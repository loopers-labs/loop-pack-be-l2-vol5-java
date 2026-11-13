package com.loopers.application.coupon.facade;

import com.loopers.domain.coupon.repository.CouponIssuanceStore;
import com.loopers.domain.coupon.vo.IssueEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 발급 TX. Redis 갱신 · XACK 는 이 TX 가 commit 된 뒤 worker 가 한다 (8주차 D-125) */
@RequiredArgsConstructor
@Component
public class CouponIssueSteps {

    private final CouponIssuanceStore couponIssuanceStore;
    private final Clock clock;

    @Transactional
    public long issue(IssueEntry entry) {
        // DB(DATETIME(6))와 같은 마이크로초 단위로 자른다
        return couponIssuanceStore.issue(entry, Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
    }
}
