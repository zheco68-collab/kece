package com.kece.judging.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Migrates a legacy credential-bound node to durable signed instance identity. */
@Service
@RequiredArgsConstructor
public class JudgeIdentityMigration {
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]+");
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public record Proof(String publicKey, String payload, String signature) {}

    private record Payload(String controlPlaneId, String nodeId, long sessionEpoch,
            String instanceId, String hostId, String nodeKeyId) {}

    public void accept(long nodeId, long hostGroupId, long sessionEpoch, Proof proof) {
        if (proof == null) {
            return;
        }
        try {
            if (proof.publicKey() == null || proof.payload() == null || proof.signature() == null
                    || proof.payload().length() > 4096 || !TOKEN.matcher(proof.publicKey()).matches()
                    || !TOKEN.matcher(proof.payload()).matches()
                    || !TOKEN.matcher(proof.signature()).matches()) {
                throw invalid();
            }
            byte[] key = Base64.getUrlDecoder().decode(proof.publicKey());
            byte[] signatureBytes = Base64.getUrlDecoder().decode(proof.signature());
            byte[] payloadBytes = Base64.getUrlDecoder().decode(proof.payload());
            if (key.length > 128 || signatureBytes.length != 64 || payloadBytes.length > 4096) {
                throw invalid();
            }
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(key)));
            verifier.update(("KECE-IDENTITY-MIGRATION-V1\n" + proof.payload()).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(signatureBytes)) {
                throw invalid();
            }
            Payload payload = json.readValue(payloadBytes, Payload.class);
            String control = db.queryForObject("SELECT control_plane_id FROM judge_control_identity WHERE id=1", String.class);
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));
            if (!control.equals(payload.controlPlaneId()) || !Long.toString(nodeId).equals(payload.nodeId())
                    || sessionEpoch != payload.sessionEpoch() || !UUID.matcher(payload.instanceId()).matches()
                    || !UUID.matcher(payload.hostId()).matches() || !fingerprint.equals(payload.nodeKeyId())) {
                throw invalid();
            }
            List<byte[]> existing = db.query("SELECT public_key FROM judge_node_identity WHERE node_id=? FOR UPDATE",
                    (r, row) -> r.getBytes(1), nodeId);
            if (!existing.isEmpty()) {
                if (!Arrays.equals(existing.get(0), key)) {
                    throw invalid();
                }
                String currentInstance = db.queryForObject(
                        "SELECT instance_id FROM judge_node_identity WHERE node_id=?", String.class, nodeId);
                if (!payload.instanceId().equals(currentInstance)) {
                    throw invalid();
                }
            } else {
                Long mappedGroup = db.query("SELECT host_group_id FROM judge_host_identity WHERE host_id=? FOR UPDATE",
                        r -> r.next() ? r.getLong(1) : null, payload.hostId());
                if (mappedGroup != null && mappedGroup != hostGroupId) {
                    throw invalid();
                }
                if (mappedGroup == null) {
                    db.update("INSERT INTO judge_host_identity (host_id,host_group_id,created_at) VALUES (?,?,UTC_TIMESTAMP(6))",
                            payload.hostId(), hostGroupId);
                }
                db.update("INSERT INTO judge_node_identity (node_id,instance_id,public_key,key_fingerprint,binding_generation) VALUES (?,?,?,?,1)",
                        nodeId, payload.instanceId(), key, fingerprint);
            }
        } catch (JudgeProtocolException e) {
            throw e;
        } catch (DuplicateKeyException e) {
            throw invalid();
        } catch (Exception e) {
            throw invalid();
        }
    }

    private static JudgeProtocolException invalid() {
        return new JudgeProtocolException(409, "IDENTITY_MIGRATION_CONFLICT");
    }
}
