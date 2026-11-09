package com.kece.judging.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class JudgeDiscoveryClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final JudgeDiscoveryClient client = new JudgeDiscoveryClient(json);

    @Test
    void normalizesHttpHttpsAndKnownEndpointSuffixes() {
        assertEquals("http://10.20.0.15:19091", client.normalize("10.20.0.15:19091/healthz"));
        assertEquals(
                "https://judge.example.com/worker/python-1",
                client.normalize("HTTPS://Judge.Example.Com:443/worker/python-1/v1/node/info"));
        assertThrows(RuntimeException.class, () -> client.normalize("file:///etc/passwd"));
        assertThrows(
                RuntimeException.class, () -> client.normalize("http://user:pass@example.com"));
        assertThrows(RuntimeException.class, () -> client.normalize("http://example.com:0"));
        assertThrows(
                RuntimeException.class, () -> client.normalize("http://example.com/../metadata"));
    }

    @Test
    void acceptsPublicAndPrivateAddressesButRejectsSpecialTargets() throws Exception {
        assertEquals(true, JudgeDiscoveryClient.safeAddress(InetAddress.getByName("8.8.8.8")));
        assertEquals(true, JudgeDiscoveryClient.safeAddress(InetAddress.getByName("10.20.0.15")));
        assertEquals(true, JudgeDiscoveryClient.safeAddress(InetAddress.getByName("127.0.0.1")));
        assertEquals(
                false, JudgeDiscoveryClient.safeAddress(InetAddress.getByName("169.254.169.254")));
    }

    @Test
    void verifiesSignedIdentityAndRejectsWrongChallenge() throws Exception {
        String control = "7cf26574-3858-4e57-a685-8fc947dc4b81";
        String instance = "cb7e8f06-dd79-4b1e-a6f7-9675a01861a5";
        String host = "8d7dcbe2-758d-4be2-8c84-b00eddf671d2";
        String nonce = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] key = keys.getPublic().getEncoded();
        String keyId = hex(MessageDigest.getInstance("SHA-256").digest(key));
        String capability =
                String.join("\n", instance, host, "python", "python-v1", "prod-1.1", "2", keyId);
        String digest =
                hex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(
                                        ("KECE-CAPABILITY-V1\n" + capability)
                                                .getBytes(StandardCharsets.UTF_8)));
        var payload = json.createObjectNode();
        payload.put("discoveryVersion", 1);
        payload.put("jobProtocolVersion", 1);
        payload.put("controlPlaneId", control);
        payload.put("instanceId", instance);
        payload.put("hostId", host);
        payload.put("nodeKeyId", keyId);
        payload.put("bootId", "boot-1234567890123456");
        payload.put("pool", "python");
        payload.put("runtimeSpec", "python-v1");
        payload.put("runtimeVersion", "prod-1.1");
        payload.put("displayName", "python-1");
        payload.put("localSlots", 2);
        payload.put("bindingState", "UNBOUND");
        payload.put("dependencyState", "READY");
        payload.put("capabilityDigest", digest);
        payload.put("nonce", nonce);
        String encoded =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(json.writeValueAsBytes(payload));
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(("KECE-DISCOVERY-V1\n" + encoded).getBytes(StandardCharsets.US_ASCII));
        var envelope = json.createObjectNode();
        envelope.put("publicKey", Base64.getUrlEncoder().withoutPadding().encodeToString(key));
        envelope.put("payload", encoded);
        envelope.put(
                "signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));

        var discovered = client.verify("http://10.20.0.15:19091", control, nonce, envelope);
        assertEquals(instance, discovered.instanceId());
        assertEquals(host, discovered.hostId());
        assertEquals("python", discovered.pool());
        assertEquals(2, discovered.localSlots());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        client.verify(
                                "http://10.20.0.15:19091", control,
                                "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB", envelope));
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
