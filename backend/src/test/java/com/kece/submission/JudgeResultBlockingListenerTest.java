package com.kece.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kece.database.service.DbSubmissionService;
import com.kece.submission.listener.JudgeResultBlockingListener;
import com.kece.submission.service.SubmissionService;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

class JudgeResultBlockingListenerTest {

    private static final String ALGO = JudgeResultBlockingListener.ALGO_RESULTS_KEY;
    private static final String SQL = JudgeResultBlockingListener.SQL_RESULTS_KEY;
    private static final String PAYLOAD = "{\"status\":\"ACCEPTED\"}";

    private StringRedisTemplate redis;
    private ZSetOperations<String, String> pending;
    private SubmissionService submissionService;
    private DbSubmissionService dbSubmissionService;
    private JudgeResultBlockingListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        pending = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(pending);
        submissionService = mock(SubmissionService.class);
        dbSubmissionService = mock(DbSubmissionService.class);
        listener = new JudgeResultBlockingListener(redis, submissionService, dbSubmissionService);
    }

    @AfterEach
    void tearDown() {
        listener.stop();
    }

    @Test
    void acknowledgesOnlyAfterPersistenceAndAcceptsLegacyMessages() {
        claim(ALGO).thenAnswer(invocation -> invocation.getArgument(2) + "|1|1001");
        assertThat(
                        listener.processNext(
                                ALGO,
                                (id, payload) -> {
                                    assertThat(id).isEqualTo(1001L);
                                    assertThat(payload).isNull();
                                    verifyNoInteractions(pending);
                                }))
                .isTrue();
        verify(pending).remove(eq(ALGO + ":pending"), anyString());
    }

    @Test
    void failedPersistenceLeavesTheResultForRedelivery() {
        claim(ALGO).thenAnswer(invocation -> invocation.getArgument(2) + "|1|1001\n" + PAYLOAD);
        var attempts = new AtomicInteger();
        BiConsumer<Long, String> consumer =
                (id, body) -> {
                    assertThat(id).isEqualTo(1001L);
                    assertThat(body).isEqualTo(PAYLOAD);
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("database unavailable");
                    }
                };
        assertThatThrownBy(() -> listener.processNext(ALGO, consumer))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(pending);

        assertThat(listener.processNext(ALGO, consumer)).isTrue();
        assertThat(attempts.get()).isEqualTo(2);
        verify(pending).remove(eq(ALGO + ":pending"), anyString());
    }

    @Test
    void emptyQueueDoesNotPersist() {
        assertThat(listener.processNext(ALGO, submissionService::persistResult)).isFalse();
        verifyNoInteractions(submissionService, dbSubmissionService, pending);
    }

    @Test
    void independentWorkerLoopsDeliverBothResultTypes() {
        var algoClaims = new AtomicInteger();
        var sqlClaims = new AtomicInteger();
        claim(ALGO)
                .thenAnswer(
                        invocation ->
                                algoClaims.incrementAndGet() == 2
                                        ? invocation.getArgument(2) + "|1|1001"
                                        : null);
        claim(SQL)
                .thenAnswer(
                        invocation ->
                                sqlClaims.incrementAndGet() == 1
                                        ? invocation.getArgument(2) + "|1|2001\n" + PAYLOAD
                                        : null);
        listener.start();
        assertThat(listener.isRunning()).isTrue();
        verify(submissionService, timeout(3000)).persistResult(1001L, null);
        verify(dbSubmissionService, timeout(3000)).persistResult(2001L, PAYLOAD);
        listener.stop();
        assertThat(listener.isRunning()).isFalse();
    }

    @Test
    void malformedMessageIsAcknowledgedAndNotPersisted() {
        claim(ALGO).thenAnswer(invocation -> invocation.getArgument(2) + "|1|not-an-id");
        assertThat(listener.processNext(ALGO, submissionService::persistResult)).isTrue();
        verify(submissionService, never()).persistResult(any(), any());
        verify(pending).remove(eq(ALGO + ":pending"), anyString());
    }

    private OngoingStubbing<String> claim(String key) {
        return when(
                redis.execute(
                        org.mockito.ArgumentMatchers.<RedisScript<String>>any(),
                        eq(List.of(key, key + ":pending", key + ":failed")),
                        anyString(),
                        anyString(),
                        anyString()));
    }
}
