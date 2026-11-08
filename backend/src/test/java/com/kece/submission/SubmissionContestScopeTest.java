package com.kece.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import com.kece.question.mapper.QuestionMapper;
import com.kece.submission.dto.SubmitRequest;
import com.kece.submission.mapper.SubmissionMapper;
import com.kece.submission.service.SubmissionService;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SubmissionContestScopeTest {

    @Test
    void ordinarySubmitRejectsClientSuppliedContestId() {
        SubmissionMapper submissions = mock(SubmissionMapper.class);
        QuestionMapper questions = mock(QuestionMapper.class);
        SubmissionService service = new SubmissionService(submissions, questions, null, null, null);
        // Use the same JSON shape as a public POST /submissions request.
        SubmitRequest request =
                new ObjectMapper()
                        .convertValue(
                                Map.of(
                                        "questionId",
                                        4,
                                        "contestId",
                                        777,
                                        "language",
                                        "cpp",
                                        "codeContent",
                                        Map.of("main.cpp", "int main() {}")),
                                SubmitRequest.class);

        AuthContextHolder.set(new AuthUser(1L, "student", Set.of("USER")));
        try {
            assertThatThrownBy(() -> service.submit(request))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            e -> assertThat(e.getResultCode()).isEqualTo(ResultCode.PARAM_ERROR));
            verifyNoInteractions(submissions, questions);
        } finally {
            AuthContextHolder.clear();
        }
    }
}
