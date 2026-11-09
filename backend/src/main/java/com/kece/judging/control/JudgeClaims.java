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
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Exchanges a previously approved worker identity for a worker-generated credential. */
@Service
@RequiredArgsConstructor
public class JudgeClaims {
    private static final Pattern UUID =
            Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]+");
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final Semaphore activeClaims = new Semaphore(8);

    public record Envelope(String publicKey, String payload, String signature) {}

    record Payload(
            int protocolVersion,
            String controlPlaneId,
            String instanceId,
            String nodeKeyId,
            String capabilityDigest,
            String claimId,
            String bootId,
            long issuedAt,
            String nonce,
            String credential) {}

    public record Confirmation(
            String connectionId,
            String claimId,
            String nodeId,
            long bindingGeneration,
            int protocolVersion,
            String pool,
            String runtimeSpec) {}

    private record Identity(long nodeId, byte[] key, String fingerprint, long generation, boolean revoked) {}

    private record Approval(
            long id,
            long nodeId,
            String instanceId,
            String capabilityDigest,
            long generation,
            String claimId,
            String credentialDigest,
            String status,
            boolean live) {}

    public Confirmation claim(Envelope envelope) {
        if (!activeClaims.tryAcquire()) {
            throw new JudgeProtocolException(429, "CLAIM_BUSY");
        }
        try {
            var verified = verify(envelope);
            String control =
                    db.queryForObject(
                            "SELECT control_plane_id FROM judge_control_identity WHERE id=1",
                            String.class);
            if (!control.equals(verified.payload().controlPlaneId())) {
                throw invalid();
            }
            return transaction.execute(ignored -> bind(verified));
        } catch (DuplicateKeyException collision) {
            throw invalid();
        } finally {
            activeClaims.release();
        }
    }

    private Confirmation bind(Verified verified) {
        Payload payload = verified.payload();
        List<Identity> identities =
                db.query(
                        "SELECT node_id,public_key,key_fingerprint,binding_generation,revoked_at FROM judge_node_identity WHERE instance_id=? FOR UPDATE",
                        (r, row) ->
                                new Identity(
                                        r.getLong(1),
                                        r.getBytes(2),
                                        r.getString(3),
                                        r.getLong(4),
                                        r.getTimestamp(5) != null),
                        payload.instanceId());
        if (identities.isEmpty()) {
            return null;
        }
        Identity identity = identities.get(0);
        if (identity.revoked()
                || !Arrays.equals(identity.key(), verified.key())
                || !identity.fingerprint().equals(payload.nodeKeyId())) {
            throw invalid();
        }
        String digest = JudgeTokens.digest(payload.credential());
        List<Approval> prior =
                db.query(
                        "SELECT id,node_id,instance_id,capability_digest,binding_generation,claim_id,credential_digest,status,expires_at>UTC_TIMESTAMP(6) AS live "
                                + "FROM judge_node_connection WHERE claim_id=? FOR UPDATE",
                        JudgeClaims::approval,
                        payload.claimId());
        if (!prior.isEmpty()) {
            Approval approved = prior.get(0);
            String nodeCredential =
                    db.queryForObject(
                            "SELECT credential_hash FROM judge_node WHERE id=? FOR UPDATE",
                            String.class,
                            identity.nodeId());
            if (approved.nodeId() != identity.nodeId()
                    || !payload.instanceId().equals(approved.instanceId())
                    || approved.generation() != identity.generation()
                    || !digest.equals(approved.credentialDigest())
                    || !digest.equals(nodeCredential)
                    || "CANCELLED".equals(approved.status())) {
                throw invalid();
            }
            return confirmation(approved, payload);
        }
        List<Approval> pending =
                db.query(
                        "SELECT id,node_id,instance_id,capability_digest,binding_generation,claim_id,credential_digest,status,expires_at>UTC_TIMESTAMP(6) AS live "
                                + "FROM judge_node_connection WHERE active_instance_id=? AND status='WAITING_BIND' FOR UPDATE",
                        JudgeClaims::approval,
                        payload.instanceId());
        if (pending.isEmpty()) {
            return null;
        }
        Approval approved = pending.get(0);
        if (!approved.live()
                || approved.nodeId() != identity.nodeId()
                || approved.generation() != identity.generation()
                || !approved.capabilityDigest().equals(payload.capabilityDigest())) {
            throw invalid();
        }
        String currentCredential =
                db.queryForObject(
                        "SELECT credential_hash FROM judge_node WHERE id=? FOR UPDATE",
                        String.class,
                        identity.nodeId());
        if (currentCredential != null) {
            throw invalid();
        }
        db.update(
                "INSERT INTO judge_claim_nonce (nonce_digest,expires_at) VALUES (?,UTC_TIMESTAMP(6)+INTERVAL 5 MINUTE)",
                JudgeTokens.digest(payload.nonce()));
        if (db.update(
                        "UPDATE judge_node SET credential_hash=? WHERE id=? AND credential_hash IS NULL AND state='PENDING_VERIFICATION'",
                        digest,
                        identity.nodeId())
                != 1) {
            throw invalid();
        }
        if (db.update(
                        "UPDATE judge_node_connection SET claim_id=?,credential_digest=?,status='WAITING_HEARTBEAT',updated_at=UTC_TIMESTAMP(6) WHERE id=? AND status='WAITING_BIND' AND expires_at>UTC_TIMESTAMP(6)",
                        payload.claimId(),
                        digest,
                        approved.id())
                != 1) {
            throw invalid();
        }
        db.update(
                "INSERT INTO judge_node_audit (node_id,actor_id,action,revision,created_at) "
                        + "SELECT ?,actor_id,'API_BIND',1,UTC_TIMESTAMP(6) FROM judge_node_connection WHERE id=?",
                identity.nodeId(),
                approved.id());
        return confirmation(approved, payload);
    }

    private Confirmation confirmation(Approval approved, Payload payload) {
        return db.queryForObject(
                "SELECT pool,runtime_spec FROM judge_node WHERE id=?",
                (r, row) ->
                        new Confirmation(
                                Long.toString(approved.id()),
                                payload.claimId(),
                                Long.toString(approved.nodeId()),
                                approved.generation(),
                                1,
                                r.getString(1),
                                r.getString(2)),
                approved.nodeId());
    }

    private static Approval approval(java.sql.ResultSet r, int row) throws java.sql.SQLException {
        return new Approval(
                r.getLong(1),
                r.getLong(2),
                r.getString(3),
                r.getString(4),
                r.getLong(5),
                r.getString(6),
                r.getString(7),
                r.getString(8),
                r.getBoolean(9));
    }

    private record Verified(Payload payload, byte[] key) {}

    private Verified verify(Envelope envelope) {
        if (envelope == null
                || envelope.publicKey() == null
                || envelope.payload() == null
                || envelope.signature() == null
                || !TOKEN.matcher(envelope.publicKey()).matches()
                || !TOKEN.matcher(envelope.payload()).matches()
                || !TOKEN.matcher(envelope.signature()).matches()
                || envelope.payload().length() > 16 * 1024) {
            throw invalid();
        }
        try {
            byte[] key = Base64.getUrlDecoder().decode(envelope.publicKey());
            byte[] signatureBytes = Base64.getUrlDecoder().decode(envelope.signature());
            byte[] payloadBytes = Base64.getUrlDecoder().decode(envelope.payload());
            if (key.length > 128 || signatureBytes.length != 64 || payloadBytes.length > 16 * 1024) {
                throw invalid();
            }
            var publicKey =
                    KeyFactory.getInstance("Ed25519")
                            .generatePublic(new X509EncodedKeySpec(key));
            var signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey);
            signature.update(
                    ("KECE-BOOTSTRAP-CLAIM-V1\n" + envelope.payload())
                            .getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(signatureBytes)) {
                throw invalid();
            }
            Payload payload = json.readValue(payloadBytes, Payload.class);
            String fingerprint =
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(key));
            long now = System.currentTimeMillis();
            if (payload.protocolVersion() != 1
                    || !UUID.matcher(payload.controlPlaneId()).matches()
                    || !UUID.matcher(payload.instanceId()).matches()
                    || !UUID.matcher(payload.claimId()).matches()
                    || !fingerprint.equals(payload.nodeKeyId())
                    || payload.capabilityDigest() == null
                    || !payload.capabilityDigest().matches("[0-9a-f]{64}")
                    || payload.bootId() == null
                    || !payload.bootId().matches("[A-Za-z0-9_-]{16,64}")
                    || !JudgeTokens.valid(payload.nonce())
                    || !JudgeTokens.valid(payload.credential())
                    || payload.issuedAt() < now - 120_000
                    || payload.issuedAt() > now + 120_000) {
                throw invalid();
            }
            return new Verified(payload, key);
        } catch (Exception e) {
            throw invalid();
        }
    }

    @Scheduled(fixedDelay = 60_000, scheduler = "judgeConnectionExecutor")
    public void expireNonces() {
        db.update("DELETE FROM judge_claim_nonce WHERE expires_at<UTC_TIMESTAMP(6) LIMIT 1000");
    }

    private static JudgeProtocolException invalid() {
        return new JudgeProtocolException(401, "INVALID_NODE_CLAIM");
    }
}
