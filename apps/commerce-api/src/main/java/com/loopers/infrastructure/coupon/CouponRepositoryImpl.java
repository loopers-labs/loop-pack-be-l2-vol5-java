package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponRepository;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static com.loopers.domain.coupon.QCoupon.coupon;

@RequiredArgsConstructor
@Component
public class CouponRepositoryImpl implements CouponRepository {
    private final CouponJpaRepository couponJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public Coupon save(Coupon coupon) {
        return couponJpaRepository.save(coupon);
    }

    @Override
    public Optional<Coupon> findByCampaignIdAndUserId(Long campaignId, Long userId) {
        return couponJpaRepository.findByCampaignIdAndUserId(campaignId, userId);
    }

    @Override
    public List<Coupon> findAllByUserId(Long userId) {
        return queryFactory
            .selectFrom(coupon)
            .where(coupon.userId.eq(userId))
            .orderBy(coupon.issuedAt.desc(), coupon.id.desc())
            .fetch();
    }
}
