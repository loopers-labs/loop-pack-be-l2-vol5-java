package com.loopers.week9;

import com.loopers.CommerceApiApplication;
import com.loopers.batch.ranking.WeeklyRankingJobConfig;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.loopers.support.http.ApiHttp.customer;
import static com.loopers.support.http.ApiHttp.success;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(
    classes = CommerceApiApplication.class,
    properties = {
        "spring.batch.job.name=weeklyRankingJob",
        "spring.batch.job.enabled=false",
        "spring.batch.jdbc.initialize-schema=always",
        "purchase-activity.kafka.listener.auto-startup=false",
        "demo-kafka.listener.auto-startup=false",
        "weekly-ranking.batch.chunk-size=2"
    }
)
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
@Sql("/week9-ranking-schema.sql")
class WeeklyRankingFlowE2eTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 8, 17);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JobLauncher jobLauncher;
    @Autowired @Qualifier(WeeklyRankingJobConfig.JOB_NAME) Job weeklyRankingJob;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;

    private CommerceFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CommerceFixture(entityManager, transactionManager);
        jdbc.update("delete from weekly_ranking_publication");
        jdbc.update("delete from weekly_ranking_item");
        jdbc.update("delete from weekly_ranking_score");
        jdbc.update("delete from weekly_ranking_snapshot_input");
        jdbc.update("delete from weekly_ranking_snapshot");
        jdbc.update("delete from product_purchase_activity");
    }

    @AfterEach
    void tearDown() {
        fixture.truncateRemainingTables();
    }

    @Test
    void readsPurchaseActivityThroughSnapshotBatchAndWeeklyRankingApi() throws Exception {
        var user = fixture.user();
        var brand = fixture.brand("weekly-ranking-e2e");
        var product7 = fixture.product(brand, "product-7", 100L, 1);
        var product8 = fixture.product(brand, "product-8", 100L, 1);
        var product9 = fixture.product(brand, "product-9", 100L, 1);
        insertPurchase(7001L, product7.getId(), 2, PERIOD_START.atStartOfDay());
        insertPurchase(7002L, product7.getId(), 3, PERIOD_START.plusDays(1).atStartOfDay());
        insertPurchase(7001L, product8.getId(), 1, PERIOD_START.atStartOfDay());
        insertPurchase(7003L, product9.getId(), 5, PERIOD_START.plusDays(2).atStartOfDay());

        String snapshotId = "weekly-ranking-e2e-" + UUID.randomUUID();
        var parameters = new JobParametersBuilder()
            .addString("periodStart", PERIOD_START.toString(), true)
            .addString("snapshotId", snapshotId, true)
            .toJobParameters();
        var execution = jobLauncher.run(weeklyRankingJob, parameters);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        mockMvc.perform(get("/api/v1/rankings/weekly")
                .param("periodStart", PERIOD_START.toString())
                .param("limit", "3")
                .with(customer(user)))
            .andExpect(success(HttpStatus.OK))
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.snapshotId").value(snapshotId))
            .andExpect(jsonPath("$.data.items.length()").value(3))
            .andExpect(jsonPath("$.data.items[0].productId").value(product7.getId()))
            .andExpect(jsonPath("$.data.items[0].score").value(5))
            .andExpect(jsonPath("$.data.items[0].rank").value(1))
            .andExpect(jsonPath("$.data.items[1].productId").value(product9.getId()))
            .andExpect(jsonPath("$.data.items[1].score").value(5))
            .andExpect(jsonPath("$.data.items[1].rank").value(2))
            .andExpect(jsonPath("$.data.items[2].productId").value(product8.getId()))
            .andExpect(jsonPath("$.data.items[2].score").value(1))
            .andExpect(jsonPath("$.data.items[2].rank").value(3));
    }

    private void insertPurchase(long orderId, long productId, int quantity, LocalDateTime confirmedAt) {
        jdbc.update("""
            insert into product_purchase_activity (order_id, product_id, quantity, confirmed_at)
            values (?, ?, ?, ?)
            """, orderId, productId, quantity, Timestamp.valueOf(confirmedAt));
    }
}
