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

// 완성 랭킹·공개 pointer는 batch SQL 한 벌이 정의한다(D155). api는 JPA 엔티티 없이 SQL로 읽기만 한다.
// pointer → snapshot 정보 → 완성 랭킹 → products(deleted = false)를 한 쿼리로 읽어, pointer와 랭킹 사이에 공개본이 바뀌어도 섞이지 않는다.
// 삭제 상품을 거른 뒤 limit을 건다(D158). 공개본은 있는데 남은 행이 없으면 랭킹 열이 null인 한 행이 온다.
@Component
public class JdbcWeeklyRankingReader implements WeeklyRankingReader {
    private static final String SQL = "select snapshot.snapshot_id, snapshot.frozen_at, ranked.calc_rank, ranked.product_id,"
        + " ranked.name, ranked.price, ranked.brand_id, ranked.brand_name, ranked.score, ranked.stock"
        + " from ranking_publication publication"
        + " join ranking_snapshot snapshot on snapshot.snapshot_id = publication.snapshot_id"
        + " left join (select result.snapshot_id, result.calc_rank, result.product_id, result.score, product.name, product.price,"
        + " product.brand_id, brand.name as brand_name, product.stock from ranking_result result"
        + " join products product on product.id = result.product_id and product.deleted = false"
        + " join brands brand on brand.id = product.brand_id) ranked on ranked.snapshot_id = publication.snapshot_id"
        + " where publication.period_start = ?"
        + " order by ranked.calc_rank"
        + " limit ?";

    private final JdbcTemplate jdbcTemplate;

    public JdbcWeeklyRankingReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PublishedRanking> findPublished(LocalDate periodStart, int limit) {
        List<RankedProduct> products = new ArrayList<RankedProduct>();
        String[] snapshotId = new String[1];
        LocalDateTime[] frozenAt = new LocalDateTime[1];
        jdbcTemplate.query(SQL, resultSet -> {
            snapshotId[0] = resultSet.getString("snapshot_id");
            frozenAt[0] = resultSet.getObject("frozen_at", LocalDateTime.class);
            if (resultSet.getObject("calc_rank") != null) {
                products.add(new RankedProduct(resultSet.getInt("calc_rank"), resultSet.getLong("product_id"),
                    resultSet.getString("name"), resultSet.getLong("price"), resultSet.getLong("brand_id"),
                    resultSet.getString("brand_name"), resultSet.getLong("score"), resultSet.getInt("stock")));
            }
        }, periodStart, limit);
        if (snapshotId[0] == null) {
            return Optional.empty();
        }
        return Optional.of(new PublishedRanking(snapshotId[0], frozenAt[0].toInstant(ZoneOffset.UTC), products));
    }
}
