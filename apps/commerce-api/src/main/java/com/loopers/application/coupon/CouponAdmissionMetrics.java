package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponPermitSemaphore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 쿠폰 수용량 통제 메트릭 (8주차 설계 §7-2·§7-4·§8-2). 기존 캐시 메트릭처럼 점 구분 이름 + result/reason 태그, 태그 값은 snake_case.
 * - coupon.admission.reject{reason}: 거절. gate_closed · stale_generation · unexpected_result · redis_error · sold_out · not_admitted · no_permit · db_unavailable
 * - coupon.admission.release{result}: 자리 반납. released · recovered(이미 회수됨, D4-1) · failed(3회 재시도 실패)
 * - coupon.admission.active_requests: 이 인스턴스에서 자리를 쥐고 DB 발급을 실행 중인 요청 수
 * - coupon.admission.permits{campaign}: permits 크기(ZCARD). 지표를 수집할 때만 읽고, 읽기 실패는 삼키고 마지막 값을 유지한다. active_requests 와의 차이가 누수다
 */
@Component
public class CouponAdmissionMetrics {

    static final String REJECT = "coupon.admission.reject";
    static final String RELEASE = "coupon.admission.release";
    static final String ACTIVE_REQUESTS = "coupon.admission.active_requests";
    static final String PERMITS = "coupon.admission.permits";

    private final MeterRegistry registry;
    private final CouponPermitSemaphore semaphore;
    private final AtomicInteger activeRequests = new AtomicInteger();
    private final Map<Long, AtomicLong> lastPermitsByCampaign = new ConcurrentHashMap<>();

    public CouponAdmissionMetrics(MeterRegistry registry, CouponPermitSemaphore semaphore) {
        this.registry = registry;
        this.semaphore = semaphore;
        Gauge.builder(ACTIVE_REQUESTS, activeRequests, AtomicInteger::get).register(registry);
    }

    public void reject(String reason) {
        registry.counter(REJECT, "reason", reason).increment();
    }

    public void release(String result) {
        registry.counter(RELEASE, "result", result).increment();
    }

    public void requestStarted() {
        activeRequests.incrementAndGet();
    }

    public void requestFinished() {
        activeRequests.decrementAndGet();
    }

    public int activeRequests() {
        return activeRequests.get();
    }

    /** 캠페인을 처음 보면 permits Gauge 를 등록한다. 값은 수집 시점에 ZCARD 로 읽는다. 요청 경로에서는 Redis 를 추가로 호출하지 않는다. */
    public void trackPermits(Long campaignId) {
        lastPermitsByCampaign.computeIfAbsent(campaignId, id -> {
            AtomicLong last = new AtomicLong();
            Gauge.builder(PERMITS, last, holder -> readPermits(id, holder))
                .tag("campaign", String.valueOf(id))
                .register(registry);
            return last;
        });
    }

    /** 수집 중 Redis 예외는 삼키고 마지막 값을 돌려준다. gate 는 닫지 않는다 (읽기는 상태를 바꾸지 않는다). */
    private double readPermits(Long campaignId, AtomicLong last) {
        try {
            last.set(semaphore.permitCount(campaignId));
        } catch (RuntimeException e) {
            // 마지막 값 유지
        }
        return last.get();
    }
}
