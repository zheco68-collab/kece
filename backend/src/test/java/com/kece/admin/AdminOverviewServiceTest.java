package com.kece.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kece.admin.dto.AdminSubmissionTrend.Bucket;
import com.kece.admin.mapper.AdminOverviewMapper;
import com.kece.admin.mapper.AdminOverviewMapper.SubmissionDay;
import com.kece.admin.service.AdminOverviewService;
import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.common.security.AuthUser;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AdminOverviewServiceTest {
    private final AdminOverviewMapper mapper = mock(AdminOverviewMapper.class);
    private final AdminOverviewService service = new AdminOverviewService(mapper);

    @AfterEach
    void clearAuth() {
        AuthContextHolder.clear();
    }

    @Test
    void weeklyTrendUsesTheTeacherScopeAndFourCompleteBeijingWeeks() {
        AuthContextHolder.set(new AuthUser(17L, "teacher", Set.of("TEACHER")));
        when(mapper.countSubmissionDays(eq(17L), any(), any()))
                .thenAnswer(
                        invocation -> {
                            LocalDate from =
                                    invocation.getArgument(1, LocalDateTime.class).toLocalDate();
                            LocalDate until =
                                    invocation.getArgument(2, LocalDateTime.class).toLocalDate();
                            assertThat(until.minusDays(28)).isEqualTo(from);
                            return List.of(
                                    new SubmissionDay(from, 1, 0),
                                    new SubmissionDay(from.plusDays(6), 2, 1),
                                    new SubmissionDay(from.plusDays(7), 0, 5),
                                    new SubmissionDay(until.minusDays(1), 4, 3));
                        });

        var trend = service.submissionTrend(28);
        LocalDate today = trend.asOf().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        assertThat(trend.buckets())
                .containsExactly(
                        new Bucket(today.minusDays(28), today.minusDays(22), 3, 1),
                        new Bucket(today.minusDays(21), today.minusDays(15), 0, 5),
                        new Bucket(today.minusDays(14), today.minusDays(8), 0, 0),
                        new Bucket(today.minusDays(7), today.minusDays(1), 4, 3));
    }

    @Test
    void unauthorizedCallNeverExecutesTheUnscopedQueries() {
        AuthContextHolder.set(new AuthUser(17L, "student", Set.of("USER")));
        assertThatThrownBy(service::activities)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getResultCode()).isEqualTo(ResultCode.FORBIDDEN));
        AuthContextHolder.clear();
        assertThatThrownBy(() -> service.submissionTrend(7))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getResultCode())
                                        .isEqualTo(ResultCode.UNAUTHORIZED));
        verifyNoInteractions(mapper);
    }
}
