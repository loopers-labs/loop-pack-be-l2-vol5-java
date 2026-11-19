package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.WeeklyRankingQueryRepository;
import com.loopers.domain.ranking.WeeklyRankingQueryResult.Item;
import com.loopers.domain.ranking.WeeklyRankingQueryResult.Publication;
import com.loopers.domain.ranking.WeeklyRankingQueryResult.SnapshotSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** batch 가 쓰는 랭킹 테이블을 JDBC 로 읽는다(랭킹 설계 7.8). 날짜·시각은 getObject 로 UTC 필드 그대로 읽는다. */
@Repository
public class WeeklyRankingQueryRepositoryImpl implements WeeklyRankingQueryRepository {

    private final JdbcTemplate jdbc;

    public WeeklyRankingQueryRepositoryImpl(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<Publication> findPublishedSnapshot(LocalDate periodStart) {
        return jdbc.query("SELECT publication.period_start AS pointer_period_start, "
                + "publication.published_snapshot_id AS pointer_snapshot_id, publication.deleted_at AS pointer_deleted_at, "
                + "snapshot.snapshot_id, snapshot.period_start, snapshot.period_end, snapshot.status, "
                + "snapshot.input_fixed_at, snapshot.completed_at, snapshot.result_checksum, "
                + "snapshot.deleted_at AS snapshot_deleted_at "
                + "FROM ranking_publication AS publication "
                + "LEFT JOIN ranking_snapshot AS snapshot ON snapshot.snapshot_id = publication.published_snapshot_id "
                + "WHERE publication.period_start = ?",
            (rs, rowNum) -> new Publication(
                rs.getObject("pointer_period_start", LocalDate.class),
                rs.getString("pointer_snapshot_id"),
                rs.getObject("pointer_deleted_at") != null,
                rs.getString("snapshot_id") == null ? null : new SnapshotSummary(
                    rs.getString("snapshot_id"),
                    rs.getObject("period_start", LocalDate.class),
                    rs.getObject("period_end", LocalDate.class),
                    rs.getString("status"),
                    rs.getObject("input_fixed_at", LocalDateTime.class),
                    rs.getObject("completed_at", LocalDateTime.class),
                    rs.getString("result_checksum"),
                    rs.getObject("snapshot_deleted_at") != null)),
            periodStart).stream().findFirst();
    }

    @Override
    public List<Item> findVisibleItems(String snapshotId, int limit) {
        return jdbc.query("SELECT ranking.product_id, product.name AS product_name, ranking.score, ranking.calculated_rank "
                + "FROM ranking_result AS ranking "
                + "JOIN product AS product ON product.id = ranking.product_id AND product.deleted_at IS NULL "
                + "JOIN brand AS brand ON brand.id = product.brand_id AND brand.deleted_at IS NULL "
                + "WHERE ranking.snapshot_id = ? ORDER BY ranking.calculated_rank ASC LIMIT ?",
            (rs, rowNum) -> new Item(
                rs.getLong("product_id"),
                rs.getString("product_name"),
                rs.getBigDecimal("score").toBigIntegerExact(),
                rs.getLong("calculated_rank")),
            snapshotId, limit);
    }
}
