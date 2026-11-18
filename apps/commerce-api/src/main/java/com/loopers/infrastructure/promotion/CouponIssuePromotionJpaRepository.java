package com.loopers.infrastructure.promotion;

import com.loopers.domain.promotion.CouponIssuePromotionModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CouponIssuePromotionJpaRepository extends JpaRepository<CouponIssuePromotionModel, Long> {
    Optional<CouponIssuePromotionModel> findByPromotionId(Long promotionId);
}
