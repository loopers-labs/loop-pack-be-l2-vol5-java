package com.loopers.infrastructure.promotion;

import com.loopers.domain.promotion.PromotionModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromotionJpaRepository extends JpaRepository<PromotionModel, Long> {
}
