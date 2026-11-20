package com.loopers.batch.job.ranking;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 주간 랭킹 테이블의 SQL. 호출한 쪽의 트랜잭션(Step의 트랜잭션 관리자)에 참여한다.
 * 시각 값은 UTC {@link LocalDateTime}으로 주고받아 JVM 시간대의 영향을 받지 않는다.
 */
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Component
public class WeeklyRankingStore {
    public static final String STATUS_FROZEN = "FROZEN";
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 계산 결과를 쓰지 않기로 운영자가 표시한 snapshot. 공개하지 않고, 다음 revision을 막지 않는다. */
    public static final String STATUS_ABANDONED = "ABANDONED";

    private final JdbcTemplate jdbc;

    public WeeklyRankingStore(@Qualifier("mySqlMainDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public record SnapshotManifest(
        String snapshotId, LocalDate periodStart, int revision, String status,
        LocalDateTime frozenAt, int inputCount, int productCount, String inputChecksum, String resultChecksum
    ) {
        public InputSummary inputSummary() {
            return new InputSummary(inputCount, productCount, inputChecksum);
        }
    }

    public record InputSummary(int inputCount, int productCount, String inputChecksum) {}

    /** 기간별 공개 pointer. */
    public record Publication(LocalDate periodStart, String snapshotId, int revision) {}

    public Optional<SnapshotManifest> findSnapshot(String snapshotId) {
        List<SnapshotManifest> found = jdbc.query("""
            SELECT snapshot_id, period_start, revision, status, frozen_at, input_count, product_count,
                   input_checksum, result_checksum
            FROM ranking_snapshot WHERE snapshot_id = ?
            """, (rs, n) -> new SnapshotManifest(
                rs.getString("snapshot_id"),
                rs.getObject("period_start", LocalDate.class),
                rs.getInt("revision"),
                rs.getString("status"),
                rs.getObject("frozen_at", LocalDateTime.class),
                rs.getInt("input_count"),
                rs.getInt("product_count"),
                rs.getString("input_checksum"),
                rs.getString("result_checksum")),
            snapshotId);
        return found.stream().findFirst();
    }

    /** 그 기간에 이미 만든 마지막 revision. 없으면 0. */
    public int lastRevision(LocalDate periodStart) {
        Integer last = jdbc.queryForObject(
            "SELECT MAX(revision) FROM ranking_snapshot WHERE period_start = ?", Integer.class, periodStart);
        return last == null ? 0 : last;
    }

    /** 그 기간에 아직 끝나지 않은(FROZEN) revision이 있는가. 같은 기간의 계산을 하나씩 실행하는 데 쓴다. */
    public boolean hasUnfinishedRevision(LocalDate periodStart) {
        Integer unfinished = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ranking_snapshot WHERE period_start = ? AND status = ?",
            Integer.class, periodStart, STATUS_FROZEN);
        return unfinished != null && unfinished > 0;
    }

