package com.kece.submission.listener;

import com.kece.database.service.DbSubmissionService;
import com.kece.submission.service.SubmissionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 评测结果阻塞监听器：
 * 使用双线程池守护线程阻塞轮询 Redis 结果队列，实现毫秒级快速落库，消除固定定时轮询带来的时延。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JudgeResultBlockingListener {

    public static final String ALGO_RESULTS_KEY = "judge:results";
    public static final String SQL_RESULTS_KEY = "judge:sql:results";
    public static final Duration POP_TIMEOUT = Duration.ofSeconds(5);

    private final StringRedisTemplate redis;
    private final SubmissionService submissionService;
    private final DbSubmissionService dbSubmissionService;

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

        algoExecutor.submit(() -> listenLoop(ALGO_RESULTS_KEY, submissionService::persistResult));
        sqlExecutor.submit(() -> listenLoop(SQL_RESULTS_KEY, dbSubmissionService::persistResult));

        log.info(
                "JudgeResultBlockingListener 已启动，分别阻塞监听 {} 与 {}",
                ALGO_RESULTS_KEY,
                SQL_RESULTS_KEY);
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

    void listenLoop(String queueKey, Consumer<Long> resultConsumer) {
        log.info("开始阻塞监听队列: {}", queueKey);
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                processNext(queueKey, resultConsumer);
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

    public boolean processNext(String queueKey, Consumer<Long> resultConsumer) {
        String submissionIdStr = redis.opsForList().leftPop(queueKey, POP_TIMEOUT);
        if (submissionIdStr == null || submissionIdStr.isBlank()) {
            return false;
        }
        try {
            Long submissionId = Long.valueOf(submissionIdStr.trim());
            resultConsumer.accept(submissionId);
        } catch (Exception e) {
            log.warn(
                    "处理评测结果失败 queueKey={}, submissionId={}: {}",
                    queueKey,
                    submissionIdStr,
                    e.getMessage());
        }
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
