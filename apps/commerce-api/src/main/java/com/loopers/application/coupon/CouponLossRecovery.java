package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponReservation;
import com.loopers.domain.coupon.CouponReservation.Reservation;
import com.loopers.domain.coupon.CouponService;
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
public class CouponLossRecovery {

    static final String CLOSED = "coupon.issue.closed";
    static final String RECOVERED = "coupon.issue.recovery";
    private static final int PAGE = 1_000;

    private final CouponReservation couponReservation;
    private final CouponService couponService;
    private final MeterRegistry meterRegistry;

    public void close(Long campaignId) {
        if (couponReservation.isClosed(campaignId)) {
            return;
        }
        couponReservation.close(campaignId);
        meterRegistry.counter(CLOSED, "campaignId", String.valueOf(campaignId)).increment();
        log.error("Redis 예약 집합이 DB 와 어긋나 캠페인을 닫았다. 워커 lag 가 0 인 것을 확인한 뒤 관리자가 복구한다: campaignId={}", campaignId);
    }

    public int recover(Long campaignId) {
        if (!couponReservation.isClosed(campaignId)) {
            throw new DomainException(DomainError.COUPON_CAMPAIGN_NOT_CLOSED);
        }
        int restored = rebuildFromDb(campaignId);
        couponReservation.prepare(campaignId);
        meterRegistry.counter(RECOVERED, "campaignId", String.valueOf(campaignId)).increment();
        couponReservation.open(campaignId);
        log.warn("DB 쿠폰으로 예약 집합을 다시 만들고 캠페인을 열었다: campaignId={}, restored={}", campaignId, restored);
        return restored;
    }

    private int rebuildFromDb(Long campaignId) {
        couponReservation.clear(campaignId);
        int restored = 0;
        long afterId = 0;
        while (true) {
            List<Coupon> page = couponService.issuedAfterId(campaignId, afterId, PAGE);
            if (page.isEmpty()) {
                return restored;
            }
            couponReservation.restore(campaignId, page.stream()
                .map(coupon -> new Reservation(coupon.userId(), coupon.issueNumber()))
                .toList());
            restored += page.size();
            afterId = page.get(page.size() - 1).id();
            if (page.size() < PAGE) {
                return restored;
            }
        }
    }
}
