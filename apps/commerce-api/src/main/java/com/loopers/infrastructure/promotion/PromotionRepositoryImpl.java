package com.loopers.infrastructure.promotion;

import com.loopers.domain.promotion.CouponIssuePromotionModel;
import com.loopers.domain.promotion.PromotionModel;
import com.loopers.domain.promotion.PromotionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PromotionRepositoryImpl implements PromotionRepository {
    private final PromotionJpaRepository promotionJpaRepository;
    private final CouponIssuePromotionJpaRepository couponIssuePromotionJpaRepository;

    @Override
    public PromotionModel save(PromotionModel promotion) {
        return promotionJpaRepository.save(promotion);
    }

    @Override
    public Optional<PromotionModel> find(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return promotionJpaRepository.findById(id);
    }

    @Override
    public CouponIssuePromotionModel saveCouponIssue(CouponIssuePromotionModel couponIssue) {
        return couponIssuePromotionJpaRepository.save(couponIssue);
    }

    @Override
    public Optional<CouponIssuePromotionModel> findCouponIssue(Long promotionId) {
        if (promotionId == null) {
            return Optional.empty();
        }
        return couponIssuePromotionJpaRepository.findByPromotionId(promotionId);
    }
}
