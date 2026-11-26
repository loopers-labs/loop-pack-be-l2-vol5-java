package com.loopers.batch.ranking;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;

public class WeeklyScoreWriter implements ItemWriter<WeeklyScore> {

    private final JdbcTemplate jdbc;
    private final String snapshotId;

    public WeeklyScoreWriter(JdbcTemplate jdbc, String snapshotId) {
        this.jdbc = jdbc;
        this.snapshotId = snapshotId;
    }

    @Override
    public void write(Chunk<? extends WeeklyScore> chunk) {
        jdbc.batchUpdate("""
            insert into weekly_ranking_score (snapshot_id, product_id, score)
            values (?, ?, ?)
            on duplicate key update score = values(score)
            """, chunk.getItems(), chunk.size(), (statement, item) -> {
            statement.setString(1, snapshotId);
            statement.setLong(2, item.productId());
            statement.setLong(3, item.score());
        });
    }
}
