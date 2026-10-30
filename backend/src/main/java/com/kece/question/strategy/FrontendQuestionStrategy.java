package com.kece.question.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.exam.dto.ExamSessionQuestionVO;
import com.kece.practice.dto.PracticePracticalQuestionVO;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 前端交互实操题（FRONTEND）多态策略骨架：为接入 HTML/CSS/JS/Vue/React 组件化前端实操、DOM断言及无头沙箱评测预留的标准多态策略实现。
 * 后续引入独立前端题库表（如 frontend_question）时，只需在此策略中补充 Mapper 关联，业务层无感知平滑扩展。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FrontendQuestionStrategy implements QuestionTypeStrategy {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public String getTypeCode() {
        return "FRONTEND";
    }

    @Override
    public String getDisplayName() {
        return "前端交互实操题";
    }

    @Override
    public boolean isPractical() {
        return true;
    }

    @Override
    public QuestionMeta resolveQuestionMeta(Long questionId) {
        // 预留实体表扩展点：若未接入专用实体表则提供降级或提示
        return QuestionMeta.builder()
                .id(questionId)
                .typeCode(getTypeCode())
                .title("前端实操题#" + questionId)
                .description("前端交互实操与组件构建题目")
                .defaultScore(10)
                .difficulty("MEDIUM")
                .build();
    }

    @Override
    public void populateExamSessionQuestion(
            Long questionId, ExamSessionQuestionVO.ExamSessionQuestionVOBuilder builder) {
        builder.content("前端交互实操题目详情（包含 HTML/CSS/JS 模板及测试断言）");
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
        return Optional.of(
                PracticePracticalQuestionVO.builder()
                        .practiceQuestionId(practiceQuestionId)
                        .questionId(questionId)
                        .questionType(getTypeCode())
                        .title("前端实操题#" + questionId)
                        .score(score)
                        .sortOrder(sortOrder)
                        .difficulty("MEDIUM")
                        .timeLimitMs(5000)
                        .memoryLimitKb(524288)
                        .languages(List.of("html", "css", "javascript"))
                        .userStatus(userStatus != null ? userStatus : "UNATTEMPTED")
                        .userScore(userScore != null ? userScore : 0)
                        .submissionId(submissionId)
                        .build());
    }

    @Override
    public JudgeResultDTO verifyAndExtractJudgeInfo(
            Long submissionId, Long expectedUserId, Long expectedQuestionId) {
        return extractJudgeInfo(submissionId);
    }

    @Override
    public JudgeResultDTO extractJudgeInfo(Long submissionId) {
        if (submissionId == null) return null;
        try {
            String redisKey = getRedisStatusKey(submissionId);
            String cached = redis.opsForValue().get(redisKey);
            if (cached != null) {
                JsonNode node = objectMapper.readTree(cached);
                String status = node.has("status") ? node.get("status").asText() : null;
                Integer score = node.has("score") ? node.get("score").asInt() : null;
                return new JudgeResultDTO(status, score);
            }
        } catch (Exception e) {
            log.debug("Read frontend redis status exception submissionId={}: {}", submissionId, e.getMessage());
        }
        return null;
    }

    @Override
    public Long findLatestUserSubmissionId(Long userId, Long questionId) {
        return null;
    }

    @Override
    public String getRedisStatusKey(Long submissionId) {
        return "fesub:status:" + submissionId;
    }

    @Override
    public String getRedisProgressKey(Long submissionId) {
        return "fe_judge:progress:" + submissionId;
    }

    @Override
    public Optional<ContestSubmissionDTO> findLatestContestSubmission(
            Long examId, Long userId, Long questionId) {
        return Optional.empty();
    }
}
