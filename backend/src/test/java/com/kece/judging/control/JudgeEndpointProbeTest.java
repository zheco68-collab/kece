package com.kece.judging.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.BusinessException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class JudgeEndpointProbeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final JudgeEndpointProbe probe =
            new JudgeEndpointProbe(json, "node.example", "10.12.0.0/20,fd12:3456::/48");

    @Test
    void acceptsOnlyConfiguredHttpsRootAndAddresses() throws Exception {
        assertThat(probe.approvedEndpoint("https://NODE.example:443/"))
                .isEqualTo("https://node.example");
        assertThat(probe.approvedEndpoint("")).isNull();
        for (String endpoint : new String[] {
            "http://node.example", "https://node.example:444", "https://node.example/debug",
            "https://node.example/?url=example", "https://user@node.example",
            "https://node.example.other", "https://node.example#fragment"
        }) {
            assertThatThrownBy(() -> probe.approvedEndpoint(endpoint))
                    .isInstanceOf(BusinessException.class);
        }
        assertThat(probe.allowsAddress(InetAddress.getByName("10.12.15.255"))).isTrue();
        assertThat(probe.allowsAddress(InetAddress.getByName("10.12.16.0"))).isFalse();
        assertThat(probe.allowsAddress(InetAddress.getByName("fd12:3456:0:ffff::1"))).isTrue();
        assertThat(probe.allowsAddress(InetAddress.getByName("fd12:3456:1::1"))).isFalse();
        assertThat(probe.allowsAddress(InetAddress.getByName("169.254.169.254"))).isFalse();
        assertThat(probe.allowsAddress(InetAddress.getLoopbackAddress())).isFalse();
    }

    @Test
    void rejectsAHealthyResponseFromTheWrongNodeOrRuntime() throws Exception {
        JudgeNodes.Node node = mock(JudgeNodes.Node.class);
        when(node.id()).thenReturn(42L);
        when(node.pool()).thenReturn("python");
        when(node.runtime()).thenReturn("python-v1");
        when(node.runtimeVersion()).thenReturn("3.12");

        var response =
                json.createObjectNode()
                        .put("protocolVersion", 1)
                        .put("nodeId", "42")
                        .put("pool", "python")
                        .put("runtimeSpec", "python-v1")
                        .put("runtimeVersion", "3.12")
                        .put("healthy", true);
        assertThat(JudgeEndpointProbe.matchesNode(response, node)).isTrue();
        assertThat(JudgeEndpointProbe.matchesNode(response.deepCopy().put("nodeId", "43"), node))
                .isFalse();
        assertThat(JudgeEndpointProbe.matchesNode(response.deepCopy().put("runtimeVersion", "3.13"), node))
                .isFalse();
        assertThat(JudgeEndpointProbe.matchesNode(response.deepCopy().put("healthy", false), node))
                .isFalse();
    }
}
