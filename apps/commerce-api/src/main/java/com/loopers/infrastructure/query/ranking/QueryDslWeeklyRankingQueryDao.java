package com.loopers.infrastructure.query.ranking;

import com.loopers.application.mall.query.BrandSummaryView;
import com.loopers.application.ranking.query.WeeklyRankingCriteria;
import com.loopers.application.ranking.query.WeeklyRankingQueryDao;
import com.loopers.application.ranking.query.WeeklyRankingView;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanPath;
import com.querydsl.core.types.dsl.DatePath;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberPath;
import com.querydsl.core.types.dsl.StringPath;
import com.querydsl.core.types.dsl.StringTemplate;
import com.querydsl.jpa.sql.JPASQLQuery;
import com.querydsl.sql.MySQLTemplates;
import com.querydsl.sql.RelationalPathBase;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class QueryDslWeeklyRankingQueryDao implements WeeklyRankingQueryDao {
    private static final RelationalPathBase<Tuple> PUBLICATION = table("publication", "weekly_ranking_publications");
    private static final RelationalPathBase<Tuple> SNAPSHOT = table("snapshot", "weekly_ranking_snapshots");
    private static final RelationalPathBase<Tuple> RANKING = table("ranking", "weekly_rankings");
    private static final RelationalPathBase<Tuple> PRODUCT = table("product", "products");
    private static final RelationalPathBase<Tuple> BRAND = table("brand", "brands");

    private static final DatePath<LocalDate> PERIOD_START = Expressions.datePath(LocalDate.class, PUBLICATION, "period_start");
    private static final StringPath PUBLICATION_SNAPSHOT_ID = Expressions.stringPath(PUBLICATION, "snapshot_id");
    private static final StringPath SNAPSHOT_ID = Expressions.stringPath(SNAPSHOT, "snapshot_id");
    // Batch writes UTC into DATETIME(6). A string projection preserves it across JDBC time zones.
    private static final StringTemplate FROZEN_AT =
        Expressions.stringTemplate("cast({0} as char)", Expressions.stringPath(SNAPSHOT, "frozen_at"));
    private static final StringPath RANKING_SNAPSHOT_ID = Expressions.stringPath(RANKING, "snapshot_id");
    private static final NumberPath<Long> RANKING_PRODUCT_ID = Expressions.numberPath(Long.class, RANKING, "product_id");
    private static final NumberPath<Long> SCORE = Expressions.numberPath(Long.class, RANKING, "score");
    private static final NumberPath<Long> RANK = Expressions.numberPath(Long.class, RANKING, "ranking_position");
    private static final NumberPath<Long> PRODUCT_ID = Expressions.numberPath(Long.class, PRODUCT, "id");
    private static final NumberPath<Long> PRODUCT_BRAND_ID = Expressions.numberPath(Long.class, PRODUCT, "brand_id");
    private static final StringPath PRODUCT_NAME = Expressions.stringPath(PRODUCT, "name");
    private static final NumberPath<Long> PRICE = Expressions.numberPath(Long.class, PRODUCT, "price");
    private static final NumberPath<Long> LIKE_COUNT = Expressions.numberPath(Long.class, PRODUCT, "like_count");
    private static final BooleanPath PRODUCT_DELETED = Expressions.booleanPath(PRODUCT, "deleted");
    private static final NumberPath<Long> BRAND_ID = Expressions.numberPath(Long.class, BRAND, "id");
    private static final StringPath BRAND_NAME = Expressions.stringPath(BRAND, "name");
    private static final BooleanPath BRAND_DELETED = Expressions.booleanPath(BRAND, "deleted");

    private final EntityManager entityManager;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<WeeklyRankingView> findPublished(WeeklyRankingCriteria criteria) {
        // 공개 pointer의 외래 키가 요청 주차의 완성 snapshot을 가리키는지 보장한다.
        Tuple snapshot = new JPASQLQuery<Void>(entityManager, MySQLTemplates.DEFAULT).select(SNAPSHOT_ID, FROZEN_AT)
            .from(PUBLICATION)
            .join(SNAPSHOT).on(SNAPSHOT_ID.eq(PUBLICATION_SNAPSHOT_ID))
            .where(PERIOD_START.eq(criteria.periodStart()))
            .fetchOne();
        if (snapshot == null) {
            return Optional.empty();
        }

        String snapshotId = snapshot.get(SNAPSHOT_ID);
        var frozenAt = Instant.parse(snapshot.get(FROZEN_AT).replace(' ', 'T') + "Z");
        return Optional.of(new WeeklyRankingView(UUID.fromString(snapshotId), frozenAt, findItems(snapshotId, criteria.limit())));
    }

    private List<WeeklyRankingView.Item> findItems(String snapshotId, int limit) {
        return new JPASQLQuery<Void>(entityManager, MySQLTemplates.DEFAULT)
            .select(PRODUCT_ID, PRODUCT_NAME, PRICE, BRAND_ID, BRAND_NAME, LIKE_COUNT, SCORE, RANK)
            .from(RANKING)
            .join(PRODUCT).on(PRODUCT_ID.eq(RANKING_PRODUCT_ID))
            .join(BRAND).on(BRAND_ID.eq(PRODUCT_BRAND_ID))
            .where(RANKING_SNAPSHOT_ID.eq(snapshotId), PRODUCT_DELETED.isFalse(), BRAND_DELETED.isFalse())
            .orderBy(RANK.asc())
            .limit(limit)
            .fetch()
            .stream()
            .map(row -> new WeeklyRankingView.Item(row.get(PRODUCT_ID), row.get(PRODUCT_NAME), row.get(PRICE),
                new BrandSummaryView(row.get(BRAND_ID), row.get(BRAND_NAME)), row.get(LIKE_COUNT), row.get(SCORE), row.get(RANK)))
            .toList();
    }

    private static RelationalPathBase<Tuple> table(String alias, String name) {
        return new RelationalPathBase<>(Tuple.class, alias, null, name);
    }
}
