package com.kece.question.strategy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.exam.dto.ExamSessionQuestionVO;
import com.kece.objective.entity.ObjectiveQuestion;
import com.kece.objective.mapper.ObjectiveQuestionMapper;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 客观题（OBJECTIVE）多态策略实现：支持单选、多选、填空、判断等题型的题面装配与元数据解析。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ObjectiveQuestionStrategy implements QuestionTypeStrategy {

    private final ObjectiveQuestionMapper objectiveQuestionMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getTypeCode() {
        return "OBJECTIVE";
    }

    @Override
    public String getDisplayName() {
        return "客观题（选择/填空/判断）";
    }

    @Override
    public boolean isPractical() {
        return false;
    }

    @Override
    public QuestionMeta resolveQuestionMeta(Long questionId) {
        ObjectiveQuestion q = objectiveQuestionMapper.findById(questionId);
        if (q == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "客观题不存在: " + questionId);
        }
        String title = q.getContent();
        if (title != null && title.length() > 30) {
            title = title.substring(0, 30) + "…";
        }
        return QuestionMeta.builder()
                .id(q.getId())
                .typeCode(getTypeCode())
                .title(title)
                .description(q.getContent())
                .createdBy(q.getCreatedBy())
                .defaultScore(5)
                .difficulty(q.getType())
                .build();
    }

    @Override
    public void populateExamSessionQuestion(
            Long questionId, ExamSessionQuestionVO.ExamSessionQuestionVOBuilder builder) {
        ObjectiveQuestion question = objectiveQuestionMapper.findById(questionId);
        if (question != null) {
            builder.content(question.getContent())
                    .objectiveType(question.getType())
                    .options(parseOptions(question.getOptions()));
        }
    }

    @Override
    public JudgeResultDTO verifyAndExtractJudgeInfo(
            Long submissionId, Long expectedUserId, Long expectedQuestionId) {
        return null;
    }

    @Override
    public JudgeResultDTO extractJudgeInfo(Long submissionId) {
        return null;
    }

    @Override
    public Long findLatestUserSubmissionId(Long userId, Long questionId) {
        return null;
    }

    @Override
    public String getRedisStatusKey(Long submissionId) {
        return "obj:status:" + submissionId;
    }

    @Override
    public String getRedisProgressKey(Long submissionId) {
        return "";
    }

    @Override
    public Optional<ContestSubmissionDTO> findLatestContestSubmission(
            Long examId, Long userId, Long questionId) {
        return Optional.empty();
    }

    private List<String> parseOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(optionsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
