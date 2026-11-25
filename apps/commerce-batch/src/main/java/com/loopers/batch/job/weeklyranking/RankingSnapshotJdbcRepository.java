package com.loopers.batch.job.weeklyranking;

import com.loopers.batch.domain.InputSummary;
import com.loopers.batch.domain.SnapshotStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 입력 고정 step의 SQL (W9 D-3). batch는 JDBC로 쓰고 읽는다.
 * 시각은 모두 UTC 벽시계 값(LocalDateTime)으로 바인딩한다. JVM 기본 시간대(Asia/Seoul)를 거치지 않게 하려는 것이다.
 * 같은 DataSource를 쓰므로 step의 TX 안에서 부르면 같은 연결·같은 TX에서 돈다.
 */
@Component
public class RankingSnapshotJdbcRepository {

    private final JdbcTemplate jdbc;
    private final JdbcTemplate streamingJdbc;

    public RankingSnapshotJdbcRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.streamingJdbc = new JdbcTemplate(dataSource);
        // MySQL Connector/J는 fetchSize가 Integer.MIN_VALUE일 때만 행을 흘려 보낸다. 아니면 결과 전체를 메모리에 올린다.
        this.streamingJdbc.setFetchSize(Integer.MIN_VALUE);
    }

    public String currentIsolation() {
        return jdbc.queryForObject("SELECT @@transaction_isolation", String.class);
    }

    public Optional<InputSummary> findFrozenSummary(String snapshotId) {
        List<InputSummary> found = jdbc.query(
            "SELECT input_count, input_quantity_sum, input_checksum FROM ranking_snapshot WHERE snapshot_id = ?",
            (rs, rowNum) -> new InputSummary(rs.getLong("input_count"), rs.getLong("input_quantity_sum"), rs.getString("input_checksum")),
            snapshotId
        );
        return found.stream().findFirst();
    }

    public long findMaxRevision(LocalDate periodStart) {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(revision), 0) FROM ranking_snapshot WHERE period_start = ?", Long.class, periodStart);
        return max == null ? 0 : max;
    }

    /**
     * 그 주 구매 활동을 한 문장으로 복사한다. 행이 앱으로 오지 않는다.
     */
    public int copyInput(String snapshotId, LocalDateTime fromUtc, LocalDateTime toUtc, LocalDateTime nowUtc) {
        return jdbc.update("""
            INSERT INTO ranking_snapshot_input (snapshot_id, product_id, order_id, quantity, confirmed_at, created_at, updated_at)
            SELECT ?, product_id, order_id, quantity, confirmed_at, ?, ?
              FROM product_purchase_activity
             WHERE confirmed_at >= ? AND confirmed_at < ? AND deleted_at IS NULL
            """, snapshotId, nowUtc, nowUtc, fromUtc, toUtc);
    }

    /**
     * 복사한 입력을 주문 ID → 상품 ID 순으로 한 행씩 흘려 읽어 요약한다. 행을 모아 두지 않는다.
     */
    public InputSummary summarizeInput(String snapshotId) {
        InputSummary.Accumulator accumulator = InputSummary.accumulator();
        streamingJdbc.query("""
            SELECT order_id, product_id, quantity, confirmed_at
              FROM ranking_snapshot_input
             WHERE snapshot_id = ?
             ORDER BY order_id, product_id
            """,
            rs -> {
                accumulator.add(
                    rs.getLong("order_id"),
                    rs.getLong("product_id"),
                    rs.getInt("quantity"),
                    rs.getObject("confirmed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)
                );
            },
            snapshotId);
        return accumulator.finish();
    }

    public void insertFrozenSnapshot(String snapshotId, LocalDate periodStart, long revision, InputSummary summary, LocalDateTime nowUtc) {
        jdbc.update("""
            INSERT INTO ranking_snapshot
                   (snapshot_id, period_start, revision, status, frozen_at, input_count, input_quantity_sum, input_checksum, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            snapshotId, periodStart, revision, SnapshotStatus.FROZEN.name(), nowUtc,
            summary.count(), summary.quantitySum(), summary.checksum(), nowUtc, nowUtc);
    }
}
