package com.loopers.application.coupon.facade;

import com.loopers.domain.coupon.gateway.CouponIssueRequestResender;
import com.loopers.domain.coupon.gateway.CouponIssueStream;
import com.loopers.domain.coupon.repository.CouponIssuanceStore;
import com.loopers.domain.coupon.repository.CouponIssueRequestStore;
import com.loopers.domain.coupon.vo.IssueEntry;
import com.loopers.domain.coupon.vo.PendingConfirm;
import com.loopers.support.error.IssueRejectedException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 발급 worker 의 한 회차. 열린 캠페인마다: 지연 항목 이동 → 죽은 worker 항목 회수 → 확인 대기 정리 → 새 항목 읽기 → 처리 → 발급 수 재계산
 * (8주차 D-130, D-134, D-142).
 * 처리: DB 발급 → commit → Redis 갱신 · XACK (D-125). 인스턴스당 동시 발급은 스레드 수로 고정한다 (D-171).
 * 실패는 분류해서 다룬다 (D-126): 영구는 즉시 FAILED(승격 없음), 일시는 상한 없이 재시도, 미분류는 상한 뒤 FAILED · 승격
 */
@Slf4j
@Component
public class CouponIssueWorker {

    private final CouponIssuanceStore couponIssuanceStore;
    private final CouponIssueStream couponIssueStream;
    private final CouponIssueSteps couponIssueSteps;
    private final CouponIssueRequestStore couponIssueRequestStore;
    private final CouponIssueRequestResender couponIssueRequestResender;
    private final CouponIssueWorkerProperties properties;
    private final Clock clock;
    private final ExecutorService executor;
    private final Map<Long, Instant> lastRecount = new ConcurrentHashMap<>();

    public CouponIssueWorker(
        CouponIssuanceStore couponIssuanceStore,
        CouponIssueStream couponIssueStream,
        CouponIssueSteps couponIssueSteps,
        CouponIssueRequestStore couponIssueRequestStore,
        CouponIssueRequestResender couponIssueRequestResender,
        CouponIssueWorkerProperties properties,
        Clock clock
    ) {
        this.couponIssuanceStore = couponIssuanceStore;
        this.couponIssueStream = couponIssueStream;
        this.couponIssueSteps = couponIssueSteps;
        this.couponIssueRequestStore = couponIssueRequestStore;
        this.couponIssueRequestResender = couponIssueRequestResender;
        this.properties = properties;
        this.clock = clock;
        this.executor = Executors.newFixedThreadPool(properties.threads());
    }

    /** @return 이번 회차에 발급을 마친 항목 수 */
    public int runOnce() {
        int completed = 0;
        for (Long campaignId : couponIssuanceStore.findOpenCampaignIds()) {
            completed += runCampaign(campaignId);
        }
        return completed;
    }

    private int runCampaign(long campaignId) {
        couponIssueStream.moveDue(campaignId, clock.millis(), properties.batchSize());
        List<IssueEntry> entries = new ArrayList<>(
            couponIssueStream.claim(campaignId, properties.instanceId(), properties.claimMinIdle(), properties.batchSize()));
        resolvePending(campaignId);
        entries.addAll(couponIssueStream.read(campaignId, properties.instanceId(), properties.batchSize()));
        int completed = process(entries);
        recountIfDue(campaignId);
        return completed;
    }

    /**
     * 확정되지 못한 접수를 정리한다. 같은 key · 순번으로 재전송해 확인되면 확정하고, 재전송이 안 되면 DB 접수 기록이 있을 때만 확정한다.
     * 결과를 모르는 사람은 지우지 않고 다음 회차로 미룬다 (D-134)
     */
    private void resolvePending(long campaignId) {
        long acceptedBefore = clock.millis() - properties.pendingConfirmAfter().toMillis();
        for (PendingConfirm pending : couponIssueStream.stalePending(campaignId, acceptedBefore, properties.batchSize())) {
            try {
                if (couponIssueRequestResender.resend(pending) || couponIssueRequestStore.exists(campaignId, pending.userId())) {
                    couponIssueStream.confirm(campaignId, pending.userId());
                    log.info("확인 대기 접수를 확정함. campaignId={} userId={} sequence={}", campaignId, pending.userId(), pending.sequence());
                }
            } catch (RuntimeException e) {
                log.warn("확인 대기 정리 실패, 다음 회차에 다시 함. campaignId={} userId={} cause={}",
                    campaignId, pending.userId(), e.toString());
            }
        }
    }

