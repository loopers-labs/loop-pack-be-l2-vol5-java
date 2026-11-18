package com.loopers.ranking.adapter.out.persistence;

import com.loopers.brand.domain.QBrandModel;
import com.loopers.product.domain.QProductModel;
import com.loopers.ranking.application.port.out.RankingQueryPort;
import com.loopers.ranking.domain.QRankingPublicationModel;
import com.loopers.ranking.domain.QRankingSnapshotModel;
import com.loopers.ranking.domain.QWeeklyRankingModel;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 주간 랭킹 조회 (W9 D-6). 순위 행에 상품·브랜드를 붙여 한 번에 읽는다.
 * 삭제된 상품은 기존 고객 조회와 같은 조건(deleted_at IS NULL, PRD-06)으로 먼저 빼고, 그 뒤에 limit을 적용한다.
 * 삭제된 브랜드의 상품은 BRD-02(살아 있는 상품이 있으면 브랜드 삭제 거절) 때문에 따로 거르지 않는다.
 */
@RequiredArgsConstructor
@Component
public class RankingQueryPersistenceAdapter implements RankingQueryPort {

    private static final QRankingPublicationModel PUBLICATION = QRankingPublicationModel.rankingPublicationModel;
    private static final QRankingSnapshotModel SNAPSHOT = QRankingSnapshotModel.rankingSnapshotModel;
    private static final QWeeklyRankingModel RANKING = QWeeklyRankingModel.weeklyRankingModel;
    private static final QProductModel PRODUCT = QProductModel.productModel;
    private static final QBrandModel BRAND = QBrandModel.brandModel;

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<PublishedSnapshot> findPublished(LocalDate periodStart) {
        Tuple row = queryFactory
            .select(PUBLICATION.snapshotId, SNAPSHOT.frozenAt)
            .from(PUBLICATION)
            .join(SNAPSHOT).on(SNAPSHOT.snapshotId.eq(PUBLICATION.snapshotId))
            .where(PUBLICATION.periodStart.eq(periodStart))
            .fetchOne();
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new PublishedSnapshot(row.get(PUBLICATION.snapshotId), row.get(SNAPSHOT.frozenAt)));
    }

    @Override
    public List<RankedProduct> findRankedProducts(String snapshotId, int limit) {
        return queryFactory
            .select(RANKING.rankNo, RANKING.productId, RANKING.score, PRODUCT.name, PRODUCT.price, BRAND.id, BRAND.name)
            .from(RANKING)
            .join(PRODUCT).on(PRODUCT.id.eq(RANKING.productId), PRODUCT.deletedAt.isNull())
            .join(BRAND).on(BRAND.id.eq(PRODUCT.brandId))
            .where(RANKING.snapshotId.eq(snapshotId))
            .orderBy(RANKING.rankNo.asc())
            .limit(limit)
            .fetch()
            .stream()
            .map(row -> new RankedProduct(
                row.get(RANKING.rankNo),
                row.get(RANKING.productId),
                row.get(RANKING.score),
                row.get(PRODUCT.name),
                row.get(PRODUCT.price).amount(),
                row.get(BRAND.id),
                row.get(BRAND.name)
            ))
            .toList();
    }
}
