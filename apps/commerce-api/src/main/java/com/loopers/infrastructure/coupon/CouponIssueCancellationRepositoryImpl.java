package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponIssueCancellationModel;
import com.loopers.domain.coupon.CouponIssueCancellationRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class CouponIssueCancellationRepositoryImpl implements CouponIssueCancellationRepository {
    private final CouponIssueCancellationJpaRepository cancellations;

    @Override
    public Optional<CouponIssueCancellationModel> find(Long campaignId, Long userId) {
        return cancellations.findByCampaignIdAndUserId(campaignId, userId);
    }

    @Override
    public List<CouponIssueCancellationModel> findAll(Long campaignId, Collection<Long> userIds) {
        if (userIds.isEmpty()) return List.of();
        return cancellations.findAllByCampaignIdAndUserIdIn(campaignId, userIds);
    }


    @Override
    public void saveIfAbsent(CouponIssueCancellationModel cancellation) {
        if (cancellations.findByCampaignIdAndUserId(cancellation.getCampaignId(), cancellation.getUserId()).isPresent()) {
            return;
        }
        try {
            cancellations.saveAndFlush(cancellation);
        } catch (DataIntegrityViolationException alreadySaved) {
            // 같은 기록이 먼저 들어갔다. 한 건이면 충분하다.
        }
    }
}
