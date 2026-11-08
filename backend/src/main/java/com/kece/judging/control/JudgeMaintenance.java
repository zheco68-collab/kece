package com.kece.judging.control;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

@Slf4j
@Component
@RequiredArgsConstructor
public class JudgeMaintenance {
    private final JudgeScheduler scheduler;
    private final JudgeNodes nodes;
    private final JudgeSettlementEvents events;
    private final com.kece.judging.persistence.JudgeJobMapper jobs;
    private final Set<Long> activeClaims = ConcurrentHashMap.newKeySet();
    private final Map<Long, Waiting> waiting = new ConcurrentHashMap<>();
    private final Semaphore claimLimit = new Semaphore(128);

    private record Waiting(
            JudgeNodes.Access access, int slots, DeferredResult<ResponseEntity<?>> response) {}

    ResponseEntity<JudgeScheduler.ClaimBatch> claimImmediately(
            JudgeNodes.Access access, int slots) {
        acquire(access.nodeId());
        try {
            return response(scheduler.claim(access, slots));
        } finally {
            release(access.nodeId());
        }
    }

    DeferredResult<ResponseEntity<?>> waitForClaim(
            JudgeNodes.Access access, int slots, int waitMs) {
        acquire(access.nodeId());
        var response = new DeferredResult<ResponseEntity<?>>((long) waitMs, response(empty()));
        var entry = new Waiting(access, slots, response);
        try {
            var first = scheduler.claim(access, slots);
            if (!first.jobs().isEmpty()) {
                response.setResult(response(first));
                release(access.nodeId());
                return response;
            }
            response.onCompletion(() -> finish(entry));
            response.onTimeout(() -> finish(entry));
            waiting.put(access.nodeId(), entry);
            return response;
        } catch (RuntimeException e) {
            release(access.nodeId());
            throw e;
        }
    }

    @Scheduled(fixedDelay = 300, scheduler = "judgeClaimExecutor")
    public void pollWaiters() {
        for (var entry : waiting.values()) {
            if (entry.response().isSetOrExpired()) {
                finish(entry);
                continue;
            }
            try {
                var claimed = scheduler.claim(entry.access(), entry.slots());
                if (!claimed.jobs().isEmpty()) {
                    entry.response().setResult(response(claimed));
                    finish(entry);
                }
            } catch (JudgeProtocolException e) {
                entry.response()
                        .setResult(
                                ResponseEntity.status(e.getStatus())
                                        .body(Map.of("code", e.getCode())));
                finish(entry);
            } catch (RuntimeException e) {
                log.warn("Judge claim failed for node {}", entry.access().nodeId(), e);
                entry.response()
                        .setResult(
                                ResponseEntity.status(503)
                                        .body(Map.of("code", "CLAIM_UNAVAILABLE")));
                finish(entry);
            }
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void reapLeases() {
        for (long id : scheduler.expired()) {
            try {
                scheduler.reap(id);
            } catch (RuntimeException e) {
                log.warn("Judge lease recovery failed for job {}", id, e);
            }
        }
        jobs.cancelOrphanedQueued();
        nodes.expireChecks();
    }

    @Scheduled(fixedDelay = 2000)
    public void dispatchEvents() {
        for (long id : events.due()) {
            try {
                events.apply(id);
            } catch (RuntimeException e) {
                log.warn("Judge settlement event {} will retry", id, e);
                events.retry(id, e.getClass().getSimpleName());
            }
        }
    }

    private void acquire(long nodeId) {
        if (!claimLimit.tryAcquire()) {
            throw new JudgeProtocolException(429, "CLAIM_LIMIT");
        }
        if (!activeClaims.add(nodeId)) {
            claimLimit.release();
            throw new JudgeProtocolException(429, "CLAIM_ALREADY_PENDING");
        }
    }

    private void finish(Waiting entry) {
        long id = entry.access().nodeId();
        if (waiting.remove(id, entry)) {
            release(id);
        }
    }

    private void release(long nodeId) {
        if (activeClaims.remove(nodeId)) {
            claimLimit.release();
        }
    }

    private static JudgeScheduler.ClaimBatch empty() {
        return new JudgeScheduler.ClaimBatch(1, List.of());
    }

    private static ResponseEntity<JudgeScheduler.ClaimBatch> response(
            JudgeScheduler.ClaimBatch batch) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(batch);
    }
}
