package com.loopers.domain.coupon;

import java.util.Optional;

/**
 * 캠페인 저장소 포트. 구현은 infrastructure/coupon의 JPA다.
 * 캠페인 행을 바꾸는 경로(발급, 비활성화, 혜택 변경)는 모두 {@link #findByIdForUpdate}로 읽는다(docs/week8/decisions.md D-8).
 */
public interface CouponCampaignRepository {

    CouponCampaign save(CouponCampaign campaign);

    Optional<CouponCampaign> findById(Long id);

    /**
     * {@code SELECT … FOR UPDATE}로 읽는다. 내 TX가 끝날 때까지 다른 TX가 이 행을 바꾸거나 같은 락을 잡지 못하므로,
     * 읽은 발급 수·활성 여부로 한 판단이 commit 시점까지 참이다(D-8, INV-801).
     */
    Optional<CouponCampaign> findByIdForUpdate(Long id);
}
