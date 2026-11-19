package com.loopers.infrastructure.product.persistence;

import com.loopers.domain.brand.entity.QBrand;
import com.loopers.domain.product.entity.QProduct;
import com.loopers.domain.product.entity.QWeeklyRankingScore;
import com.loopers.domain.product.entity.WeeklyRankingSnapshot;
import com.loopers.domain.product.enums.RankingSnapshotStatus;
import com.loopers.domain.product.repository.WeeklyRankingRepository;
import com.loopers.domain.product.vo.RankedProductView;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class WeeklyRankingRepositoryImpl implements WeeklyRankingRepository {

    private static final QWeeklyRankingScore SCORE = QWeeklyRankingScore.weeklyRankingScore;
    private static final QProduct PRODUCT = QProduct.product;
    private static final QBrand BRAND = QBrand.brand;

    private final WeeklyRankingSnapshotJpaRepository snapshotJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<WeeklyRankingSnapshot> findPublished(LocalDate periodStart) {
        return snapshotJpaRepository.findByPeriodStartAndStatus(periodStart, RankingSnapshotStatus.PUBLISHED);
    }

    // 삭제 상품 제외와 limit 을 한 쿼리에서 적용해, 거른 뒤 개수가 모자라지 않게 함. 브랜드 삭제는 상품 삭제로 이어짐 (9주차 D-188, D-189)
    @Override
    public List<RankedProductView> findVisibleRanks(String snapshotId, int limit) {
        return queryFactory
            .select(Projections.constructor(RankedProductView.class,
                SCORE.productId, SCORE.score, SCORE.ranking, PRODUCT.name, PRODUCT.price, BRAND.id, BRAND.name))
            .from(SCORE)
            .join(PRODUCT).on(PRODUCT.id.eq(SCORE.productId))
            .join(PRODUCT.brand, BRAND)
            .where(SCORE.snapshotId.eq(snapshotId), PRODUCT.deletedAt.isNull())
            .orderBy(SCORE.ranking.asc())
            .limit(limit)
            .fetch();
    }
}
