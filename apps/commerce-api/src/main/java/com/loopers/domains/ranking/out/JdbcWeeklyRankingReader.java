package com.loopers.domains.ranking.out;

import com.loopers.domains.ranking.app.port.PublishedRanking;
import com.loopers.domains.ranking.app.port.PublishedRanking.RankedProduct;
import com.loopers.domains.ranking.app.port.WeeklyRankingReader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 완성 랭킹·공개 pointer는 batch SQL 한 벌이 정의한다(D155). api는 JPA 엔티티 없이 SQL로 읽기만 한다.
// 완성 랭킹을 앞에 두고 pointer가 가리키는 snapshot의 행을 PK(snapshot_id, calc_rank)로 읽는다. 정렬 순서가 PK와 같아
// nested loop 계획이면 정렬 없이 삭제 상품(D158)을 거르며 limit개를 채운 곳에서 멈춘다(LEFT JOIN 안쪽 열로 정렬하던 이전 쿼리는 K행을 모두 정렬).
@Component
public class JdbcWeeklyRankingReader implements WeeklyRankingReader {
    private static final String RANKED_SQL = "select snapshot.snapshot_id, snapshot.frozen_at, result.calc_rank,"
        + " result.product_id, product.name, product.price, product.brand_id, brand.name as brand_name, result.score,"
        + " product.stock"
        + " from ranking_result result"
        + " join ranking_snapshot snapshot on snapshot.snapshot_id = result.snapshot_id"
        + " join products product on product.id = result.product_id and product.deleted = false"
        + " join brands brand on brand.id = product.brand_id"
        + " where result.snapshot_id = (select publication.snapshot_id from ranking_publication publication"
        + " where publication.period_start = ?)"
        + " order by result.calc_rank"
        + " limit ?";
    private static final String PUBLISHED_SNAPSHOT_SQL = "select snapshot.snapshot_id, snapshot.frozen_at"
        + " from ranking_publication publication"
        + " join ranking_snapshot snapshot on snapshot.snapshot_id = publication.snapshot_id"
        + " where publication.period_start = ?";

    private final JdbcTemplate jdbcTemplate;

    public JdbcWeeklyRankingReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 0행일 때만 pointer를 다시 읽어 "공개본 없음 / 남은 행 없음"을 가른다. 두 읽기를 한 TX(REPEATABLE READ의 같은 읽기 시점)로
    // 묶는 이유: 옛 pointer로 0행을 읽은 뒤 그 사이 commit된 새 pointer를 다시 읽으면 서로 다른 공개본이 섞인다.
    @Override
    @Transactional(readOnly = true)
    public Optional<PublishedRanking> findPublished(LocalDate periodStart, int limit) {
        List<RankedProduct> products = new ArrayList<RankedProduct>();
        String[] snapshotId = new String[1];
        LocalDateTime[] frozenAt = new LocalDateTime[1];
        jdbcTemplate.query(RANKED_SQL, resultSet -> {
            snapshotId[0] = resultSet.getString("snapshot_id");
            frozenAt[0] = resultSet.getObject("frozen_at", LocalDateTime.class);
            products.add(new RankedProduct(resultSet.getInt("calc_rank"), resultSet.getLong("product_id"),
                resultSet.getString("name"), resultSet.getLong("price"), resultSet.getLong("brand_id"),
                resultSet.getString("brand_name"), resultSet.getLong("score"), resultSet.getInt("stock")));
        }, periodStart, limit);
        if (products.isEmpty()) {
            jdbcTemplate.query(PUBLISHED_SNAPSHOT_SQL, resultSet -> {
                snapshotId[0] = resultSet.getString("snapshot_id");
                frozenAt[0] = resultSet.getObject("frozen_at", LocalDateTime.class);
            }, periodStart);
        }
        if (snapshotId[0] == null) {
            return Optional.empty();
        }
        return Optional.of(new PublishedRanking(snapshotId[0], frozenAt[0].toInstant(ZoneOffset.UTC), products));
    }
}
