package com.loopers.infrastructure.coupon;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.coupon.CouponQueryRepository;
import com.loopers.domain.coupon.OwnedCoupon;
import com.loopers.domain.coupon.QCouponCampaignModel;
import com.loopers.domain.coupon.QCouponModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 연결 캠페인은 LEFT JOIN 으로 읽는다. INNER JOIN 이면 캠페인이 없는 쿠폰이 조용히 빠지거나 '미발급'으로 보이므로,
 * 캠페인이 없는 행은 결과 검증에서 INTERNAL_ERROR 로 드러낸다.
 */
@RequiredArgsConstructor
@Component
public class CouponQueryRepositoryImpl implements CouponQueryRepository {

    private static final QCouponModel COUPON = QCouponModel.couponModel;
    private static final QCouponCampaignModel CAMPAIGN = QCouponCampaignModel.couponCampaignModel;

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<OwnedCoupon> findOwned(Long userId, Long campaignId) {
        Tuple row = baseQuery()
            .where(COUPON.userId.eq(userId), COUPON.campaignId.eq(campaignId))
            .fetchOne();
        return Optional.ofNullable(row).map(CouponQueryRepositoryImpl::toOwned);
    }

    @Override
    public PageResult<OwnedCoupon> findOwnedPage(Long userId, PageCommand page, ListSort sort) {
        List<OwnedCoupon> items = baseQuery()
            .where(COUPON.userId.eq(userId))
            .orderBy(orderOf(sort))
            .offset((long) page.page() * page.size())
            .limit(page.size())
            .fetch()
            .stream()
            .map(CouponQueryRepositoryImpl::toOwned)
            .toList();

        Long total = queryFactory.select(COUPON.count())
            .from(COUPON)
            .where(COUPON.userId.eq(userId))
            .fetchOne();

        return PageResult.of(items, page, total != null ? total : 0L);
    }

    private JPAQuery<Tuple> baseQuery() {
        return queryFactory
            .select(COUPON.id, COUPON.campaignId, CAMPAIGN.id, CAMPAIGN.name,
                COUPON.benefitType, COUPON.benefitValue, COUPON.createdAt)
            .from(COUPON)
            .leftJoin(CAMPAIGN).on(CAMPAIGN.id.eq(COUPON.campaignId));
    }

    private static OwnedCoupon toOwned(Tuple row) {
        Long couponId = row.get(COUPON.id);
        Long campaignId = row.get(COUPON.campaignId);
        if (row.get(CAMPAIGN.id) == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR,
                "쿠폰의 연결 캠페인이 없습니다. [couponId = " + couponId + ", campaignId = " + campaignId + "]");
        }
        return new OwnedCoupon(couponId, campaignId, row.get(CAMPAIGN.name),
            row.get(COUPON.benefitType), row.get(COUPON.benefitValue), row.get(COUPON.createdAt));
    }

    private static OrderSpecifier<?>[] orderOf(ListSort sort) {
        return sort.isDescending()
            ? new OrderSpecifier<?>[]{COUPON.createdAt.desc(), COUPON.id.desc()}
            : new OrderSpecifier<?>[]{COUPON.createdAt.asc(), COUPON.id.asc()};
    }
}
