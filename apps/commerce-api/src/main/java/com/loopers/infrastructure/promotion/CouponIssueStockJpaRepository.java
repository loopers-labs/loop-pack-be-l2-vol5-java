package com.loopers.infrastructure.promotion;

import com.loopers.domain.promotion.CouponIssueStockModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * 남은 수량은 조건 갱신으로만 줄인다 (DR-58). 행 잠금을 잡은 뒤 커밋된 최신 값으로 조건을 다시 보므로 0 아래로 내려가지 않는다.
 * 조건 갱신은 @PreUpdate 를 거치지 않아 updated_at 을 직접 넣는다.
 */
public interface CouponIssueStockJpaRepository extends JpaRepository<CouponIssueStockModel, Long> {
    Optional<CouponIssueStockModel> findByPromotionId(Long promotionId);

    @Modifying
    @Query("update CouponIssueStockModel s set s.remaining = s.remaining - 1, s.updatedAt = :now "
        + "where s.promotionId = :promotionId and s.remaining > 0")
    int decrease(@Param("promotionId") Long promotionId, @Param("now") ZonedDateTime now);
}
