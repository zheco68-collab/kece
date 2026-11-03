package com.kece.question.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.database.dto.DbSubmissionListVO;
import com.kece.database.entity.DbQuestion;
import com.kece.database.entity.DbSubmission;
import com.kece.database.mapper.DbQuestionMapper;
import com.kece.database.mapper.DbSubmissionMapper;
import com.kece.exam.dto.ExamSessionQuestionVO;
import com.kece.practice.dto.PracticePracticalQuestionVO;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 数据库SQL题（DB）多态策略实现：支持 MySQL/PostgreSQL 数据定义（DDL）、查询（SELECT）与数据操纵（DML）沙箱评测。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbQuestionStrategy implements QuestionTypeStrategy {

    private final DbQuestionMapper dbQuestionMapper;
    private final DbSubmissionMapper dbSubmissionMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public String getTypeCode() {
        return "DB";
    }

    @Override
    public String getDisplayName() {
        return "数据库SQL题";
    }

    @Override
    public boolean isPractical() {
        return true;
    }

    @Override
    public QuestionMeta resolveQuestionMeta(Long questionId) {
        DbQuestion dq = dbQuestionMapper.findById(questionId);
        if (dq == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "数据库题不存在: " + questionId);
        }
        return QuestionMeta.builder()
                .id(dq.getId())
                .typeCode(getTypeCode())
                .title(dq.getTitle())
                .description(dq.getDescription())
                .createdBy(dq.getCreatedBy())
                .defaultScore(10)
                .difficulty(dq.getType())
                .build();
    }

    @Override
    public void populateExamSessionQuestion(
            Long questionId, ExamSessionQuestionVO.ExamSessionQuestionVOBuilder builder) {
        DbQuestion question = dbQuestionMapper.findById(questionId);
        if (question != null) {
            builder.content(question.getDescription()).schemaSql(question.getSchemaSql());
        }
    }

    @Override
    public Optional<PracticePracticalQuestionVO> buildPracticePracticalVO(
            Long questionId,
            Long practiceQuestionId,
            Integer score,
            Integer sortOrder,
            String userStatus,
            Integer userScore,
            Long submissionId) {
        DbQuestion dq = dbQuestionMapper.findById(questionId);

        return Optional.of(
                PracticePracticalQuestionVO.builder()
                        .practiceQuestionId(practiceQuestionId)
                        .questionId(questionId)
                        .questionType(getTypeCode())
                        .title(dq != null ? dq.getTitle() : "数据库题#" + questionId)
                        .score(score)
                        .sortOrder(sortOrder)
                        .difficulty(dq != null ? dq.getType() : "SELECT")
                        .timeLimitMs(3000)
                        .memoryLimitKb(262144)
                        .languages(List.of("sql"))
                        .userStatus(userStatus != null ? userStatus : "UNATTEMPTED")
                        .userScore(userScore != null ? userScore : 0)
                        .submissionId(submissionId)
                        .build());
    }

    @Override
    public JudgeResultDTO verifyAndExtractJudgeInfo(
            Long submissionId, Long expectedUserId, Long expectedQuestionId) {
        DbSubmission ds = dbSubmissionMapper.findById(submissionId);
        if (ds == null
                || !expectedUserId.equals(ds.getUserId())
                || !expectedQuestionId.equals(ds.getQuestionId())) {
            log.warn("课练数据库题提交记录归属不符: subId={}, expectedUserId={}", submissionId, expectedUserId);
            return null;
        }
        return extractJudgeInfo(submissionId);
    }

    @Override
    public JudgeResultDTO extractJudgeInfo(Long submissionId) {
        if (submissionId == null) return null;

        // 1. 优先读取 Redis 缓存
        try {
            String redisKey = getRedisStatusKey(submissionId);
            String cached = redis.opsForValue().get(redisKey);
            if (cached != null) {
                JsonNode node = objectMapper.readTree(cached);
                String status = node.has("status") ? node.get("status").asText() : null;
                Integer score = null;
                if (node.has("result") && !node.get("result").isNull()) {
                    JsonNode resultNode = node.get("result");
                    if (resultNode.has("score") && !resultNode.get("score").isNull()) {
                        score = resultNode.get("score").asInt();
                    } else if (resultNode.has("testCasesReport")
                            && resultNode.get("testCasesReport").isArray()) {
                        JsonNode report = resultNode.get("testCasesReport");
                        if (report.size() > 0) {
                            int passed = 0;
                            for (JsonNode tc : report) {
                                if (tc.has("status")
                                        && "ACCEPTED".equals(tc.get("status").asText())) {
                                    passed++;
                                }
                            }
                            score = passed * 100 / report.size();
                        }
                    }
                }
                if (status != null && !isJudging(status)) {
                    return new JudgeResultDTO(status, score);
                }
            }
        } catch (Exception e) {
            log.debug(
                    "Read redis status exception submissionId={}: {}",
                    submissionId,
                    e.getMessage());
        }

        // 2. 从 MySQL 读取
        try {
            DbSubmission ds = dbSubmissionMapper.findById(submissionId);
            if (ds != null) {
                Integer score = ds.getScore();
                if (score == null && ds.getResultJson() != null && !ds.getResultJson().isBlank()) {
                    try {
                        JsonNode node = objectMapper.readTree(ds.getResultJson());
                        if (node.has("score") && !node.get("score").isNull()) {
                            score = node.get("score").asInt();
                        }
                    } catch (Exception ignored) {
                    }
                }
                return new JudgeResultDTO(ds.getStatus(), score);
            }
        } catch (Exception e) {
            log.debug(
                    "Read DB submission exception submissionId={}: {}",
                    submissionId,
                    e.getMessage());
        }

        return null;
    }

    @Override
    public Long findLatestUserSubmissionId(Long userId, Long questionId) {
        if (userId == null || questionId == null) return null;
        try {
            List<DbSubmissionListVO> list =
                    dbSubmissionMapper.findPage(userId, questionId, null, null, 0, 1);
            if (list != null && !list.isEmpty()) {
                return list.get(0).getSubmissionId();
            }
        } catch (Exception e) {
            log.debug("findLatestUserSubmissionId error: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public String getRedisStatusKey(Long submissionId) {
        return "dbsub:status:" + submissionId;
    }

    @Override
    public String getRedisProgressKey(Long submissionId) {
        return "db_judge:progress:" + submissionId;
    }

    @Override
    public Optional<ContestSubmissionDTO> findLatestContestSubmission(
            Long examId, Long userId, Long questionId) {
        DbSubmission s = dbSubmissionMapper.findLatestByContest(examId, userId, questionId);
        if (s != null) {
            return Optional.of(new ContestSubmissionDTO(s.getId(), s.getStatus(), s.getScore()));
        }
        return Optional.empty();
    }

    private boolean isJudging(String status) {
        return "PENDING".equals(status) || "COMPILING".equals(status) || "JUDGING".equals(status);
    }
}
