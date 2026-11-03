package com.kece.question.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.exam.dto.ExamSessionQuestionVO;
import com.kece.practice.dto.PracticePracticalQuestionVO;
import com.kece.question.entity.Question;
import com.kece.question.mapper.QuestionMapper;
import com.kece.submission.dto.SubmissionListVO;
import com.kece.submission.entity.Submission;
import com.kece.submission.mapper.SubmissionMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 算法编程题（ALGO）多态策略实现：支持 C++、Java、Python、ArkTS 等沙箱多用例评测。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlgoQuestionStrategy implements QuestionTypeStrategy {

    private final QuestionMapper questionMapper;
    private final SubmissionMapper submissionMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public String getTypeCode() {
        return "ALGO";
    }

    @Override
    public String getDisplayName() {
        return "算法编程题";
    }

    @Override
    public boolean isPractical() {
        return true;
    }

    @Override
    public QuestionMeta resolveQuestionMeta(Long questionId) {
        Question q = questionMapper.findById(questionId);
        if (q == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "编程题不存在: " + questionId);
        }
        return QuestionMeta.builder()
                .id(q.getId())
                .typeCode(getTypeCode())
                .title(q.getTitle())
                .description(q.getDescription())
                .createdBy(q.getCreatedBy())
                .defaultScore(10)
                .difficulty(q.getDifficulty())
                .build();
    }

    @Override
    public void populateExamSessionQuestion(
            Long questionId, ExamSessionQuestionVO.ExamSessionQuestionVOBuilder builder) {
        Question question = questionMapper.findById(questionId);
        if (question != null) {
            builder.content(question.getDescription())
                    .timeLimitMs(question.getTimeLimit())
                    .memoryLimitMb(question.getMemoryLimit());
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
        Question aq = questionMapper.findById(questionId);
        List<String> langs =
                aq != null && aq.getLanguages() != null
                        ? Arrays.stream(aq.getLanguages().split(",")).map(String::trim).toList()
                        : List.of("cpp", "java", "python");

        return Optional.of(
                PracticePracticalQuestionVO.builder()
                        .practiceQuestionId(practiceQuestionId)
                        .questionId(questionId)
                        .questionType(getTypeCode())
                        .title(aq != null ? aq.getTitle() : "编程题#" + questionId)
                        .score(score)
                        .sortOrder(sortOrder)
                        .difficulty(aq != null ? aq.getDifficulty() : "MEDIUM")
                        .timeLimitMs(
                                aq != null && aq.getTimeLimit() != null ? aq.getTimeLimit() : 1000)
                        .memoryLimitKb(
                                aq != null && aq.getMemoryLimit() != null
                                        ? aq.getMemoryLimit() * 1024
                                        : 262144)
                        .languages(langs)
                        .userStatus(userStatus != null ? userStatus : "UNATTEMPTED")
                        .userScore(userScore != null ? userScore : 0)
                        .submissionId(submissionId)
                        .build());
    }

    @Override
    public JudgeResultDTO verifyAndExtractJudgeInfo(
            Long submissionId, Long expectedUserId, Long expectedQuestionId) {
        Submission s = submissionMapper.findById(submissionId);
        if (s == null
                || !expectedUserId.equals(s.getUserId())
                || !expectedQuestionId.equals(s.getQuestionId())) {
            log.warn("课练编程题提交记录归属不符: subId={}, expectedUserId={}", submissionId, expectedUserId);
            return null;
        }
        return extractJudgeInfo(submissionId);
    }

    @Override
    public JudgeResultDTO extractJudgeInfo(Long submissionId) {
        if (submissionId == null) return null;

        // 1. 优先从 Redis 实时读取缓存
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
            Submission s = submissionMapper.findById(submissionId);
            if (s != null) {
                Integer score = s.getScore();
                if (score == null && s.getResultJson() != null && !s.getResultJson().isBlank()) {
                    try {
                        JsonNode node = objectMapper.readTree(s.getResultJson());
                        if (node.has("score") && !node.get("score").isNull()) {
                            score = node.get("score").asInt();
                        }
                    } catch (Exception ignored) {
                    }
                }
                return new JudgeResultDTO(s.getStatus(), score);
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
            List<SubmissionListVO> list =
                    submissionMapper.findPage(userId, questionId, null, null, 0, 1);
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
        return "sub:status:" + submissionId;
    }

    @Override
    public String getRedisProgressKey(Long submissionId) {
        return "judge:progress:" + submissionId;
    }

    @Override
    public Optional<ContestSubmissionDTO> findLatestContestSubmission(
            Long examId, Long userId, Long questionId) {
        Submission s = submissionMapper.findLatestByContest(examId, userId, questionId);
        if (s != null) {
            return Optional.of(new ContestSubmissionDTO(s.getId(), s.getStatus(), s.getScore()));
        }
        return Optional.empty();
    }

    private boolean isJudging(String status) {
        return "PENDING".equals(status) || "COMPILING".equals(status) || "JUDGING".equals(status);
    }
}
