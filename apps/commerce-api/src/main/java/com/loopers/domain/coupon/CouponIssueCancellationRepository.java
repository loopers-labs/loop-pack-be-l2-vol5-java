package com.loopers.domain.coupon;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CouponIssueCancellationRepository {
    Optional<CouponIssueCancellationModel> find(Long campaignId, Long userId);

    /** 한 캠페인에서 주어진 사용자들의 취소 기록. 한 번의 조회로 확인한다. */
    List<CouponIssueCancellationModel> findAll(Long campaignId, Collection<Long> userIds);

    /** 같은 사용자의 취소 기록이 이미 있으면 그대로 둔다(재처리에도 한 건). */
    void saveIfAbsent(CouponIssueCancellationModel cancellation);
}
