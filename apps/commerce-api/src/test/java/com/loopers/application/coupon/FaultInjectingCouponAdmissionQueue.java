package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponAdmissionQueue;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;

/**
 * 승격 경로의 Redis 예외를 재현하기 위해 실제 대기열 구현을 감싸는 테스트 전용 포장지 (FaultInjectingProductListCache 와 같은 방식).
 * 기본 모드(PASS_THROUGH)는 모든 호출을 그대로 넘긴다. FAIL_PROMOTE 는 promote 만 DataAccessException 을 던진다.
 */
class FaultInjectingCouponAdmissionQueue implements CouponAdmissionQueue {

    enum Mode { PASS_THROUGH, FAIL_PROMOTE }

    private final CouponAdmissionQueue delegate;
    private volatile Mode mode = Mode.PASS_THROUGH;

    FaultInjectingCouponAdmissionQueue(CouponAdmissionQueue delegate) {
        this.delegate = delegate;
    }

    void setMode(Mode mode) {
        this.mode = mode;
    }

    void reset() {
        mode = Mode.PASS_THROUGH;
    }

    @Override
    public EnqueueResult enqueue(Long campaignId, Long userId, long acceptLimit, String expectedGen) {
        return delegate.enqueue(campaignId, userId, acceptLimit, expectedGen);
    }

    @Override
    public Optional<Long> waitingRank(Long campaignId, Long userId) {
        return delegate.waitingRank(campaignId, userId);
    }

    @Override
    public boolean isAdmitted(Long campaignId, Long userId) {
        return delegate.isAdmitted(campaignId, userId);
    }

    @Override
    public long acceptedCount(Long campaignId) {
        return delegate.acceptedCount(campaignId);
    }

    @Override
    public long waitingCount(Long campaignId) {
        return delegate.waitingCount(campaignId);
    }

    @Override
    public long admittedCount(Long campaignId) {
        return delegate.admittedCount(campaignId);
    }

    @Override
    public int promote(Long campaignId, String expectedGen) {
        if (mode == Mode.FAIL_PROMOTE) {
            throw new DataAccessResourceFailureException("승격 Lua 호출 실패(주입)");
        }
        return delegate.promote(campaignId, expectedGen);
    }

    @Override
    public boolean extendAdmission(Long campaignId, Long userId) {
        return delegate.extendAdmission(campaignId, userId);
    }

    @Override
    public boolean removeAdmitted(Long campaignId, Long userId) {
        return delegate.removeAdmitted(campaignId, userId);
    }
}