    public LocalDateTime currentUtcTime() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
    }

    /**
     * 원본 구매 활동 중 기간 안의 행을 한 문장으로 복사한다. 원본을 읽는 문장이 이것 하나라 그 문장 시점의 행만 들어간다.
     * READ COMMITTED TX에서 부르면 원본을 잠그지 않는다(docs/week9/experiments.md 실험 1).
     */
    public int copyInputs(WeeklySnapshotId snapshotId) {
        return jdbc.update("""
            INSERT INTO ranking_snapshot_input (snapshot_id, order_id, product_id, quantity, confirmed_at)
            SELECT ?, order_id, product_id, quantity, confirmed_at
            FROM product_purchase_activity
            WHERE confirmed_at >= ? AND confirmed_at < ?
            """, snapshotId.value(), snapshotId.startInclusive(), snapshotId.endExclusive());
    }

    /**
     * 복사한 snapshot 입력 중 수량이 0 이하인 행 수. 원본을 다시 읽지 않고 복사본을 보므로, 복사한 행과 확인한 행이 같다.
     */
    public int nonPositiveQuantityRows(String snapshotId) {
        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ranking_snapshot_input WHERE snapshot_id = ? AND quantity <= 0",
            Integer.class, snapshotId);
        return rows == null ? 0 : rows;
    }

    /** 원본이 아니라 복사한 snapshot 입력에서 건수·상품 수·checksum을 계산한다. */
    public InputSummary summarizeInputs(String snapshotId) {
        RowChecksum checksum = new RowChecksum();
        int[] rows = {0};
        jdbc.query("""
            SELECT order_id, product_id, quantity, confirmed_at
            FROM ranking_snapshot_input WHERE snapshot_id = ?
            ORDER BY order_id, product_id
            """, rs -> {
            checksum.addRow(rs.getLong("order_id"), rs.getLong("product_id"), rs.getInt("quantity"),
                RowChecksum.utc(rs.getObject("confirmed_at", LocalDateTime.class)));
            rows[0]++;
        }, snapshotId);
        Integer products = jdbc.queryForObject(
            "SELECT COUNT(DISTINCT product_id) FROM ranking_snapshot_input WHERE snapshot_id = ?",
            Integer.class, snapshotId);
        return new InputSummary(rows[0], products, checksum.hex());
    }

    public void insertSnapshot(WeeklySnapshotId snapshotId, LocalDateTime frozenAt, InputSummary summary) {
        jdbc.update("""
            INSERT INTO ranking_snapshot
              (snapshot_id, period_start, revision, status, frozen_at, input_count, product_count, input_checksum)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, snapshotId.value(), snapshotId.periodStart(), snapshotId.revision(), STATUS_FROZEN, frozenAt,
            summary.inputCount(), summary.productCount(), summary.inputChecksum());
    }

    public long productQuantityTotal(String snapshotId, long productId) {
        // 입력 고정 직후 테이블 통계가 비어 있으면 optimizer가 PRIMARY로 snapshot 전체를 읽었다(실험 13).
        // Reader와 같은 상품 인덱스를 지정해 통계 상태와 관계없이 그 상품의 행만 읽게 한다(실험 15).
        return jdbc.queryForObject("""
            SELECT COALESCE(SUM(quantity), 0) FROM ranking_snapshot_input FORCE INDEX (idx_ranking_snapshot_input_product)
            WHERE snapshot_id = ? AND product_id = ?
            """, Long.class, snapshotId, productId);
    }

    /** 고정 입력의 상품별 전체 점수를 삽입한다. 같은 snapshot의 중복 상품은 PK로 거부한다. */
    public void insertScores(String snapshotId, List<ProductScore> scores) {
        jdbc.batchUpdate("""
            INSERT INTO ranking_score_stage (snapshot_id, product_id, score) VALUES (?, ?, ?)
            """, scores.stream().map(s -> new Object[] {snapshotId, s.productId(), s.score()}).toList());
    }

    public int stageRows(String snapshotId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM ranking_score_stage WHERE snapshot_id = ?", Integer.class, snapshotId);
    }

    public long stageScoreTotal(String snapshotId) {
        return jdbc.queryForObject(
            "SELECT COALESCE(SUM(score), 0) FROM ranking_score_stage WHERE snapshot_id = ?", Long.class, snapshotId);
    }

    public long inputQuantityTotal(String snapshotId) {
        return jdbc.queryForObject(
            "SELECT COALESCE(SUM(quantity), 0) FROM ranking_snapshot_input WHERE snapshot_id = ?", Long.class, snapshotId);
    }

    /** stage 점수에 순위(점수 내림차순, 동점이면 productId 오름차순, 1부터 빈칸 없이)를 매겨 완성 랭킹으로 저장한다. */
    public void insertEntries(String snapshotId) {
        jdbc.update("""
            INSERT INTO ranking_entry (snapshot_id, product_id, score, ranking)
            SELECT snapshot_id, product_id, score, ROW_NUMBER() OVER (ORDER BY score DESC, product_id ASC)
            FROM ranking_score_stage WHERE snapshot_id = ?
            """, snapshotId);
    }

    /** 완성 랭킹을 순위 순서로 {@code productId, score, rank} 직렬화한 checksum. */
    public String resultChecksum(String snapshotId) {
        RowChecksum checksum = new RowChecksum();
        jdbc.query(
            "SELECT product_id, score, ranking FROM ranking_entry WHERE snapshot_id = ? ORDER BY ranking",
            rs -> {
                checksum.addRow(rs.getLong("product_id"), rs.getLong("score"), rs.getInt("ranking"));
            }, snapshotId);
        return checksum.hex();
    }

    /** FROZEN인 snapshot만 COMPLETED로 바꾼다. 바뀐 행 수를 돌려준다. */
    public int markCompleted(String snapshotId, String resultChecksum) {
        return jdbc.update("""
            UPDATE ranking_snapshot SET status = ?, result_checksum = ?, completed_at = UTC_TIMESTAMP(6)
            WHERE snapshot_id = ? AND status = ?
            """, STATUS_COMPLETED, resultChecksum, snapshotId, STATUS_FROZEN);
    }

    /** 기간의 공개 pointer를 읽고 그 행을 TX가 끝날 때까지 잠근다(없으면 그 자리를 잠근다). */
    public Optional<Publication> lockPublication(LocalDate periodStart) {
        return jdbc.query(
            "SELECT period_start, snapshot_id, revision FROM ranking_publication WHERE period_start = ? FOR UPDATE",
            (rs, n) -> new Publication(rs.getObject("period_start", LocalDate.class),
                rs.getString("snapshot_id"), rs.getInt("revision")),
            periodStart).stream().findFirst();
    }

    public void insertPublication(WeeklySnapshotId snapshotId) {
        jdbc.update("""
            INSERT INTO ranking_publication (period_start, snapshot_id, revision, published_at)
            VALUES (?, ?, ?, UTC_TIMESTAMP(6))
            """, snapshotId.periodStart(), snapshotId.value(), snapshotId.revision());
    }

    /** 현재 pointer의 revision이 기대값일 때만 바꾼다. 바뀐 행 수를 돌려준다. */
    public int replacePublication(WeeklySnapshotId snapshotId, int currentRevision) {
        return jdbc.update("""
            UPDATE ranking_publication SET snapshot_id = ?, revision = ?, published_at = UTC_TIMESTAMP(6)
            WHERE period_start = ? AND revision = ?
            """, snapshotId.value(), snapshotId.revision(), snapshotId.periodStart(), currentRevision);
    }

    /** 그 기간의 마지막 revision snapshot. 없으면 비어 있다. */
    public Optional<SnapshotManifest> findLatestSnapshot(LocalDate periodStart) {
        return jdbc.query("SELECT snapshot_id FROM ranking_snapshot WHERE period_start = ? ORDER BY revision DESC LIMIT 1",
                (rs, n) -> rs.getString("snapshot_id"), periodStart)
            .stream().findFirst().flatMap(this::findSnapshot);
    }

    /** 원본에서 그 기간(confirmed_at 기준)에 속한 구매 활동 수. 원본은 추가만 되므로 늘었으면 새 행이 있다. */
    public int sourceRows(WeeklySnapshotId period) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM product_purchase_activity WHERE confirmed_at >= ? AND confirmed_at < ?
            """, Integer.class, period.startInclusive(), period.endExclusive());
    }

    /** 원본에서 그 기간 구매 활동이 마지막으로 저장된 시각(created_at, UTC). 없으면 비어 있다. */
    public Optional<LocalDateTime> lastStoredAt(WeeklySnapshotId period) {
        return Optional.ofNullable(jdbc.queryForObject("""
            SELECT MAX(created_at) FROM product_purchase_activity WHERE confirmed_at >= ? AND confirmed_at < ?
            """, LocalDateTime.class, period.startInclusive(), period.endExclusive()));
    }
}
