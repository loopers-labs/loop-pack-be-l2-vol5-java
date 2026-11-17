package com.loopers.infrastructure.query.coupon;

import com.loopers.application.coupon.query.CouponQueryDao;
import com.loopers.application.coupon.query.CouponView;
import com.loopers.infrastructure.persistence.coupon.entity.QCouponJpaEntity;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QueryDslCouponQueryDao implements CouponQueryDao {
    private static final QCouponJpaEntity COUPON = QCouponJpaEntity.couponJpaEntity;

    private final JPAQueryFactory queryFactory;

    @Override
    public List<CouponView> findCoupons(long userId) {
        return selectCoupons()
            .where(COUPON.userId.eq(userId))
            .orderBy(COUPON.issuedAt.desc(), COUPON.id.desc())
            .fetch();
    }

    @Override
    public Optional<CouponView> findIssuedCoupon(long userId, long campaignId) {
        return Optional.ofNullable(selectCoupons()
            .where(COUPON.userId.eq(userId), COUPON.campaignId.eq(campaignId))
            .fetchOne());
    }

    private JPAQuery<CouponView> selectCoupons() {
        return queryFactory.select(Projections.constructor(CouponView.class, COUPON.id, COUPON.campaignId,
                COUPON.issuedAt, COUPON.benefitDescription, COUPON.discountAmount))
            .from(COUPON);
    }
}
