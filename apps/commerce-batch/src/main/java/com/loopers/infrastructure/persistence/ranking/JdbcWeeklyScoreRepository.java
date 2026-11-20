package com.loopers.infrastructure.persistence.ranking;

import com.loopers.domain.ranking.model.vo.WeeklyPurchaseScore;
import com.loopers.domain.ranking.repository.WeeklyScoreRepository;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcWeeklyScoreRepository implements WeeklyScoreRepository {
    private final JdbcTemplate jdbc;

    public JdbcWeeklyScoreRepository(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public WeeklyPurchaseScore calculate(UUID snapshotId, long productId) {
        return jdbc.queryForObject("""
            SELECT SUM(quantity) AS total,COUNT(*) AS input_count,MIN(quantity) AS minimum
            FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=? AND product_id=?
            """, (row, index) -> WeeklyPurchaseScore.fromAggregate(snapshotId, productId, row.getBigDecimal("total"),
                row.getLong("input_count"), row.getInt("minimum")), snapshotId.toString(), productId);
    }

    @Override
    public void upsert(List<? extends WeeklyPurchaseScore> scores) {
        if (scores.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
            INSERT INTO weekly_ranking_score_stage (snapshot_id,product_id,score) VALUES (?,?,?)
            ON DUPLICATE KEY UPDATE score=?
            """, scores, scores.size(), (statement, score) -> {
                statement.setString(1, score.snapshotId().toString());
                statement.setLong(2, score.productId());
                statement.setLong(3, score.score());
                statement.setLong(4, score.score());
            });
        // Connector/J can return SUCCESS_NO_INFO for rewritten batches; contents define success.
    }
}
