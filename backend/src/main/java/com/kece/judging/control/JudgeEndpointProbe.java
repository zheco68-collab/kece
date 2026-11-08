package com.kece.judging.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class JudgeEndpointProbe {
    private static final int MAX_BODY = 4096;
    private static final Timeout IO_TIMEOUT = Timeout.ofSeconds(2);
    private final ObjectMapper json;
    private final List<String> allowedHosts;
    private final List<Network> allowedNetworks;
    private final ThreadPoolExecutor probes =
            new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, new SynchronousQueue<>());

    JudgeEndpointProbe(
            ObjectMapper json,
            @Value("${kece.judging.allowed-node-hosts:}") String hosts,
            @Value("${kece.judging.allowed-node-networks:}") String networks) {
        this.json = json;
        this.allowedHosts = values(hosts);
        this.allowedNetworks = values(networks).stream().map(Network::parse).toList();
    }

    String approvedEndpoint(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost();
            if (raw.length() <= 255
                    && "https".equals(uri.getScheme())
                    && host != null
                    && uri.getUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && (uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                    && !allowedNetworks.isEmpty()
                    && allowedHosts.stream().anyMatch(host::equalsIgnoreCase)) {
                return "https://" + host.toLowerCase(Locale.ROOT);
            }
        } catch (IllegalArgumentException ignored) {
            // Treat malformed and unauthorized endpoints uniformly.
        }
        throw new BusinessException(ResultCode.PARAM_ERROR, "节点地址须为批准主机的 HTTPS 根地址，并配置允许网段");
    }

    void verify(JudgeNodes.Node node) {
        String endpoint = approvedEndpoint(node.baseUrl());
        if (endpoint == null) {
            return;
        }
        HttpGet request = new HttpGet(endpoint + "/healthz");
        String host = URI.create(endpoint).getHost();
        Future<?> pending;
        try {
            pending = probes.submit(() -> {
                probe(request, host, node);
                return null;
            });
        } catch (RejectedExecutionException e) {
            throw new BusinessException(ResultCode.CONFLICT, "节点探测繁忙，请稍后重试");
        }
        try {
            pending.get(4, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.CONFLICT, "节点探测已中断");
        } catch (TimeoutException | ExecutionException e) {
            throw new BusinessException(ResultCode.CONFLICT, "节点 HTTPS 探测失败，请检查地址、证书、网段和节点身份");
        } finally {
            if (!pending.isDone()) {
                request.cancel();
                pending.cancel(true);
            }
        }
    }

    private void probe(HttpGet request, String host, JudgeNodes.Node node) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0 || !Arrays.stream(addresses).allMatch(this::allowsAddress)) {
            throw new IOException("Node host resolved outside approved networks");
        }
        // Resolve once, validate every answer and pin the actual connection to those answers.
        DnsResolver pinned = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String name) throws UnknownHostException {
                if (!host.equalsIgnoreCase(name)) {
                    throw new UnknownHostException(name);
                }
                return addresses.clone();
            }

            @Override
            public String resolveCanonicalHostname(String name) throws UnknownHostException {
                if (!host.equalsIgnoreCase(name)) {
                    throw new UnknownHostException(name);
                }
                return host;
            }
        };
        try (var client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .useSystemProperties()
                        .setDnsResolver(pinned)
                        .setConnectionFactory(ManagedHttpClientConnectionFactory.builder()
                                .http1Config(Http1Config.custom()
                                        .setMaxHeaderCount(32)
                                        .setMaxLineLength(4096)
                                        .build())
                                .build())
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(IO_TIMEOUT)
                                .setSocketTimeout(IO_TIMEOUT)
                                .build())
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(IO_TIMEOUT).build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableContentCompression()
                .disableCookieManagement()
                .build()) {
            client.execute(request, response -> {
                var entity = response.getEntity();
                if (response.getCode() != 200 || entity == null || entity.getContentLength() > MAX_BODY) {
                    request.cancel();
                    throw new IOException("Unexpected node probe response");
                }
                byte[] body = entity.getContent().readNBytes(MAX_BODY + 1);
                if (body.length > MAX_BODY) {
                    request.cancel();
                    throw new IOException("Node probe response too large");
                }
                if (!matchesNode(json.readTree(body), node)) {
                    throw new IOException("Node probe identity mismatch");
                }
                return null;
            });
        }
    }

    static boolean matchesNode(JsonNode data, JudgeNodes.Node node) {
        return data != null
                && data.path("protocolVersion").isIntegralNumber()
                && data.path("protocolVersion").asInt() == 1
                && Long.toString(node.id()).equals(data.path("nodeId").textValue())
                && node.pool().equals(data.path("pool").textValue())
                && node.runtime().equals(data.path("runtimeSpec").textValue())
                && node.runtimeVersion() != null
                && node.runtimeVersion().equals(data.path("runtimeVersion").textValue())
                && data.path("healthy").isBoolean()
                && data.path("healthy").asBoolean();
    }

    boolean allowsAddress(InetAddress address) {
        return !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isMulticastAddress()
                && allowedNetworks.stream().anyMatch(network -> network.contains(address.getAddress()));
    }

    private static List<String> values(String csv) {
        return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private record Network(byte[] address, int prefix) {
        static Network parse(String cidr) {
            String[] fields = cidr.split("/", -1);
            if (fields.length == 2
                    && (fields[0].matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
                            || fields[0].matches("[0-9a-fA-F:]*:[0-9a-fA-F:]+"))) {
                try {
                    byte[] address = InetAddress.getByName(fields[0]).getAddress();
                    int prefix = Integer.parseInt(fields[1]);
                    if (prefix > 0 && prefix <= address.length * Byte.SIZE) {
                        return new Network(address, prefix);
                    }
                } catch (UnknownHostException | NumberFormatException ignored) {
                    // Reject invalid operator configuration at startup.
                }
            }
            throw new IllegalArgumentException("Invalid judge node CIDR: " + cidr);
        }

        boolean contains(byte[] other) {
            if (address.length != other.length) {
                return false;
            }
            int fullBytes = prefix / Byte.SIZE;
            int bits = prefix % Byte.SIZE;
            return Arrays.equals(address, 0, fullBytes, other, 0, fullBytes)
                    && (bits == 0
                            || ((address[fullBytes] ^ other[fullBytes]) & (0xff00 >> bits) & 0xff) == 0);
        }
    }

    @PreDestroy
    void shutdown() {
        probes.shutdownNow();
    }
}
