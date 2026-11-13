package com.loopers.application.coupon;

import com.loopers.config.coupon.CouponAdmissionProperties;
import com.loopers.domain.coupon.CouponIssueCircuit;
import com.loopers.infrastructure.coupon.Resilience4jCouponIssueCircuit;

import java.time.Clock;

/**
 * 테스트 전용 서킷 포장지. 서킷은 전체에 하나인 싱글톤이라 한 테스트가 열어 둔 상태가 다음 테스트로 넘어간다.
 * 매 테스트 전에 reset() 으로 운영 구현을 새로 만들어 끼우면 초기 상태(CLOSED, 빈 윈도우)에서 시작한다. 운영 코드에는 초기화 기능을 두지 않는다.
 */
class ResettableCouponIssueCircuit implements CouponIssueCircuit {

    private final CouponAdmissionProperties properties;
    private final Clock clock;
    private volatile CouponIssueCircuit delegate;

    ResettableCouponIssueCircuit(CouponAdmissionProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        reset();
    }

    void reset() {
        delegate = new Resilience4jCouponIssueCircuit(properties, clock);
    }

    @Override
    public State state() {
        return delegate.state();
    }

    @Override
    public boolean tryAcquire() {
        return delegate.tryAcquire();
    }

    @Override
    public void release() {
        delegate.release();
    }

    @Override
    public void recordSuccess() {
        delegate.recordSuccess();
    }

    @Override
    public void recordFailure(Throwable cause) {
        delegate.recordFailure(cause);
    }
}
