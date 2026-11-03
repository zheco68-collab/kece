package com.kece.submission.listener;

import com.kece.database.service.DbSubmissionService;
import com.kece.practice.service.LessonPracticeService;
import com.kece.submission.service.SubmissionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 认领结果并在落库后确认；过期的认领会重新投递。 */
@Slf4j
@Component
public class JudgeResultBlockingListener {

    public static final String ALGO_RESULTS_KEY = "judge:results";
    public static final String SQL_RESULTS_KEY = "judge:sql:results";
    private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(2);
    private static final long IDLE_DELAY_MS = 250;
    private static final int MAX_CLAIMS = 30;
    private static final DefaultRedisScript<String> CLAIM =
            new DefaultRedisScript<>(
                    """
            local clock = redis.call('TIME')
            local now = clock[1] * 1000 + math.floor(clock[2] / 1000)
            local stale = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', now, 'LIMIT', 0, 1)[1]
            local payload
            local attempt = 1
            if stale then
                local separator = string.find(stale, '|', string.len(ARGV[1]) + 2, true)
                attempt = tonumber(string.sub(stale, string.len(ARGV[1]) + 2, separator - 1)) + 1
                payload = string.sub(stale, separator + 1)
                if attempt > tonumber(ARGV[3]) then
                    redis.call('RPUSH', KEYS[3], payload)
                    redis.call('ZREM', KEYS[2], stale)
                    return ''
                end
            else
                payload = redis.call('LINDEX', KEYS[1], 0)
            end
            if not payload then return nil end
            local claim = ARGV[1] .. '|' .. attempt .. '|' .. payload
            redis.call('ZADD', KEYS[2], now + tonumber(ARGV[2]), claim)
            if stale then
                redis.call('ZREM', KEYS[2], stale)
            else
                redis.call('LPOP', KEYS[1])
            end
            return claim
            """,
                    String.class);

    private final StringRedisTemplate redis;
    private final SubmissionService submissionService;
    private final DbSubmissionService dbSubmissionService;
    private final ObjectProvider<LessonPracticeService> practiceServiceProvider;

    public JudgeResultBlockingListener(
            StringRedisTemplate redis,
            SubmissionService submissionService,
            DbSubmissionService dbSubmissionService) {
        this(redis, submissionService, dbSubmissionService, null);
    }

    @Autowired
    public JudgeResultBlockingListener(
            StringRedisTemplate redis,
            SubmissionService submissionService,
            DbSubmissionService dbSubmissionService,
            ObjectProvider<LessonPracticeService> practiceServiceProvider) {
        this.redis = redis;
        this.submissionService = submissionService;
        this.dbSubmissionService = dbSubmissionService;
        this.practiceServiceProvider = practiceServiceProvider;
    }

    private volatile boolean running = false;
    private ExecutorService algoExecutor;
    private ExecutorService sqlExecutor;

    @PostConstruct
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;

        algoExecutor =
                Executors.newSingleThreadExecutor(
                        createDaemonThreadFactory("judge-algo-result-listener"));
        sqlExecutor =
                Executors.newSingleThreadExecutor(
                        createDaemonThreadFactory("judge-sql-result-listener"));

        algoExecutor.submit(() -> listenLoop(ALGO_RESULTS_KEY, this::handleAlgoResult));
        sqlExecutor.submit(() -> listenLoop(SQL_RESULTS_KEY, this::handleSqlResult));

        log.info(
                "JudgeResultBlockingListener 已启动，分别阻塞监听 {} 与 {}",
                ALGO_RESULTS_KEY,
                SQL_RESULTS_KEY);
    }

    private void handleAlgoResult(Long submissionId, String payload) {
        submissionService.persistResult(submissionId, payload);
        updatePractice("ALGO", submissionId);
    }

    private void handleSqlResult(Long submissionId, String payload) {
        dbSubmissionService.persistResult(submissionId, payload);
        updatePractice("DB", submissionId);
    }

    private void updatePractice(String type, Long submissionId) {
        if (practiceServiceProvider != null) {
            LessonPracticeService practiceService = practiceServiceProvider.getIfAvailable();
            if (practiceService != null) {
                practiceService.onPracticalJudgeCompleted(type, submissionId);
            }
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        log.info("正在停止 JudgeResultBlockingListener...");
        shutdownExecutor(algoExecutor, "algoExecutor");
        shutdownExecutor(sqlExecutor, "sqlExecutor");
        log.info("JudgeResultBlockingListener 已停止");
    }

    public boolean isRunning() {
        return running;
    }

    void listenLoop(String queueKey, BiConsumer<Long, String> resultConsumer) {
        log.info("开始阻塞监听队列: {}", queueKey);
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                if (!processNext(queueKey, resultConsumer)) {
                    TimeUnit.MILLISECONDS.sleep(IDLE_DELAY_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (!running || Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.warn("监听队列异常 queueKey={}: {}", queueKey, e.getMessage());
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("停止监听队列: {}", queueKey);
    }

    public boolean processNext(String queueKey, BiConsumer<Long, String> resultConsumer) {
        String pendingKey = queueKey + ":pending";
        String token = UUID.randomUUID().toString();
        String claim =
                redis.execute(
                        CLAIM,
                        List.of(queueKey, pendingKey, queueKey + ":failed"),
                        token,
                        Long.toString(CLAIM_TIMEOUT.toMillis()),
                        Integer.toString(MAX_CLAIMS));
        if (claim == null) {
            return false;
        }

        if (claim.isEmpty()) {
            log.error("评测结果多次处理失败，已转入 {}", queueKey + ":failed");
            return true;
        }

        String message = claim.substring(claim.indexOf('|', token.length() + 1) + 1);
        // Legacy workers queued only the numeric ID. New messages also carry a durable result body.
        int separator = message.indexOf('\n');
        String id = separator < 0 ? message : message.substring(0, separator);
        String payload = separator < 0 ? null : message.substring(separator + 1);
        Long submissionId;
        try {
            submissionId = Long.valueOf(id.trim());
        } catch (NumberFormatException e) {
            log.warn("忽略无法识别的评测结果 queueKey={}", queueKey);
            redis.opsForZSet().remove(pendingKey, claim);
            return true;
        }

        resultConsumer.accept(submissionId, payload);
        redis.opsForZSet().remove(pendingKey, claim);
        return true;
    }

    private ThreadFactory createDaemonThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    log.warn("线程池 {} 未能正常终止", name);
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
