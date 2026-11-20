package com.loopers.interfaces.api.ranking;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.product.Price;
import com.loopers.utils.DatabaseCleanUp;

@SpringBootTest
@AutoConfigureMockMvc
@Sql("file:../commerce-batch/src/main/resources/schema/ranking.sql")
class RankingV1ApiE2ETest {

    private static final String WEEKLY = "/api/v1/rankings/weekly";
    private static final String R1 = "weekly-20260817-r1";
    private static final String R2 = "weekly-20260817-r2";

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final DatabaseCleanUp databaseCleanUp;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;

    private Long brandId;
    private Long coat;
    private Long shirt;
    private Long cap;

    @Autowired
    RankingV1ApiE2ETest(
        MockMvc mvc, DataSource dataSource, DatabaseCleanUp databaseCleanUp,
        BrandFacade brandFacade, ProductFacade productFacade
    ) {
        this.mvc = mvc;
        this.jdbc = new JdbcTemplate(dataSource);
        this.databaseCleanUp = databaseCleanUp;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
    }

    @BeforeEach
    void setUp() {
        brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        coat = productFacade.register(brandId, "코트", Price.of(129_000)).getId();
        shirt = productFacade.register(brandId, "셔츠", Price.of(39_000)).getId();
        cap = productFacade.register(brandId, "모자", Price.of(19_000)).getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        jdbc.execute("truncate table weekly_ranking");
        jdbc.execute("truncate table weekly_ranking_publication");
    }

    @Nested
    @DisplayName("요청 검증")
    class RequestValidation {

        @DisplayName("QRY-002: periodStart 가 월요일이 아니면 400 이다.")
        @Test
        void rejectsNonMonday() throws Exception {
            mvc.perform(get(WEEKLY).param("periodStart", "2026-08-18"))
                .andExpect(status().isBadRequest());
        }

        @DisplayName("QRY-002: periodStart 가 yyyy-MM-dd 가 아니면 400 이다.")
        @Test
        void rejectsOtherFormat() throws Exception {
            mvc.perform(get(WEEKLY).param("periodStart", "2026/08/17"))
                .andExpect(status().isBadRequest());
        }

        @DisplayName("QRY-002, Q-5: periodStart 를 생략하면 400 이다.")
        @Test
        void rejectsMissingPeriodStart() throws Exception {
            mvc.perform(get(WEEKLY))
                .andExpect(status().isBadRequest());
        }

        @DisplayName("QRY-003: limit 이 0 이면 400 이다.")
        @Test
        void rejectsZeroLimit() throws Exception {
            mvc.perform(get(WEEKLY).param("periodStart", "2026-08-17").param("limit", "0"))
                .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("공개본 조회")
    class Lookup {

        @DisplayName("QRY-004, QRY-009: 공개 pointer 가 가리키는 snapshot 의 순위를 상품 표시 정보와 함께 순위 순으로 돌려준다.")
        @Test
        void returnsPublishedRanking() throws Exception {
            publish(R1, 1);
            rank(R1, 1, coat, 5);
            rank(R1, 2, cap, 5);
            rank(R1, 3, shirt, 1);
            rank(R2, 1, shirt, 9);

            mvc.perform(get(WEEKLY).param("periodStart", "2026-08-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.periodStart").value("2026-08-17"))
                .andExpect(jsonPath("$.data.periodEnd").value("2026-08-24"))
                .andExpect(jsonPath("$.data.snapshotId").value(R1))
                .andExpect(jsonPath("$.data.frozenAt", startsWith("2026-08-24T01:00")))
                .andExpect(jsonPath("$.data.items[*].rank", contains(1, 2, 3)))
                .andExpect(jsonPath("$.data.items[0].productId").value(coat))
                .andExpect(jsonPath("$.data.items[0].score").value(5))
                .andExpect(jsonPath("$.data.items[0].productName").value("코트"))
                .andExpect(jsonPath("$.data.items[0].brandId").value(brandId))
                .andExpect(jsonPath("$.data.items[0].brandName").value("무신사"))
                .andExpect(jsonPath("$.data.items[0].price").value(129_000));
        }

        @DisplayName("QRY-005: 그 주의 공개 pointer 가 없으면 NOT_PUBLISHED 와 빈 items 다. 다른 주를 대신 돌려주지 않는다.")
        @Test
        void notPublishedWithoutPointer() throws Exception {
            publishFor(LocalDate.of(2026, 8, 10), "weekly-20260810-r1", 1);
            rank("weekly-20260810-r1", 1, coat, 3);

            mvc.perform(get(WEEKLY).param("periodStart", "2026-08-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_PUBLISHED"))
                .andExpect(jsonPath("$.data.snapshotId").doesNotExist())
                .andExpect(jsonPath("$.data.items").isEmpty());
        }

        @DisplayName("QRY-006: 구매가 없던 주의 공개본은 PUBLISHED, 그 snapshotId, 빈 items 다.")
        @Test
        void publishedEmptyWeek() throws Exception {
            publish(R1, 1);

            mvc.perform(get(WEEKLY).param("periodStart", "2026-08-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.snapshotId").value(R1))
                .andExpect(jsonPath("$.data.items").isEmpty());
        }

        @DisplayName("QRY-007: 삭제된 상품은 빼고, 저장된 순위는 다시 매기지 않는다.")
        @Test
        void excludesDeletedProductKeepingStoredRank() throws Exception {
            publish(R1, 1);
            rank(R1, 1, coat, 5);
            rank(R1, 2, cap, 5);
            rank(R1, 3, shirt, 1);
            productFacade.delete(cap);

            mvc.perform(get(WEEKLY).param("periodStart", "2026-08-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].rank", contains(1, 3)));
        }

        @DisplayName("QRY-008: 공개 조건을 먼저 적용하고 limit 을 적용한다.")
        @Test
        void appliesLimitAfterVisibility() throws Exception {
            publish(R1, 1);
            rank(R1, 1, coat, 5);
            rank(R1, 2, cap, 5);
            rank(R1, 3, shirt, 1);
            productFacade.delete(cap);

            mvc.perform(get(WEEKLY).param("periodStart", "2026-08-17").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].rank", contains(1, 3)));
        }
    }

    private void publish(String snapshotId, int revision) {
        publishFor(LocalDate.of(2026, 8, 17), snapshotId, revision);
    }

    private void publishFor(LocalDate periodStart, String snapshotId, int revision) {
        jdbc.update(
            "insert into weekly_ranking_publication (period_start, snapshot_id, revision, published_at, frozen_at) "
                + "values (?, ?, ?, ?, ?)",
            periodStart, snapshotId, revision,
            LocalDateTime.parse("2026-08-24T02:00:00"), LocalDateTime.parse("2026-08-24T01:00:00")
        );
    }

    private void rank(String snapshotId, int rankNo, long productId, long score) {
        jdbc.update(
            "insert into weekly_ranking (snapshot_id, rank_no, product_id, score) values (?, ?, ?, ?)",
            snapshotId, rankNo, productId, score
        );
    }
}
