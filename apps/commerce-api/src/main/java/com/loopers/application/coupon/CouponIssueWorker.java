package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;
import com.loopers.domain.coupon.CouponReservation;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueWorker {

    static final String REJECTED_BY_DB = "coupon.issue.rejected_by_db";
    static final String BATCH = "coupon.issue.worker.batch";

    private final CouponTransactions couponTransactions;
    private final CouponReservation couponReservation;
    private final CouponLossRecovery couponLossRecovery;
    private final MeterRegistry meterRegistry;

    public static final class RecordFailed extends RuntimeException {

        private final int index;

        RecordFailed(int index, RuntimeException cause) {
            super(cause.getMessage(), cause);
            this.index = index;
        }

        public int index() {
            return index;
        }
    }

    public void record(List<CouponIssue> issues) {
        if (issues.isEmpty()) {
            return;
        }
        meterRegistry.summary(BATCH).record(issues.size());
        int start = 0;
        while (start < issues.size()) {
            int end = start + 1;
            while (end < issues.size() && issues.get(end).campaignId().equals(issues.get(start).campaignId())) {
                end++;
            }
            recordRun(issues, start, end);
            start = end;
        }
    }

    private void recordRun(List<CouponIssue> issues, int start, int end) {
        List<CouponIssue> run = issues.subList(start, end);
        Long campaignId = run.get(0).campaignId();
        try {
            couponTransactions.issueAll(campaignId, run);
            log.debug("접수 묶음을 쿠폰으로 기록했다: campaignId={}, size={}, issueNumbers={}..{}",
                campaignId, run.size(), run.get(0).issueNumber(), run.get(run.size() - 1).issueNumber());
            return;
        } catch (DomainException e) {
            if (e.error() != DomainError.COUPON_ALREADY_ISSUED && e.error() != DomainError.COUPON_SOLD_OUT) {
                throw new RecordFailed(start, e);
            }
            log.info("묶음을 한 번에 기록하지 못해 건별로 다시 기록한다: campaignId={}, size={}, error={}",
                campaignId, run.size(), e.error());
        } catch (RuntimeException e) {
            throw new RecordFailed(start, e);
        }
        for (int i = 0; i < run.size(); i++) {
            try {
                record(run.get(i));
            } catch (RuntimeException e) {
                throw new RecordFailed(start + i, e);
            }
        }
    }

    private void record(CouponIssue issue) {
        try {
            Coupon coupon = couponTransactions.issue(issue.campaignId(), issue.userId(), issue.issueNumber(),
                issue.reservedAt());
            log.debug("접수를 쿠폰으로 기록했다: campaignId={}, userId={}, issueNumber={}, couponId={}",
                issue.campaignId(), issue.userId(), issue.issueNumber(), coupon.id());
        } catch (DomainException e) {
            switch (e.error()) {
                case COUPON_ALREADY_ISSUED -> log.info("이미 기록된 접수라 넘긴다: campaignId={}, userId={}, issueNumber={}",
                    issue.campaignId(), issue.userId(), issue.issueNumber());
                case COUPON_SOLD_OUT -> rejectedByDb(issue);
                default -> throw e;
            }
        }
    }

    private void rejectedByDb(CouponIssue issue) {
        meterRegistry.counter(REJECTED_BY_DB, "campaignId", String.valueOf(issue.campaignId())).increment();
        if (!couponReservation.isReserved(issue.campaignId(), issue.userId())) {
            log.warn("DB 가 품절로 거절했지만 Redis 가 받아들인 사용자가 아니라 어긋남이 아니다. 넘긴다: campaignId={}, userId={}, issueNumber={}",
                issue.campaignId(), issue.userId(), issue.issueNumber());
            return;
        }
        log.warn("Redis 가 받아들인 접수를 DB 가 품절로 거절했다. 예약 집합과 DB 가 어긋났다: campaignId={}, userId={}, issueNumber={}",
            issue.campaignId(), issue.userId(), issue.issueNumber());
        couponLossRecovery.close(issue.campaignId());
    }
}
