package com.loopers.batch.job.ranking.step;

import java.util.Map;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class ScoreStageWriter implements ItemWriter<Long> {

    private final NamedParameterJdbcTemplate jdbc;
    private final String snapshotId;

    public ScoreStageWriter(NamedParameterJdbcTemplate jdbc, String snapshotId) {
        this.jdbc = jdbc;
        this.snapshotId = snapshotId;
    }

    @Override
    public void write(Chunk<? extends Long> productIds) {
        jdbc.update("""
            insert into ranking_score_stage (snapshot_id, product_id, score)
            select * from (
              select snapshot_id, product_id, sum(quantity) as total
              from ranking_snapshot_input
              where snapshot_id = :snapshotId and product_id in (:productIds)
              group by snapshot_id, product_id
            ) as scored
            on duplicate key update score = scored.total
            """, Map.of("snapshotId", snapshotId, "productIds", productIds.getItems()));
    }
}
