package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.domain.ranking.ProductScore;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Step 2 "calculateWeeklyScoresStep" 의 reader·processor·writer (설계 D10). 상품 1개 = chunk item 1개.
 *
 * <ul>
 *   <li>R1: 점수 행이 존재한다 ⇔ Step 2 가 그 상품 점수를 commit 했다. score 는 절대 NULL 이 아니다.</li>
 *   <li>R2: Step 2 는 score 만 쓴다. {@link #UPSERT_SCORE_SQL} 에 ranking 컬럼은 등장하지 않는다.</li>
 *   <li>절대값 upsert: 같은 상품을 다시 처리해도 {@code score = total} 로 수렴한다. {@code score += } 는 쓰지 않는다.</li>
 *   <li>frozen 입력(weekly_ranking_inputs)만 읽는다. product_purchase_activity 는 읽지 않는다.</li>
 *   <li>chunk TX = JobRepository TX (컨텍스트의 JpaTransactionManager, probe 로 확인). chunk commit 과 reader
 *       checkpoint 가 같은 TX 에 저장된다. chunk size 2 와 Step 조립은 WeeklyRankingJobConfig(⑤).</li>
 * </ul>
 */
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Configuration
public class WeeklyScoreStepComponents {

    public static final String STEP_NAME = "calculateWeeklyScoresStep";
    public static final String READER_NAME = "weeklyProductIdReader";

    /** reader: snapshot 안 상품 ID 를 유일·안정 순서로. */
    static final String DISTINCT_PRODUCT_IDS_SQL =
        "SELECT DISTINCT product_id FROM weekly_ranking_inputs WHERE snapshot_id = ? ORDER BY product_id";

    /** processor: frozen 입력에서만 합계. */
    static final String SUM_QUANTITY_SQL =
        "SELECT COALESCE(SUM(quantity), 0) FROM weekly_ranking_inputs WHERE snapshot_id = ? AND product_id = ?";

    /** writer: (snapshot_id, product_id) 유일 키에 절대값 upsert. ranking 은 건드리지 않는다 (R2). */
    static final String UPSERT_SCORE_SQL =
        "INSERT INTO weekly_ranking_scores (snapshot_id, product_id, score, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE score = VALUES(score), updated_at = VALUES(updated_at)";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @StepScope
    @Bean(READER_NAME)
    public JdbcCursorItemReader<Long> weeklyProductIdReader(@Value("#{jobParameters['snapshotId']}") String snapshotId) {
        return new JdbcCursorItemReaderBuilder<Long>()
            .name(READER_NAME)
            .saveState(true)
            .dataSource(dataSource)
            .sql(DISTINCT_PRODUCT_IDS_SQL)
            .preparedStatementSetter(ps -> ps.setString(1, snapshotId))
            .rowMapper((rs, rowNum) -> rs.getLong("product_id"))
            .build();
    }

    @StepScope
    @Bean("weeklyScoreProcessor")
    public ItemProcessor<Long, ProductScore> weeklyScoreProcessor(@Value("#{jobParameters['snapshotId']}") String snapshotId) {
        return productId -> {
            Long total = jdbcTemplate.queryForObject(SUM_QUANTITY_SQL, Long.class, snapshotId, productId);
            if (total == null || total <= 0) {
                // reader 가 돌려준 상품인데 수량이 없다는 것은 입력 계약 위반이다. skip 하지 않고 실패를 드러낸다.
                throw new IllegalStateException(
                    "구매 수량 합이 0 이하입니다. snapshotId=" + snapshotId + ", productId=" + productId + ", sum=" + total);
            }
            return new ProductScore(productId, total);
        };
    }

    @StepScope
    @Bean("weeklyScoreWriter")
    public ItemWriter<ProductScore> weeklyScoreWriter(@Value("#{jobParameters['snapshotId']}") String snapshotId) {
        return (Chunk<? extends ProductScore> chunk) -> {
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            List<Object[]> args = chunk.getItems().stream()
                .map(item -> new Object[] {snapshotId, item.productId(), item.score(), now, now})
                .toList();
            jdbcTemplate.batchUpdate(UPSERT_SCORE_SQL, args);
        };
    }
}
