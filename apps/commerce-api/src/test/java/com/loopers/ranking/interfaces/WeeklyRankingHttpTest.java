package com.loopers.ranking.interfaces;

import com.loopers.brand.domain.Brand;
import com.loopers.product.domain.Product;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

import static com.loopers.support.http.ApiHttp.customer;
import static com.loopers.support.http.ApiHttp.success;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
@Sql("/week9-ranking-schema.sql")
class WeeklyRankingHttpTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 8, 17);
    private static final String SNAPSHOT_ID = "http-weekly-ranking";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;

    private CommerceFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CommerceFixture(entityManager, transactionManager);
        jdbc.update("delete from weekly_ranking_publication");
        jdbc.update("delete from weekly_ranking_item");
        jdbc.update("delete from weekly_ranking_snapshot");
    }

    @AfterEach
    void tearDown() {
        fixture.truncateRemainingTables();
    }

    @Test
    void returnsNotPublishedWhenPeriodHasNoPublishedSnapshot() throws Exception {
        User user = fixture.user();

        mockMvc.perform(get("/api/v1/rankings/weekly")
                .param("periodStart", PERIOD_START.toString())
                .with(customer(user)))
            .andExpect(success(HttpStatus.OK))
            .andExpect(jsonPath("$.data.status").value("NOT_PUBLISHED"))
            .andExpect(jsonPath("$.data.snapshotId").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.inputFixedAt").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void returnsPublishedSnapshotWithEmptyItemsWhenWeekHadNoPurchases() throws Exception {
        User user = fixture.user();
        insertPublishedSnapshot();

        mockMvc.perform(get("/api/v1/rankings/weekly")
                .param("periodStart", PERIOD_START.toString())
                .with(customer(user)))
            .andExpect(success(HttpStatus.OK))
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.snapshotId").value(SNAPSHOT_ID))
            .andExpect(jsonPath("$.data.inputFixedAt").value("2026-08-24T00:05:00Z"))
            .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void excludesDeletedProductsBeforeApplyingLimitAndKeepsStoredRank() throws Exception {
        User user = fixture.user();
        Brand brand = fixture.brand("weekly-ranking");
        Product deleted = fixture.deletedProduct(brand, "deleted", 100L);
        Product active = fixture.product(brand, "active", 200L, 1);
        insertPublishedSnapshot();
        insertItem(deleted.getId(), 99L, 1L);
        insertItem(active.getId(), 10L, 2L);

        mockMvc.perform(get("/api/v1/rankings/weekly")
                .param("periodStart", PERIOD_START.toString())
                .param("limit", "1")
                .with(customer(user)))
            .andExpect(success(HttpStatus.OK))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].productId").value(active.getId()))
            .andExpect(jsonPath("$.data.items[0].productName").value("active"))
            .andExpect(jsonPath("$.data.items[0].score").value(10))
            .andExpect(jsonPath("$.data.items[0].rank").value(2));
    }

    @Test
    void rejectsNonMondayAndOutOfRangeLimit() throws Exception {
        User user = fixture.user();

        mockMvc.perform(get("/api/v1/rankings/weekly")
                .param("periodStart", "2026-08-18")
                .with(customer(user)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/rankings/weekly")
                .param("periodStart", PERIOD_START.toString())
                .param("limit", "101")
                .with(customer(user)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void requiresIdentifiedCustomer() throws Exception {
        mockMvc.perform(get("/api/v1/rankings/weekly").param("periodStart", PERIOD_START.toString()))
            .andExpect(status().isUnauthorized());
    }

    private void insertPublishedSnapshot() {
        jdbc.update("""
            insert into weekly_ranking_snapshot
                (snapshot_id, period_start, period_end, input_fixed_at, input_count)
            values (?, ?, ?, ?, 0)
            """, SNAPSHOT_ID, PERIOD_START, PERIOD_START.plusWeeks(1),
            Timestamp.valueOf(LocalDateTime.of(2026, 8, 24, 0, 5)));
        jdbc.update("insert into weekly_ranking_publication (period_start, snapshot_id) values (?, ?)",
            PERIOD_START, SNAPSHOT_ID);
    }

    private void insertItem(long productId, long score, long rank) {
        jdbc.update("""
            insert into weekly_ranking_item (snapshot_id, product_id, score, rank_no)
            values (?, ?, ?, ?)
            """, SNAPSHOT_ID, productId, score, rank);
    }
}
