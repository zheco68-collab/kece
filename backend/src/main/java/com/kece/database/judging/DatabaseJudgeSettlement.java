package com.kece.database.judging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.database.entity.DbSubmission;
import com.kece.database.mapper.DbSubmissionMapper;
import com.kece.judging.JudgeDispatch.Kind;
import com.kece.submission.dto.SubmissionResultVO;
import com.kece.submission.dto.SubmissionVO;
import com.kece.submission.enums.SubmissionStatus;
import com.kece.user.service.UserDailyAcService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists the judgment using the submission's business rules. */
@Service
@RequiredArgsConstructor
public class DatabaseJudgeSettlement {

    private final DbSubmissionMapper submissionMapper;
    private final ObjectMapper objectMapper;
    private final UserDailyAcService userDailyAcService;

    @Transactional(propagation = Propagation.MANDATORY)
    public void applyPersistent(DbSubmission sub, SubmissionVO vo) {
        Long submissionId = sub.getId();
        SubmissionResultVO result = vo.getResult();

        submissionMapper.updateResult(
                submissionId,
                vo.getStatus(),
                result == null ? null : result.getScore(),
                result == null ? null : result.getTimeUsedMs(),
                result == null ? null : writeJson(result));

        LocalDate date =
                sub.getCreatedAt() != null ? sub.getCreatedAt().toLocalDate() : LocalDate.now();
        userDailyAcService.syncJudgeContribution(
                Kind.DATABASE,
                submissionId,
                sub.getUserId(),
                date,
                SubmissionStatus.ACCEPTED.name().equals(vo.getStatus()));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "JSON 序列化失败");
        }
    }
}
