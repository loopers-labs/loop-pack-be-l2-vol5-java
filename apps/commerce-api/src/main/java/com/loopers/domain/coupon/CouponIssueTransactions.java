package com.loopers.domain.coupon;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * 발급의 트랜잭션 단위. CouponIssueService가 예외 뒤에 새 트랜잭션을 열 수 있도록 별도 빈으로 둔다
 * (같은 클래스 안의 호출은 Spring 프록시를 거치지 않는다).
 * Redis를 호출하지 않는다. 트랜잭션은 3초를 넘기면 롤백한다.
 */
@RequiredArgsConstructor
@Component
public class CouponIssueTransactions {

    static final int TIMEOUT_SECONDS = 3;

    private final CouponCampaignRepository campaignRepository;
    private final CouponRepository couponRepository;

    /**
     * 순서: 쿠폰 INSERT(유일 제약) → 조건부 UPDATE(락은 마지막에 짧게).
     * UPDATE 영향 행이 0이면 예외로 INSERT까지 함께 롤백한다.
     * 유일 제약 위반은 DataIntegrityViolationException으로 그대로 올려 보내 롤백한다.
     */
    @Transactional(timeout = TIMEOUT_SECONDS)
    public Long insertAndIncrease(Long campaignId, Long userId, String benefitDescription) {
        CouponModel coupon = couponRepository.saveAndFlush(
            new CouponModel(campaignId, userId, benefitDescription, ZonedDateTime.now())
        );
        if (!campaignRepository.increaseIssuedCount(campaignId)) {
            throw new QuotaNotAcquiredException();
        }
        return coupon.getId();
    }

    @Transactional(readOnly = true, timeout = TIMEOUT_SECONDS)
    public Optional<CouponModel> findCoupon(Long campaignId, Long userId) {
        return couponRepository.findByCampaignIdAndUserId(campaignId, userId);
    }

    @Transactional(readOnly = true, timeout = TIMEOUT_SECONDS)
    public Optional<CouponCampaignModel> findCampaign(Long campaignId) {
        return campaignRepository.findById(campaignId);
    }

    /** 조건부 UPDATE가 한 행도 바꾸지 못했다는 신호. 롤백을 일으키는 용도이며 응답으로 나가지 않는다. */
    static final class QuotaNotAcquiredException extends RuntimeException {
        QuotaNotAcquiredException() {
            super("발급 수를 올리지 못했습니다(품절 또는 비활성).", null, false, false);
        }
    }
}
