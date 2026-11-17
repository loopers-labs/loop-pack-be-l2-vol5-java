package com.loopers.batch.job.ranking;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

// 주간 랭킹 snapshot의 JDBC 접근. 원본은 streamer 소유 product_purchase_activity를 JDBC로 읽는다(D75 범위 확장).
// snapshot 입력을 UPDATE·DELETE하는 메서드는 두지 않는다(D148).
public class WeeklyRankingRepository {
    private final JdbcTemplate jdbcTemplate;

    public WeeklyRankingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<SnapshotInfo> findInfo(String snapshotId) {
        return Optional.empty();
    }

    public List<SourcePurchase> readSource(LocalDate periodStart, LocalDate periodEnd) {
        return List.of();
    }

    public void insertInputs(String snapshotId, List<SourcePurchase> rows) {
    }

    public void insertInfo(SnapshotInfo info) {
    }

    public long scoreOf(String snapshotId, long productId) {
        return 0;
    }

    public void upsertScores(List<ProductScore> scores) {
    }
}
