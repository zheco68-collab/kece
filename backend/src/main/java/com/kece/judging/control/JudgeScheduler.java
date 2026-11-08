package com.kece.judging.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.database.entity.DbSubmission;
import com.kece.database.judging.DatabaseJudgeSettlement;
import com.kece.database.mapper.DbSubmissionMapper;
import com.kece.submission.dto.SubmissionResultVO;
import com.kece.submission.dto.SubmissionVO;
import com.kece.submission.entity.Submission;
import com.kece.submission.enums.SubmissionStatus;
import com.kece.submission.judging.CodeJudgeSettlement;
import com.kece.submission.mapper.SubmissionMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JudgeScheduler {
    private static final int LEASE_SECONDS = 60;
    private static final String CURRENT_JOB =
            """
            ((j.submission_type='ALGO' AND EXISTS (
              SELECT 1 FROM submission s WHERE s.id=j.submission_id AND s.current_judge_job_id=j.id))
             OR (j.submission_type='DATABASE' AND EXISTS (
              SELECT 1 FROM db_submission s WHERE s.id=j.submission_id AND s.current_judge_job_id=j.id)))
            """;
    private static final String JOB_FIELDS =
            """
            SELECT id, submission_type, submission_id, node_id, session_epoch, lease_epoch, state,
                   COALESCE(lease_until>UTC_TIMESTAMP(6),0) AS active, attempt_count, max_attempts, manifest_id
            FROM judge_job WHERE id=?
            """;

    private final JdbcTemplate db;
    private final JudgeNodes nodes;
    private final SubmissionMapper submissions;
    private final DbSubmissionMapper databaseSubmissions;
    private final CodeJudgeSettlement codeSettlement;
    private final DatabaseJudgeSettlement databaseSettlement;
    private final ObjectMapper json;

    public record ClaimBatch(int protocolVersion, List<ClaimedJob> jobs) {}

    public record ClaimedJob(
            String jobId,
            String submissionType,
            String submissionId,
            int generation,
            String pool,
            String runtimeSpec,
            long leaseEpoch,
            long leaseRemainingMs,
            String manifestId,
            JsonNode manifest,
            JsonNode source,
            String artifactToken) {}

    public record Progress(
            String jobId, long leaseEpoch, long sequence, String phase, int finishedCases) {}

    public record Renewed(String jobId, String status, long leaseRemainingMs) {}

    public record ResultRequest(
            long leaseEpoch, String status, SubmissionResultVO result, String errorCode) {}

    public record Receipt(String status) {}

    private record Job(
            long id,
            String type,
            long submissionId,
            long node,
            long session,
            long epoch,
            String state,
            boolean active,
            int attempts,
            int maxAttempts,
            long manifestId) {}

    private record BusinessSubmission(Submission code, DbSubmission sql) {
        boolean owns(long jobId) {
            Long current =
                    code != null
                            ? code.getCurrentJudgeJobId()
                            : sql != null ? sql.getCurrentJudgeJobId() : null;
            return Objects.equals(current, jobId);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ClaimBatch claim(JudgeNodes.Access access, int slots) {
        var hint = nodes.find(access.nodeId(), false);
        int hostSlots = nodes.hostSlots(hint.host());
        var n = nodes.lock(access, true);
        if (!"ENABLED".equals(n.state()) || !n.fresh() || !n.healthy() || !n.verified()) {
            return new ClaimBatch(1, List.of());
        }
        int capacity =
                Math.min(
                        Math.min(
                                slots,
                                Math.min(n.localSlots(), n.maxSlots()) - nodes.running(n.id())),
                        hostSlots - nodes.hostUsed(n.host()));
        if (capacity <= 0) {
            return new ClaimBatch(1, List.of());
        }
        var ids =
                db.query(
                        """
                SELECT j.id FROM judge_job j
                WHERE j.pool=? AND j.runtime_spec=? AND j.state='QUEUED' AND j.manifest_id IS NOT NULL
                  AND j.available_at<=UTC_TIMESTAMP(6) AND j.attempt_count<j.max_attempts AND
                """
                                + CURRENT_JOB
                                + """
                ORDER BY j.available_at,j.id LIMIT ? FOR UPDATE SKIP LOCKED
                """,
                        (r, i) -> r.getLong(1),
                        n.pool(),
                        n.runtime(),
                        capacity);
        var batch = new ArrayList<ClaimedJob>();
        for (long id : ids) {
            String token = JudgeTokens.newToken();
            db.update(
                    """
                    UPDATE judge_job SET state='RUNNING', node_id=?,host_group_id=?,session_epoch=?,
                      lease_epoch=lease_epoch+1,lease_until=UTC_TIMESTAMP(6)+INTERVAL 60 SECOND,
                      artifact_token_hash=?,attempt_count=attempt_count+1,finished_cases=0,
                      progress_seq=0,progress_phase=IF(submission_type='ALGO','COMPILING','JUDGING'),
                      status_version=status_version+1
                    WHERE id=?
                    """,
                    n.id(),
                    n.host(),
                    n.epoch(),
                    JudgeTokens.digest(token),
                    id);
            db.update(
                    """
                    INSERT INTO judge_job_attempt (job_id,lease_epoch,node_id,session_epoch,started_at)
                    SELECT id,lease_epoch,node_id,session_epoch,UTC_TIMESTAMP(6) FROM judge_job WHERE id=?
                    """,
                    id);
            batch.add(
                    db.queryForObject(
                            """
                    SELECT j.*, m.document,
                        TIMESTAMPDIFF(MICROSECOND,UTC_TIMESTAMP(6),lease_until) DIV 1000 AS remaining
                    FROM judge_job j JOIN judge_manifest m ON m.id=j.manifest_id WHERE j.id=?
                    """,
                            (r, i) ->
                                    new ClaimedJob(
                                            r.getString("id"),
                                            r.getString("submission_type"),
                                            r.getString("submission_id"),
                                            r.getInt("generation"),
                                            r.getString("pool"),
                                            r.getString("runtime_spec"),
                                            r.getLong("lease_epoch"),
                                            r.getLong("remaining"),
                                            r.getString("manifest_id"),
                                            readJson(r.getString("document")),
                                            readJson(r.getString("request_snapshot")),
                                            token),
                            id));
        }
        return new ClaimBatch(1, batch);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Renewed> renew(JudgeNodes.Access access, List<Progress> items) {
        var n = nodes.lock(access, true);
        var result = new ArrayList<Renewed>();
        var seen = new HashSet<Long>();
        if (items == null || items.isEmpty() || items.size() > 32) {
            throw new JudgeProtocolException(422, "INVALID_RENEW_BATCH");
        }
        for (Progress p : items) {
            if (p == null) {
                throw new JudgeProtocolException(422, "INVALID_PROGRESS");
            }
            long id = JudgeNodes.parseId(p.jobId());
            if (!seen.add(id)
                    || p.leaseEpoch() <= 0
                    || p.sequence() < 0
                    || p.finishedCases() < 0
                    || (!"COMPILING".equals(p.phase()) && !"JUDGING".equals(p.phase()))) {
                throw new JudgeProtocolException(422, "INVALID_PROGRESS");
            }
            boolean current =
                    !db.query(
                                    "SELECT id FROM judge_job j WHERE j.id=? AND " + CURRENT_JOB,
                                    (r, i) -> r.getLong(1),
                                    id)
                            .isEmpty();
            int count =
                    !current
                            ? 0
                            : db.update(
                                    """
                    UPDATE judge_job j SET lease_until=UTC_TIMESTAMP(6)+INTERVAL 60 SECOND,
                      status_version=status_version+IF(progress_seq < ?,1,0),
                      progress_phase=IF(progress_seq < ? AND progress_phase <> 'JUDGING',?,progress_phase),
                      finished_cases=IF(progress_seq < ?,GREATEST(finished_cases,?),finished_cases),
                      progress_seq=GREATEST(progress_seq,?)
                    WHERE id=? AND node_id=? AND session_epoch=? AND lease_epoch=? AND state='RUNNING'
                      AND lease_until>UTC_TIMESTAMP(6) AND total_cases>=?
                    """,
                                    p.sequence(),
                                    p.sequence(),
                                    p.phase(),
                                    p.sequence(),
                                    p.finishedCases(),
                                    p.sequence(),
                                    id,
                                    n.id(),
                                    n.epoch(),
                                    p.leaseEpoch(),
                                    p.finishedCases());
            result.add(
                    new Renewed(
                            p.jobId(),
                            count == 1 ? "RENEWED" : "STALE_LEASE",
                            count == 1 ? LEASE_SECONDS * 1000L : 0));
        }
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt result(JudgeNodes.Access access, long id, ResultRequest request) {
        var n = nodes.lock(access, true);
        Job hint = requireJob(id, false);
        BusinessSubmission business = lockBusiness(hint);
        Job j = requireJob(id, true);
        if (!business.owns(id) || request.leaseEpoch() <= 0) {
            throw staleLease();
        }
        String digest = JudgeTokens.digest(writeJson(request));
        String prior =
                db
                        .query(
                                """
                SELECT receipt_digest FROM judge_job_attempt
                WHERE job_id=? AND lease_epoch=? AND node_id=? AND session_epoch=? AND receipt_digest IS NOT NULL
                """,
                                (r, i) -> r.getString(1),
                                id,
                                request.leaseEpoch(),
                                n.id(),
                                n.epoch())
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (prior != null) {
            if (!prior.equals(digest)) {
                throw new JudgeProtocolException(409, "RESULT_CONFLICT");
            }
            return new Receipt("ALREADY_COMMITTED");
        }
        if (!"RUNNING".equals(j.state())
                || !j.active()
                || j.node() != n.id()
                || j.session() != n.epoch()
                || j.epoch() != request.leaseEpoch()) {
            throw staleLease();
        }
        if (request.errorCode() != null) {
            if (request.result() != null
                    || request.status() != null
                    || !request.errorCode().matches("[A-Z0-9_]{1,64}")) {
                throw new JudgeProtocolException(422, "INVALID_INFRASTRUCTURE_FAILURE");
            }
            checkStillActive(j.id());
            boolean retry = fail(j, business, request.errorCode(), digest, false);
            return new Receipt(retry ? "RETRY_SCHEDULED" : "COMMITTED");
        }
        validateResult(j, request);
        String result = writeJson(request.result());
        if (db.update(
                        """
                UPDATE judge_job SET state='COMPLETED', result_json=?, result_digest=?,
                   artifact_token_hash=NULL, lease_until=NULL, finished_at=UTC_TIMESTAMP(6),
                   status_version=status_version+1
                WHERE id=? AND lease_until>UTC_TIMESTAMP(6)
                """,
                        result,
                        digest,
                        id)
                != 1) {
            throw staleLease();
        }
        finishAttempt(j, "COMPLETED", digest);
        settle(j, business, request.status(), request.result());
        return new Receipt("COMMITTED");
    }

    public List<Long> expired() {
        return db.query(
                """
                SELECT id FROM judge_job WHERE state='RUNNING' AND lease_until<=UTC_TIMESTAMP(6)
                ORDER BY lease_until,id LIMIT 32
                """,
                (r, i) -> r.getLong(1));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reap(long id) {
        Job hint = requireJob(id, false);
        BusinessSubmission business = lockBusiness(hint);
        Job j = requireJob(id, true);
        if (!"RUNNING".equals(j.state()) || j.active()) {
            return;
        }
        if (!business.owns(id)) {
            db.update(
                    """
                    UPDATE judge_job SET state='CANCELLED', artifact_token_hash=NULL, lease_until=NULL,
                      finished_at=UTC_TIMESTAMP(6),status_version=status_version+1 WHERE id=?
                    """,
                    id);
            finishAttempt(j, "CANCELLED", null);
            return;
        }
        fail(j, business, "LEASE_EXPIRED", null, true);
    }

    private boolean fail(
            Job j, BusinessSubmission business, String error, String digest, boolean expired) {
        boolean retry = j.attempts() < j.maxAttempts();
        if (retry) {
            int delay = expired ? 0 : 1 << Math.min(j.attempts(), 5);
            db.update(
                    """
                    UPDATE judge_job SET state='QUEUED', available_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),
                      node_id=NULL, host_group_id=NULL, session_epoch=NULL, lease_until=NULL, artifact_token_hash=NULL,
                      progress_seq=0, progress_phase=NULL, finished_cases=0, status_version=status_version+1,
                      last_error_code=? WHERE id=?
                    """,
                    delay,
                    error,
                    j.id());
        } else {
            var result = SubmissionResultVO.builder().score(0).testCasesReport(List.of()).build();
            db.update(
                    """
                    UPDATE judge_job SET state='FAILED', result_json=?, result_digest=?, last_error_code=?,
                      lease_until=NULL, artifact_token_hash=NULL, finished_at=UTC_TIMESTAMP(6),
                      status_version=status_version+1 WHERE id=?
                    """,
                    writeJson(result),
                    digest,
                    error,
                    j.id());
            settle(j, business, "SYSTEM_ERROR", result);
        }
        finishAttempt(j, retry ? "RETRY_SCHEDULED" : "FAILED", digest);
        return retry;
    }

    private void finishAttempt(Job j, String outcome, String digest) {
        db.update(
                """
                UPDATE judge_job_attempt SET outcome=?, receipt_digest=?, ended_at=UTC_TIMESTAMP(6)
                WHERE job_id=? AND lease_epoch=?
                """,
                outcome,
                digest,
                j.id(),
                j.epoch());
    }

    private void settle(
            Job j, BusinessSubmission business, String status, SubmissionResultVO result) {
        var vo =
                SubmissionVO.builder()
                        .submissionId(j.submissionId())
                        .status(status)
                        .result(result)
                        .build();
        if (business.code() != null) {
            codeSettlement.applyPersistent(business.code(), vo);
        } else {
            databaseSettlement.applyPersistent(business.sql(), vo);
        }
        db.update(
                """
                INSERT INTO judge_settlement_event (job_id,event_type,available_at)
                VALUES (?,'SUBMISSION_SETTLED',UTC_TIMESTAMP(6))
                """,
                j.id());
    }

    private void validateResult(Job j, ResultRequest request) {
        SubmissionStatus status = SubmissionStatus.from(request.status());
        SubmissionResultVO result = request.result();
        if (status == null
                || !status.isTerminal()
                || status == SubmissionStatus.SYSTEM_ERROR
                || result == null
                || result.getScore() == null
                || result.getScore() < 0
                || result.getScore() > 100
                || tooLong(result.getCompileErrorLog(), 65_536)
                || negative(result.getMemoryUsedKb())
                || negative(result.getTimeUsedMs())) {
            throw new JudgeProtocolException(422, "INVALID_RESULT");
        }
        JsonNode manifest =
                readJson(
                        db.queryForObject(
                                "SELECT document FROM judge_manifest WHERE id=?",
                                String.class,
                                j.manifestId()));
        var expected = new HashSet<Long>();
        for (var c : manifest.path("cases")) {
            expected.add(c.path("caseId").asLong());
        }
        var report = result.getTestCasesReport();
        if (report == null
                || (status == SubmissionStatus.COMPILE_ERROR
                        ? !report.isEmpty() || result.getScore() != 0
                        : report.size() != expected.size())) {
            throw new JudgeProtocolException(422, "INVALID_CASE_REPORT");
        }
        for (var c : report) {
            SubmissionStatus verdict = c == null ? null : SubmissionStatus.from(c.getStatus());
            if (verdict == null
                    || !verdict.isTerminal()
                    || verdict == SubmissionStatus.SYSTEM_ERROR
                    || verdict == SubmissionStatus.COMPILE_ERROR
                    || !expected.remove(c.getCaseId())
                    || (status == SubmissionStatus.ACCEPTED && verdict != SubmissionStatus.ACCEPTED)
                    || tooLong(c.getExpectedOutput(), 65_536)
                    || tooLong(c.getActualOutput(), 65_536)
                    || negative(c.getTimeMs())
                    || negative(c.getMemoryKb())) {
                throw new JudgeProtocolException(422, "INVALID_CASE_REPORT");
            }
        }
        if (status == SubmissionStatus.ACCEPTED && result.getScore() != 100) {
            throw new JudgeProtocolException(422, "INVALID_RESULT");
        }
    }

    private static boolean negative(Integer n) {
        return n != null && n < 0;
    }

    private static boolean tooLong(String text, int limit) {
        return text != null && text.length() > limit;
    }

    private void checkStillActive(long id) {
        if (!Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT lease_until>UTC_TIMESTAMP(6) FROM judge_job WHERE id=?",
                        Boolean.class,
                        id))) {
            throw staleLease();
        }
    }

    private BusinessSubmission lockBusiness(Job job) {
        return "ALGO".equals(job.type())
                ? new BusinessSubmission(submissions.findByIdForUpdate(job.submissionId()), null)
                : new BusinessSubmission(
                        null, databaseSubmissions.findByIdForUpdate(job.submissionId()));
    }

    private Job requireJob(long id, boolean lock) {
        return db
                .query(
                        JOB_FIELDS + (lock ? " FOR UPDATE" : ""),
                        (r, i) ->
                                new Job(
                                        r.getLong("id"),
                                        r.getString("submission_type"),
                                        r.getLong("submission_id"),
                                        r.getLong("node_id"),
                                        r.getLong("session_epoch"),
                                        r.getLong("lease_epoch"),
                                        r.getString("state"),
                                        r.getBoolean("active"),
                                        r.getInt("attempt_count"),
                                        r.getInt("max_attempts"),
                                        r.getLong("manifest_id")),
                        id)
                .stream()
                .findFirst()
                .orElseThrow(JudgeScheduler::staleLease);
    }

    private static JudgeProtocolException staleLease() {
        return new JudgeProtocolException(409, "STALE_LEASE");
    }

    private JsonNode readJson(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid persisted judging document", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode judging result", e);
        }
    }
}