    private int process(List<IssueEntry> entries) {
        List<Future<Boolean>> results = new ArrayList<>(entries.size());
        for (IssueEntry entry : entries) {
            results.add(executor.submit(() -> handle(entry)));
        }
        int completed = 0;
        for (Future<Boolean> result : results) {
            try {
                if (result.get()) {
                    completed++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return completed;
            } catch (ExecutionException e) {
                log.error("발급 작업이 예기치 않게 끝남. 항목은 대기열에 남아 회수된다", e.getCause());
            }
        }
        return completed;
    }

    /** @return 발급을 마쳤는가 */
    boolean handle(IssueEntry entry) {
        if (entry.deliveries() > properties.poisonDeliveries()) {
            log.error("전달 횟수 상한을 넘은 poison 항목이라 최종 실패로 둠. campaignId={} userId={} deliveries={}",
                entry.campaignId(), entry.userId(), entry.deliveries());
            return fail(entry, true);
        }
        Duration wait = properties.innerBackoff();
        for (int attempt = 0; ; attempt++) {
            try {
                complete(entry, couponIssueSteps.issue(entry));
                return true;
            } catch (IssueRejectedException e) {
                log.warn("영구 오류라 재시도 없이 최종 실패. 승격하지 않음. campaignId={} userId={} cause={}",
                    entry.campaignId(), entry.userId(), e.getMessage());
                return fail(entry, false);
            } catch (RuntimeException e) {
                if (!isTransient(e)) {
                    return unclassified(entry, e);
                }
                if (attempt >= properties.innerRetries()) {
                    redeliverLater(entry.withTransient(), e);
                    return false;
                }
                sleep(wait);
                wait = wait.multipliedBy(2);
            }
        }
    }

    private void complete(IssueEntry entry, long couponId) {
        couponIssueStream.completeIssued(entry, couponId);
    }

    /** 미분류 예외는 항목별로 센다. 상한에 닿으면 최종 실패 · 승격 (D-126, D-127) */
    private boolean unclassified(IssueEntry entry, RuntimeException e) {
        IssueEntry counted = entry.withUnclassified();
        if (counted.unclassified() >= properties.unclassifiedLimit()) {
            log.error("미분류 예외가 상한에 닿아 최종 실패. campaignId={} userId={} count={}",
                entry.campaignId(), entry.userId(), counted.unclassified(), e);
            return fail(counted, true);
        }
        redeliverLater(counted, e);
        return false;
    }

    /** 다음 시도 시각까지 지연 ZSET 에 둔다. backoff 는 시도 횟수에 따라 2배씩, 최대값까지 (D-129) */
    private void redeliverLater(IssueEntry entry, RuntimeException cause) {
        int attempts = Math.max(entry.transientAttempts(), entry.unclassified());
        long delay = Math.min(properties.redeliveryMax().toMillis(),
            properties.redeliveryInitial().toMillis() << Math.min(Math.max(attempts - 1, 0), 30));
        log.warn("발급을 다음 시도로 미룸. campaignId={} userId={} delayMs={} cause={}",
            entry.campaignId(), entry.userId(), delay, cause.toString());
        try {
            couponIssueStream.delay(entry, clock.millis() + delay);
        } catch (RuntimeException e) {
            log.warn("지연 등록 실패, 대기열에 남겨 회수에 맡김. campaignId={} userId={} cause={}",
                entry.campaignId(), entry.userId(), e.toString());
        }
    }

    /**
     * 최종 실패. 새 TX 로 기존 쿠폰을 다시 확인해 있으면 발급으로 끝낸다 (D-120).
     * 기록 · 승격이 실패하면 항목은 대기열에 남아 회수되고, 다시 와도 한 번만 승격된다
     */
    private boolean fail(IssueEntry entry, boolean promote) {
        try {
            Optional<Long> existing = couponIssueSteps.markFailed(entry);
            if (existing.isPresent()) {
                complete(entry, existing.get());
                return true;
            }
            Long promoted = couponIssueStream.fail(entry, promote);
            if (promoted != null) {
                log.info("예비 승격. campaignId={} failedUserId={} promotedUserId={} slotNo={}",
                    entry.campaignId(), entry.userId(), promoted, entry.slotNo());
            }
        } catch (RuntimeException e) {
            log.warn("최종 실패 기록 실패, 대기열에 남겨 회수에 맡김. campaignId={} userId={} cause={}",
                entry.campaignId(), entry.userId(), e.toString());
        }
        return false;
    }

    /** 일정 간격마다 발급 수를 쿠폰 수로 맞추고, 한도에 닿으면 품절 플래그를 세운다 (D-142) */
    private void recountIfDue(long campaignId) {
        Instant now = clock.instant();
        Instant last = lastRecount.get(campaignId);
        if (last != null && now.isBefore(last.plus(properties.recountInterval()))) {
            return;
        }
        lastRecount.put(campaignId, now);
        try {
            if (couponIssueSteps.recountIssued(campaignId)) {
                couponIssueStream.markSoldOut(campaignId);
            }
        } catch (RuntimeException e) {
            log.warn("발급 수 재계산 실패, 다음 회차에 다시 함. campaignId={} cause={}", campaignId, e.toString());
            lastRecount.remove(campaignId);
        }
    }

    /** 연결 실패 · timeout · 잠금 대기 초과처럼 다시 하면 될 수 있는 오류 (D-126) */
    private static boolean isTransient(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException
                || cause instanceof DataAccessResourceFailureException
                || cause instanceof RecoverableDataAccessException) {
                return true;
            }
        }
        return false;
    }

    private static void sleep(Duration wait) {
        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void close() {
        executor.shutdown();
    }
}
