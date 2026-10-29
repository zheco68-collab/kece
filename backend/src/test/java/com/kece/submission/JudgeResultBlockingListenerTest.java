package com.kece.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kece.database.service.DbSubmissionService;
import com.kece.submission.listener.JudgeResultBlockingListener;
import com.kece.submission.service.SubmissionService;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class JudgeResultBlockingListenerTest {

    private StringRedisTemplate redis;
    private ListOperations<String, String> listOps;
    private SubmissionService submissionService;
    private DbSubmissionService dbSubmissionService;
    private JudgeResultBlockingListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(listOps);

        submissionService = mock(SubmissionService.class);
        dbSubmissionService = mock(DbSubmissionService.class);

        listener = new JudgeResultBlockingListener(redis, submissionService, dbSubmissionService);
    }

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.stop();
        }
    }

    @Test
    void testAlgoResultPopAndPersist() {
        when(listOps.leftPop(eq(JudgeResultBlockingListener.ALGO_RESULTS_KEY), any(Duration.class)))
                .thenReturn("1001");

        boolean processed =
                listener.processNext(
                        JudgeResultBlockingListener.ALGO_RESULTS_KEY,
                        submissionService::persistResult);

        assertThat(processed).isTrue();
        verify(submissionService, times(1)).persistResult(1001L);
        verifyNoInteractions(dbSubmissionService);
    }

    @Test
    void testSqlResultPopAndPersist() {
        when(listOps.leftPop(eq(JudgeResultBlockingListener.SQL_RESULTS_KEY), any(Duration.class)))
                .thenReturn("2001");

        boolean processed =
                listener.processNext(
                        JudgeResultBlockingListener.SQL_RESULTS_KEY,
                        dbSubmissionService::persistResult);

        assertThat(processed).isTrue();
        verify(dbSubmissionService, times(1)).persistResult(2001L);
        verifyNoInteractions(submissionService);
    }

    @Test
    void testTimeoutReturnsFalseAndDoesNotPersist() {
        when(listOps.leftPop(eq(JudgeResultBlockingListener.ALGO_RESULTS_KEY), any(Duration.class)))
                .thenReturn(null);

        boolean processed =
                listener.processNext(
                        JudgeResultBlockingListener.ALGO_RESULTS_KEY,
                        submissionService::persistResult);

        assertThat(processed).isFalse();
        verify(submissionService, never()).persistResult(any());
        verifyNoInteractions(dbSubmissionService);
    }

    @Test
    void testStartAndStopLifecycleWithTimeoutAndSubsequentPop() {
        AtomicInteger algoCount = new AtomicInteger(0);
        when(listOps.leftPop(eq(JudgeResultBlockingListener.ALGO_RESULTS_KEY), any(Duration.class)))
                .thenAnswer(
                        invocation -> {
                            int count = algoCount.getAndIncrement();
                            if (count == 0) {
                                // 第一次超时，返回 null
                                return null;
                            } else if (count == 1) {
                                // 第二次正常收到结果
                                return "1002";
                            }
                            // 后续模拟等待，避免热轮询
                            Thread.sleep(50);
                            return null;
                        });

        when(listOps.leftPop(eq(JudgeResultBlockingListener.SQL_RESULTS_KEY), any(Duration.class)))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(50);
                            return null;
                        });

        listener.start();
        assertThat(listener.isRunning()).isTrue();

        // 验证超时循环后成功触发 persistResult
        verify(submissionService, timeout(3000).times(1)).persistResult(1002L);

        listener.stop();
        assertThat(listener.isRunning()).isFalse();
    }

    @Test
    void testSqlResultInThreadLoop() {
        AtomicInteger sqlCount = new AtomicInteger(0);
        when(listOps.leftPop(eq(JudgeResultBlockingListener.SQL_RESULTS_KEY), any(Duration.class)))
                .thenAnswer(
                        invocation -> {
                            int count = sqlCount.getAndIncrement();
                            if (count == 0) {
                                return null; // 模拟超时
                            } else if (count == 1) {
                                return "2002";
                            }
                            Thread.sleep(50);
                            return null;
                        });

        when(listOps.leftPop(eq(JudgeResultBlockingListener.ALGO_RESULTS_KEY), any(Duration.class)))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(50);
                            return null;
                        });

        listener.start();
        verify(dbSubmissionService, timeout(3000).times(1)).persistResult(2002L);

        listener.stop();
        assertThat(listener.isRunning()).isFalse();
    }

    @Test
    void testMalformedSubmissionIdIgnored() {
        when(listOps.leftPop(eq(JudgeResultBlockingListener.ALGO_RESULTS_KEY), any(Duration.class)))
                .thenReturn("not-a-number");

        boolean processed =
                listener.processNext(
                        JudgeResultBlockingListener.ALGO_RESULTS_KEY,
                        submissionService::persistResult);

        assertThat(processed).isTrue();
        verify(submissionService, never()).persistResult(any());
    }
}
