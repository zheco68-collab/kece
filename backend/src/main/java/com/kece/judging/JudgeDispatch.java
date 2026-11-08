package com.kece.judging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.database.entity.DbSubmission;
import com.kece.database.mapper.DbSubmissionMapper;
import com.kece.judging.artifact.JudgeManifestMapper;
import com.kece.judging.persistence.JudgeJob;
import com.kece.judging.persistence.JudgeJobMapper;
import com.kece.submission.dto.ProgressVO;
import com.kece.submission.dto.SubmissionVO;
import com.kece.submission.entity.Submission;
import com.kece.submission.enums.SubmissionStatus;
import com.kece.submission.mapper.SubmissionMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creates persistent judging jobs and resolves live judging progress from current job pointers. */
@Service
@RequiredArgsConstructor
public class JudgeDispatch {

    public enum Kind {
        ALGO,
        DATABASE
    }

    public enum Scene {
        PRACTICE,
        PROBLEM,
        EXAM,
        REJUDGE
    }

    private final ObjectMapper objectMapper;
    private final JudgeJobMapper jobs;
    private final JudgeManifestMapper manifests;
    private final SubmissionMapper submissions;
    private final DbSubmissionMapper dbSubmissions;

    @Transactional(propagation = Propagation.MANDATORY)
    public SubmissionVO enqueueCode(Submission submission, Map<String, String> code, Scene scene) {
        return enqueue(
                Kind.ALGO,
                submission.getId(),
                submission.getQuestionId(),
                submission.getCurrentJudgeJobId(),
                submission.getLanguage(),
                scene,
                code);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SubmissionVO enqueueSql(DbSubmission submission, Scene scene) {
        return enqueue(
                Kind.DATABASE,
                submission.getId(),
                submission.getQuestionId(),
                submission.getCurrentJudgeJobId(),
                "mysql",
                scene,
                Map.of("answer.sql", submission.getSqlContent()));
    }

    private SubmissionVO enqueue(
            Kind kind,
            Long id,
            Long questionId,
            Long currentJobId,
            String pool,
            Scene scene,
            Map<String, String> codeContent) {
        JudgeJob previous = currentJobId == null ? null : requireJob(currentJobId);
        Long revision = jobs.findPersistentRevision(pool, scene.name());
        long effectiveRevision =
                revision != null ? revision : (previous != null ? previous.getRouteRevision() : 1L);

        var manifest = manifests.current(kind.name(), questionId);
        if (manifest == null) {
            throw new BusinessException(ResultCode.CONFLICT, "题目尚未发布用例清单");
        }
        if (manifest.totalCases() == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "题目尚未配置测试用例");
        }

        String snapshot =
                writeJson(
                        Map.of(
                                "questionId",
                                questionId,
                                "language",
                                pool,
                                "codeContent",
                                codeContent));

        JudgeJob job = new JudgeJob();
        job.setSubmissionType(kind.name());
        job.setSubmissionId(id);
        job.setGeneration(previous == null ? 1 : Math.incrementExact(previous.getGeneration()));
        job.setPool(pool);
        job.setScene(scene.name());
        job.setRouteRevision(effectiveRevision);
        job.setRuntimeSpec(pool + "-v1");
        job.setManifestId(manifest.id());
        job.setRequestSnapshot(snapshot);
        job.setRequestDigest(snapshotDigest(snapshot));
        job.setTotalCases(manifest.totalCases());
        jobs.insert(job);

        int updated =
                kind == Kind.ALGO
                        ? submissions.setCurrentJudgeJob(id, job.getId())
                        : dbSubmissions.setCurrentJudgeJob(id, job.getId());
        if (updated != 1) {
            throw new IllegalStateException("Cannot attach judging job to submission " + id);
        }

        return SubmissionVO.builder()
                .submissionId(id)
                .status(SubmissionStatus.PENDING.name())
                .waitReason("WAITING_FOR_WORKER")
                .progress(
                        ProgressVO.builder()
                                .totalCases(manifest.totalCases())
                                .finishedCases(0)
                                .build())
                .build();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelOrphanedQueued() {
        return jobs.cancelOrphanedQueued();
    }

    public SubmissionVO persistentStatus(Long currentJobId, SubmissionVO submission) {
        SubmissionStatus status = SubmissionStatus.from(submission.getStatus());
        if (status != null && status.isTerminal()) {
            return submission;
        }
        JudgeJob job = requireJob(currentJobId);
        submission.setProgress(
                ProgressVO.builder()
                        .totalCases(job.getTotalCases())
                        .finishedCases(job.getFinishedCases())
                        .build());
        if ("QUEUED".equals(job.getState())) {
            submission.setWaitReason("WAITING_FOR_WORKER");
        } else if ("RUNNING".equals(job.getState()) && job.getProgressPhase() != null) {
            submission.setStatus(job.getProgressPhase());
        }
        return submission;
    }

    private JudgeJob requireJob(Long id) {
        JudgeJob job = jobs.findState(id);
        if (job == null) {
            throw new IllegalStateException("Current judging job is missing: " + id);
        }
        return job;
    }

    private static String snapshotDigest(String payload) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "评测任务序列化失败");
        }
    }
}
