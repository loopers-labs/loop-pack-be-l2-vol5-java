package com.loopers.domain.coupon;

import java.util.Optional;

/**
 * 기본 token 자리(tryAcquire/release)만 쓰는 기존 test 대역의 공통 부모. 대기실 메서드는 호출되면 바로 실패한다.
 * 제품 포트에 허위 성공 기본 구현을 두지 않고, 대역이 대기실 경로에 잘못 쓰이면 드러나게 한다.
 */
public interface TokenOnlyAdmissionRepository extends CouponAdmissionRepository {

    @Override
    default Registration register(Long campaignId, Long userId, String newWaitingId) {
        throw unsupported("register");
    }

    @Override
    default Optional<CouponWaitingSnapshot> find(Long campaignId, Long userId) {
        throw unsupported("find");
    }

    @Override
    default AdmissionProgress processAdmissions(Long campaignId, int capacity, long admissionDurationMillis, boolean soldOut, boolean canAdmit) {
        throw unsupported("processAdmissions");
    }

    @Override
    default Start start(Long campaignId, Long userId, String waitingId) {
        throw unsupported("start");
    }

    @Override
    default FinishOutcome finish(Long campaignId, Long userId, String waitingId, CouponWaitingState outcome) {
        throw unsupported("finish");
    }

    private static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException("token 전용 test 대역은 대기실 " + method + " 를 지원하지 않습니다.");
    }
}
