package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.RankingInputManifest;
import com.loopers.domain.ranking.RankingProductScore;
import com.loopers.domain.ranking.RankingScoreRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcRankingScoreRepository implements RankingScoreRepository {

    private final JdbcTemplate jdbc;

    public JdbcRankingScoreRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<RankingInputManifest> findSnapshot(String snapshotId) {
        return jdbc.query(RankingSnapshotRows.SELECT + "WHERE snapshot_id = ?", RankingSnapshotRows.MAPPER, snapshotId)
            .stream().findFirst();
    }

    @Override
    public Optional<BigInteger> sumQuantity(String snapshotId, long productId) {
        BigDecimal sum = jdbc.queryForObject(
            "SELECT SUM(quantity) AS score FROM ranking_snapshot_input WHERE snapshot_id = ? AND product_id = ?",
            BigDecimal.class, snapshotId, productId);
        return Optional.ofNullable(sum).map(BigDecimal::toBigIntegerExact);
    }

    @Override
    public void upsertScores(String snapshotId, List<RankingProductScore> scores) {
        if (scores.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO ranking_score_stage "
                + "(snapshot_id, product_id, score, created_at, updated_at, deleted_at) "
                + "VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL) "
                + "ON DUPLICATE KEY UPDATE score = ?, updated_at = UTC_TIMESTAMP(6)",
            scores.stream().map(score -> {
                BigDecimal value = new BigDecimal(score.score());
                return new Object[]{snapshotId, score.productId(), value, value};
            }).toList());
    }
}
