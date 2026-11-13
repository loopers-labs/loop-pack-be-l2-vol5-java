package com.loopers.infrastructure.query.coupon;

import com.loopers.application.coupon.query.CouponCounterSnapshotDao;
import com.loopers.infrastructure.persistence.coupon.entity.QCouponJpaEntity;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class QueryDslCouponCounterSnapshotDao implements CouponCounterSnapshotDao {
    private final JPAQueryFactory queryFactory;

    @Override
    public List<Long> findCommittedCouponIds(long campaignId) {
        QCouponJpaEntity coupon = QCouponJpaEntity.couponJpaEntity;
        return queryFactory.select(coupon.id).from(coupon)
            .where(coupon.campaignId.eq(campaignId)).orderBy(coupon.id.asc()).fetch();
    }
}
