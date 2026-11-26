package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.domain.ranking.ProductScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.batch.test.StepScopeTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 7절 #3 · R1: chunk commit 직전 실패로 같은 상품을 다시 써도 절대값 upsert 는 `5 → 5` 로 수렴하고 행 수가 늘지 않는다 (설계 D10). */
@Import(RankingTestConfig.class)
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME})
class WeeklyScoreWriterTest {

    private static final LocalDate PERIOD = LocalDate.of(2026, 6, 29);

    @Autowired
    @Qualifier("weeklyScoreWriter")
    private ItemWriter<ProductScore> weeklyScoreWriter;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DisplayName("같은 ProductScore 를 두 번 write 해도 행은 1개, score 는 절대값 5 그대로이며 ranking 은 건드리지 않는다.")
    @Test
    void rewriteSameProduct_keepsScore() throws Exception {
        String snapshotId = RankingTestSupport.snapshotId(PERIOD, 1);
        JobParameters params = RankingTestSupport.params(PERIOD, 1);
        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution(params);
        Chunk<ProductScore> chunk = new Chunk<>(List.of(new ProductScore(7L, 5L)));

        StepScopeTestUtils.doInStepScope(stepExecution, () -> {
            weeklyScoreWriter.write(chunk);
            weeklyScoreWriter.write(chunk);
            return null;
        });

        List<Map<String, Object>> rows = new RankingTestSupport(jdbcTemplate).scores(snapshotId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("product_id")).isEqualTo(7L);
        assertThat(rows.get(0).get("score")).isEqualTo(5L);
        assertThat(rows.get(0).get("ranking")).isNull();
    }
}
