package com.kece.judging.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/judgers")
@RequiredArgsConstructor
public class JudgeMachineController {
    private static final int MAX_REQUEST = 1024 * 1024;
    private final JudgeNodes nodes;
    private final JudgeScheduler scheduler;
    private final JudgeMaintenance maintenance;
    private final JudgeClaims claims;
    private final ObjectMapper json;
    @Value("${kece.judging.legacy-admin-enrollment-enabled:false}") private boolean legacyEnrollmentEnabled;

    record JoinRequest(String enrollmentCode, String bootId, int protocolVersion) {}

    record SessionRequest(String bootId, int protocolVersion) {}

    record HeartbeatRequest(
            String runtimeSpec,
            String runtimeVersion,
            int localSlots,
            boolean healthy,
            JudgeNodes.CheckReport checkResult,
            JudgeIdentityMigration.Proof identity) {}

    record ClaimRequest(int slots, int waitMs) {}

    record RenewRequest(List<JudgeScheduler.Progress> jobs) {}

    @PostMapping("/join")
    public ResponseEntity<JudgeNodes.Joined> join(HttpServletRequest request) throws IOException {
        if (!legacyEnrollmentEnabled) {
            throw new JudgeProtocolException(410, "LEGACY_ENROLLMENT_DISABLED");
        }
        var body = read(request, JoinRequest.class);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(nodes.join(body.enrollmentCode(), body.bootId(), body.protocolVersion()));
    }

    @PostMapping("/bootstrap/claim")
    public ResponseEntity<JudgeClaims.Confirmation> bootstrapClaim(HttpServletRequest request)
            throws IOException {
        var confirmation = claims.claim(read(request, JudgeClaims.Envelope.class, 16 * 1024));
        if (confirmation == null) {
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(confirmation);
    }

    @PostMapping("/session")
    public JudgeNodes.Session session(HttpServletRequest request) throws IOException {
        var body = read(request, SessionRequest.class);
        return nodes.session(access(request), body.bootId(), body.protocolVersion());
    }

    @PostMapping("/heartbeat")
    public JudgeNodes.Heartbeat heartbeat(HttpServletRequest request) throws IOException {
        var body = read(request, HeartbeatRequest.class);
        return nodes.heartbeat(
                access(request),
                body.runtimeSpec(),
                body.runtimeVersion(),
                body.localSlots(),
                body.healthy(),
                body.checkResult(),
                body.identity());
    }

    @PostMapping("/jobs/claim")
    public Object claim(HttpServletRequest request) throws IOException {
        var body = read(request, ClaimRequest.class);
        if (body.slots() < 1 || body.slots() > 16 || body.waitMs() < 0 || body.waitMs() > 15_000) {
            throw new JudgeProtocolException(422, "INVALID_CLAIM");
        }
        if (body.waitMs() == 0) {
            return maintenance.claimImmediately(access(request), body.slots());
        }
        return maintenance.waitForClaim(access(request), body.slots(), body.waitMs());
    }

    @PostMapping("/jobs/renew")
    public Map<String, List<JudgeScheduler.Renewed>> renew(HttpServletRequest request)
            throws IOException {
        var body = read(request, RenewRequest.class);
        return Map.of("jobs", scheduler.renew(access(request), body.jobs()));
    }

    @PostMapping("/jobs/{id}/result")
    public JudgeScheduler.Receipt result(@PathVariable String id, HttpServletRequest request)
            throws IOException {
        return scheduler.result(
                access(request),
                JudgeNodes.parseId(id),
                read(request, JudgeScheduler.ResultRequest.class));
    }

    @ExceptionHandler(JudgeProtocolException.class)
    public ResponseEntity<Map<String, String>> protocolError(JudgeProtocolException error) {
        return ResponseEntity.status(error.getStatus()).body(Map.of("code", error.getCode()));
    }

    private JudgeNodes.Access access(HttpServletRequest request) {
        return (JudgeNodes.Access) request.getAttribute(JudgeMachineInterceptor.ACCESS);
    }

    private <T> T read(HttpServletRequest request, Class<T> type) throws IOException {
        return read(request, type, MAX_REQUEST);
    }

    private <T> T read(HttpServletRequest request, Class<T> type, int maxBytes)
            throws IOException {
        if (request.getContentLengthLong() > maxBytes) {
            throw new JudgeProtocolException(413, "BODY_TOO_LARGE");
        }
        byte[] bytes = request.getInputStream().readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new JudgeProtocolException(413, "BODY_TOO_LARGE");
        }
        try {
            T decoded =
                    json.readerFor(type)
                            .with(
                                    com.fasterxml.jackson.databind.DeserializationFeature
                                            .FAIL_ON_TRAILING_TOKENS)
                            .readValue(bytes);
            if (decoded == null) {
                throw new JudgeProtocolException(422, "INVALID_BODY");
            }
            return decoded;
        } catch (JsonProcessingException e) {
            throw new JudgeProtocolException(422, "INVALID_BODY");
        }
    }
}
