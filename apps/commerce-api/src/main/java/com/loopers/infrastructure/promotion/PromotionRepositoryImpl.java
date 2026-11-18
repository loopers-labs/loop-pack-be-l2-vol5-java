package com.loopers.infrastructure.promotion;

import com.loopers.domain.promotion.CouponIssuePromotionModel;
import com.loopers.domain.promotion.CouponIssueStockModel;
import com.loopers.domain.promotion.IssuedCouponModel;
import com.loopers.domain.promotion.PromotionModel;
import com.loopers.domain.promotion.PromotionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PromotionRepositoryImpl implements PromotionRepository {
    private final PromotionJpaRepository promotionJpaRepository;
    private final CouponIssuePromotionJpaRepository couponIssuePromotionJpaRepository;
    private final CouponIssueStockJpaRepository couponIssueStockJpaRepository;
    private final IssuedCouponJpaRepository issuedCouponJpaRepository;

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

    @Override
    public CouponIssueStockModel saveStock(CouponIssueStockModel stock) {
        return couponIssueStockJpaRepository.save(stock);
    }

    @Override
    public Optional<CouponIssueStockModel> findStock(Long promotionId) {
        if (promotionId == null) {
            return Optional.empty();
        }
        return couponIssueStockJpaRepository.findByPromotionId(promotionId);
    }

    @Override
    public boolean decreaseStock(Long promotionId) {
        return couponIssueStockJpaRepository.decrease(promotionId, ZonedDateTime.now()) == 1;
    }

    /**
     * 바로 flush 해 (user_id, promotion_id) 고유 제약을 이 자리에서 확인한다. 위반이면 empty.
     * 위반 뒤 TX 는 rollback 전용이 되므로 호출자는 예외로 끝내야 한다.
     */
    @Override
    public Optional<IssuedCouponModel> insertIssuedCoupon(IssuedCouponModel issuedCoupon) {
        try {
            return Optional.of(issuedCouponJpaRepository.saveAndFlush(issuedCoupon));
        } catch (DataIntegrityViolationException e) {
            return Optional.empty();
        }
    }

    /** (user_id, promotion_id) 고유 인덱스로 찾는다. */
    @Override
    public boolean existsIssuedCoupon(Long userId, Long promotionId) {
        return issuedCouponJpaRepository.existsByUserIdAndPromotionId(userId, promotionId);
    }
}
