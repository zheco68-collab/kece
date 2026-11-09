package com.kece.judging.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class JudgeDiscoveryClient {
    private static final int MAX_BODY = 16 * 1024;
    private static final Timeout TIMEOUT = Timeout.ofSeconds(2);
    private static final Pattern UUID =
            Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern RUNTIME = Pattern.compile("[A-Za-z0-9._+-]{1,100}");
    private static final Pattern BOOT = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private static final Set<String> POOLS = Set.of("cpp", "java", "python", "mysql", "arkts");
    private final ObjectMapper json;

    record Discovery(
            String normalizedUrl,
            String instanceId,
            String hostId,
            String keyFingerprint,
            byte[] publicKey,
            String bootId,
            String pool,
            String runtimeSpec,
            String runtimeVersion,
            String displayName,
            int localSlots,
            String capabilityDigest,
            String bindingState,
            String dependencyState) {}

    String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalidAddress();
        }
        String candidate = raw.strip();
        if (!candidate.contains("://")) {
            candidate = "http://" + candidate;
        }
        try {
            URI uri = URI.create(candidate);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            String path = uri.getRawPath();
            if (!(scheme.equals("http") || scheme.equals("https"))
                    || host == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getPort() == 0
                    || uri.getPort() > 65535
                    || candidate.length() > 1024
                    || candidate.contains("\\")
                    || path.contains("%")
                    || path.contains("//")
                    || Arrays.stream(path.split("/"))
                            .anyMatch(p -> p.equals("..") || p.equals("."))) {
                throw invalidAddress();
            }
            if (path.endsWith("/v1/node/info")) {
                path = path.substring(0, path.length() - "/v1/node/info".length());
            } else if (path.endsWith("/healthz")) {
                path = path.substring(0, path.length() - "/healthz".length());
            }
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            int port = uri.getPort();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
                port = -1;
            }
            String normalized =
                    new URI(scheme, null, host.toLowerCase(Locale.ROOT), port, path, null, null)
                            .toASCIIString();
            if (normalized.length() > 1024) {
                throw invalidAddress();
            }
            return normalized;
        } catch (Exception e) {
            throw invalidAddress();
        }
    }

    Discovery discover(String raw, String controlPlaneId) {
        String base = normalize(raw);
        String host = URI.create(base).getHost();
        String nonce = JudgeTokens.newToken();
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0
                    || !Arrays.stream(addresses).allMatch(JudgeDiscoveryClient::safeAddress)) {
                throw new IllegalArgumentException("unsafe endpoint address");
            }
            DnsResolver pinned =
                    new DnsResolver() {
                        @Override
                        public InetAddress[] resolve(String name) throws UnknownHostException {
                            if (!host.equalsIgnoreCase(name)) {
                                throw new UnknownHostException(name);
                            }
                            return addresses.clone();
                        }

                        @Override
                        public String resolveCanonicalHostname(String name)
                                throws UnknownHostException {
                            if (!host.equalsIgnoreCase(name)) {
                                throw new UnknownHostException(name);
                            }
                            return host;
                        }
                    };
            var ssl =
                    SSLContexts.custom().loadTrustMaterial(null, (chain, authType) -> true).build();
            var manager =
                    PoolingHttpClientConnectionManagerBuilder.create()
                            .setDnsResolver(pinned)
                            .setSSLSocketFactory(
                                    SSLConnectionSocketFactoryBuilder.create()
                                            .setSslContext(ssl)
                                            .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                                            .build())
                            .setDefaultConnectionConfig(
                                    ConnectionConfig.custom()
                                            .setConnectTimeout(TIMEOUT)
                                            .setSocketTimeout(TIMEOUT)
                                            .build())
                            .build();
            try (var client =
                    HttpClients.custom()
                            .setConnectionManager(manager)
                            .setDefaultRequestConfig(
                                    RequestConfig.custom().setResponseTimeout(TIMEOUT).build())
                            .disableRedirectHandling()
                            .disableAutomaticRetries()
                            .disableCookieManagement()
                            .disableContentCompression()
                            .build()) {
                var request = new HttpPost(base + "/v1/node/info");
                request.setEntity(
                        new StringEntity(
                                json.writeValueAsString(
                                        java.util.Map.of(
                                                "discoveryVersion",
                                                1,
                                                "controlPlaneId",
                                                controlPlaneId,
                                                "nonce",
                                                nonce)),
                                ContentType.APPLICATION_JSON));
                return client.execute(
                        request,
                        response -> {
                            if (response.getCode() != 200
                                    || response.getEntity() == null
                                    || response.getEntity().getContentLength() > MAX_BODY) {
                                throw new java.io.IOException(
                                        "unsupported node discovery response");
                            }
                            byte[] body =
                                    response.getEntity().getContent().readNBytes(MAX_BODY + 1);
                            if (body.length > MAX_BODY) {
                                throw new java.io.IOException("node discovery response too large");
                            }
                            try {
                                return verify(base, controlPlaneId, nonce, json.readTree(body));
                            } catch (Exception e) {
                                throw new IOException("invalid signed discovery response", e);
                            }
                        });
            }
        } catch (Exception e) {
            throw new BusinessException(ResultCode.CONFLICT, "无法验证评测机发现接口，请检查地址、网络和节点版本");
        }
    }

    Discovery verify(String base, String controlPlaneId, String nonce, JsonNode envelope)
            throws Exception {
        String keyText = envelope.path("publicKey").asText();
        String payloadText = envelope.path("payload").asText();
        String signatureText = envelope.path("signature").asText();
        if (!TOKEN.matcher(keyText).matches()
                || !TOKEN.matcher(payloadText).matches()
                || !TOKEN.matcher(signatureText).matches()
                || payloadText.length() > MAX_BODY) {
            throw new IllegalArgumentException("invalid discovery envelope");
        }
        byte[] keyBytes = Base64.getUrlDecoder().decode(keyText);
        byte[] signatureBytes = Base64.getUrlDecoder().decode(signatureText);
        byte[] payloadBytes = Base64.getUrlDecoder().decode(payloadText);
        if (keyBytes.length > 128
                || signatureBytes.length != 64
                || payloadBytes.length > MAX_BODY) {
            throw new IllegalArgumentException("invalid discovery sizes");
        }
        var publicKey =
                KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(keyBytes));
        var signature = Signature.getInstance("Ed25519");
        signature.initVerify(publicKey);
        signature.update(("KECE-DISCOVERY-V1\n" + payloadText).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(signatureBytes)) {
            throw new IllegalArgumentException("invalid discovery signature");
        }
        JsonNode payload = json.readTree(payloadBytes);
        String instanceId = payload.path("instanceId").asText();
        String hostId = payload.path("hostId").asText();
        String pool = payload.path("pool").asText();
        String runtimeSpec = payload.path("runtimeSpec").asText();
        String runtimeVersion = payload.path("runtimeVersion").asText();
        String bootId = payload.path("bootId").asText();
        String displayName = payload.path("displayName").asText();
        String bindingState = payload.path("bindingState").asText();
        String dependencyState = payload.path("dependencyState").asText();
        String keyFingerprint = hex(MessageDigest.getInstance("SHA-256").digest(keyBytes));
        if (payload.path("discoveryVersion").asInt() != 1
                || payload.path("jobProtocolVersion").asInt() != 1
                || !controlPlaneId.equals(payload.path("controlPlaneId").asText())
                || !nonce.equals(payload.path("nonce").asText())
                || !UUID.matcher(instanceId).matches()
                || !UUID.matcher(hostId).matches()
                || !keyFingerprint.equals(payload.path("nodeKeyId").asText())
                || !POOLS.contains(pool)
                || !runtimeSpec.equals(pool + "-v1")
                || !RUNTIME.matcher(runtimeVersion).matches()
                || !BOOT.matcher(bootId).matches()
                || displayName.isBlank()
                || displayName.length() > 100
                || displayName.chars().anyMatch(Character::isISOControl)
                || !Set.of("UNBOUND", "BOUND").contains(bindingState)
                || !Set.of("CHECKING", "READY", "UNHEALTHY").contains(dependencyState)
                || !payload.path("localSlots").isIntegralNumber()
                || payload.path("localSlots").asInt() < 1
                || payload.path("localSlots").asInt() > 256) {
            throw new IllegalArgumentException("invalid discovery identity");
        }
        int slots = payload.path("localSlots").asInt();
        String capability =
                String.join(
                        "\n",
                        instanceId,
                        hostId,
                        pool,
                        runtimeSpec,
                        runtimeVersion,
                        Integer.toString(slots),
                        keyFingerprint);
        String digest =
                hex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(
                                        ("KECE-CAPABILITY-V1\n" + capability)
                                                .getBytes(StandardCharsets.UTF_8)));
        if (!digest.equals(payload.path("capabilityDigest").asText())) {
            throw new IllegalArgumentException("invalid capability digest");
        }
        return new Discovery(
                base,
                instanceId,
                hostId,
                keyFingerprint,
                keyBytes,
                bootId,
                pool,
                runtimeSpec,
                runtimeVersion,
                displayName,
                slots,
                digest,
                bindingState,
                dependencyState);
    }

    static boolean safeAddress(InetAddress address) {
        return !address.isAnyLocalAddress()
                && !address.isLinkLocalAddress()
                && !address.isMulticastAddress();
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }

    private static BusinessException invalidAddress() {
        return new BusinessException(ResultCode.PARAM_ERROR, "请输入评测机 HTTP 或 HTTPS API 地址");
    }
}
