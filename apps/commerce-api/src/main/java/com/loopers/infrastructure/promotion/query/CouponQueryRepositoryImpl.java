package com.loopers.infrastructure.promotion.query;

import com.loopers.application.promotion.query.CouponQueryRepository;
import com.loopers.application.promotion.query.CouponView;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.loopers.domain.promotion.QCouponIssuePromotionModel.couponIssuePromotionModel;
import static com.loopers.domain.promotion.QIssuedCouponModel.issuedCouponModel;
import static com.loopers.domain.promotion.QPromotionModel.promotionModel;

/** 같은 AG(AG-07) 안의 TB-18·TB-15·TB-16 을 ID 로 조인한다. */
@RequiredArgsConstructor
@Component
public class CouponQueryRepositoryImpl implements CouponQueryRepository {
    private final JPAQueryFactory queryFactory;

    @Override
    public List<CouponView.MyCoupon> findMyCoupons(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return queryFactory
            .select(Projections.constructor(CouponView.MyCoupon.class,
                issuedCouponModel.id,
                issuedCouponModel.promotionId,
                promotionModel.name,
                couponIssuePromotionModel.couponSpec,
                issuedCouponModel.createdAt))
            .from(issuedCouponModel)
            .join(promotionModel).on(promotionModel.id.eq(issuedCouponModel.promotionId))
            .join(couponIssuePromotionModel).on(couponIssuePromotionModel.promotionId.eq(issuedCouponModel.promotionId))
            .where(issuedCouponModel.userId.eq(userId))
            .orderBy(issuedCouponModel.id.desc())
            .fetch();
    }
}
