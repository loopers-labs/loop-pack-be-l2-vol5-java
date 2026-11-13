package com.loopers.application.coupon.facade;

import com.loopers.domain.coupon.gateway.CouponIssueStream;
import com.loopers.domain.coupon.repository.CouponIssuanceStore;
import com.loopers.domain.coupon.vo.IssueEntry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 발급 worker 의 한 회차: 열린 캠페인마다 대기열에서 꺼내 DB 발급 → commit → Redis 갱신 · XACK (8주차 D-116, D-125).
 * 인스턴스당 동시 발급은 스레드 수로 고정한다 (8주차 D-171).
 * 실패한 항목은 XACK 하지 않아 대기열(PEL)에 남는다. 재시도 · 회수는 다음 단계에서 다룬다
 */
@Slf4j
@Component
public class CouponIssueWorker {

    private final CouponIssuanceStore couponIssuanceStore;
    private final CouponIssueStream couponIssueStream;
    private final CouponIssueSteps couponIssueSteps;
    private final String consumer;
    private final int batchSize;
    private final ExecutorService executor;

    public CouponIssueWorker(
        CouponIssuanceStore couponIssuanceStore,
        CouponIssueStream couponIssueStream,
        CouponIssueSteps couponIssueSteps,
        @Value("${coupon-issue.worker.instance-id}") String consumer,
        @Value("${coupon-issue.worker.batch-size}") int batchSize,
        @Value("${coupon-issue.worker.threads}") int threads
    ) {
        this.couponIssuanceStore = couponIssuanceStore;
        this.couponIssueStream = couponIssueStream;
        this.couponIssueSteps = couponIssueSteps;
        this.consumer = consumer;
        this.batchSize = batchSize;
        this.executor = Executors.newFixedThreadPool(threads);
    }

    /** @return 이번 회차에 발급을 마친 항목 수 */
    public int runOnce() {
        int completed = 0;
        for (Long campaignId : couponIssuanceStore.findOpenCampaignIds()) {
            completed += process(couponIssueStream.read(campaignId, consumer, batchSize));
        }
        return completed;
    }

    private int process(List<IssueEntry> entries) {
        List<Future<Boolean>> results = new ArrayList<>(entries.size());
        for (IssueEntry entry : entries) {
            results.add(executor.submit(() -> issue(entry)));
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
                log.error("발급 작업이 예기치 않게 끝남", e.getCause());
            }
        }
        return completed;
    }

    private boolean issue(IssueEntry entry) {
        try {
            long couponId = couponIssueSteps.issue(entry);
            couponIssueStream.completeIssued(entry, couponId);
            return true;
        } catch (RuntimeException e) {
            log.warn("발급 실패, 대기열에 남김. campaignId={} userId={} entryId={} cause={}",
                entry.campaignId(), entry.userId(), entry.entryId(), e.toString());
            return false;
        }
    }

    @PreDestroy
    void close() {
        executor.shutdown();
    }
}
