package com.loopers.job.ranking;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 구매 수량을 Chunk 안에서 상품별로 합산하는 실험용 Job. */
record PurchaseRowScoreExperiment(DataSource dataSource, JobRepository jobRepository,
                                  PlatformTransactionManager transactionManager, JdbcTemplate jdbc) {
    static final String READER_NAME = "purchaseRowReader";

    private record PurchaseRow(long orderId, long productId, long quantity) {}

    void prepareTables() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS exp_purchase_row_score (
              snapshot_id VARCHAR(64) NOT NULL,
              product_id BIGINT NOT NULL,
              score BIGINT NOT NULL,
              PRIMARY KEY (snapshot_id, product_id)
            ) ENGINE=InnoDB
            """);
    }

    Job job(String snapshotId, int chunkSize, boolean failAfterWritingSecondChunk) throws Exception {
        return job(snapshotId, chunkSize, failAfterWritingSecondChunk, false);
    }

    /** forcePrimary면 다음 페이지 SQL이 PK 순서로 읽도록 PRIMARY를 지정한다(실험 15). */
    Job job(String snapshotId, int chunkSize, boolean failAfterWritingSecondChunk, boolean forcePrimary) throws Exception {
        var provider = new MySqlPagingQueryProvider();
        provider.setSelectClause("order_id, product_id, quantity");
        provider.setFromClause(forcePrimary ? "ranking_snapshot_input FORCE INDEX (PRIMARY)" : "ranking_snapshot_input");
        provider.setWhereClause("snapshot_id = :snapshotId");
        var keys = new LinkedHashMap<String, Order>();
        keys.put("order_id", Order.ASCENDING);
        keys.put("product_id", Order.ASCENDING);
        provider.setSortKeys(keys);

        var reader = new JdbcPagingItemReaderBuilder<PurchaseRow>()
            .name(READER_NAME)
            .dataSource(dataSource)
            .queryProvider(provider)
            .parameterValues(Map.of("snapshotId", snapshotId))
            .pageSize(chunkSize)
            .rowMapper((rs, n) -> new PurchaseRow(rs.getLong("order_id"), rs.getLong("product_id"), rs.getLong("quantity")))
            .saveState(true)
            .build();
        reader.afterPropertiesSet();

        var step = new StepBuilder("calculatePurchaseRowScores", jobRepository)
            .<PurchaseRow, PurchaseRow>chunk(chunkSize, transactionManager)
            .reader(reader)
            .writer(chunk -> {
                applyPurchases(snapshotId, chunk.getItems());
                if (failAfterWritingSecondChunk && chunk.getItems().stream().anyMatch(row -> row.productId() == 9L)) {
                    throw new IllegalStateException("두 번째 Chunk를 쓴 뒤 커밋 전 실패");
                }
            })
            .build();
        return new JobBuilder("purchaseRowScoreExperiment", jobRepository).start(step).build();
    }

    private void applyPurchases(String snapshotId, List<? extends PurchaseRow> purchases) {
        var deltas = new TreeMap<Long, Long>();
        for (PurchaseRow row : purchases) {
            deltas.merge(row.productId(), row.quantity(), Long::sum);
        }
        jdbc.batchUpdate("""
            INSERT INTO exp_purchase_row_score (snapshot_id, product_id, score) VALUES (?, ?, ?) AS incoming
            ON DUPLICATE KEY UPDATE score = exp_purchase_row_score.score + incoming.score
            """, deltas.entrySet().stream()
            .map(entry -> new Object[] {snapshotId, entry.getKey(), entry.getValue()})
            .toList());
    }
}
