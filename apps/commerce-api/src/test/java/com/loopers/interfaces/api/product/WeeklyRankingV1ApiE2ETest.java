package com.loopers.interfaces.api.product;

import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.product.entity.Product;
import com.loopers.domain.product.service.ProductService;
import com.loopers.infrastructure.brand.persistence.BrandJpaRepository;
import com.loopers.infrastructure.product.persistence.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 9주차 설계 6장. 랭킹 테이블은 batch 가 JDBC 로 쓰므로 테스트도 같은 방식으로 채운다 */
@SpringBootTest
@AutoConfigureMockMvc
class WeeklyRankingV1ApiE2ETest {

    private static final String ENDPOINT = "/api/v1/rankings/weekly";
    private static final String WEEK = "2026-08-17";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private Brand brand;

    @BeforeEach
    void setUp() {
        brand = brandJpaRepository.save(new Brand("브랜드", "브랜드 설명"));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @DisplayName("GET /api/v1/rankings/weekly")
    @Nested
    class GetWeeklyRanking {

        @DisplayName("공개본이 있으면, PUBLISHED 와 snapshot 정보 · 계산 순위 순의 점수와 현재 상품 정보를 돌려준다.")
        @Test
        void returnsPublishedRanking() throws Exception {
            Product first = saveProduct("첫째", 1_000L);
            Product second = saveProduct("둘째", 2_000L);
            insertSnapshot("weekly-20260817-r1", WEEK, 1, "PUBLISHED");
            insertScore("weekly-20260817-r1", second, 3, 2);
            insertScore("weekly-20260817-r1", first, 5, 1);

            mvc.perform(get(ENDPOINT).param("periodStart", WEEK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.periodStart").value("2026-08-17"))
                .andExpect(jsonPath("$.data.periodEnd").value("2026-08-24"))
                .andExpect(jsonPath("$.data.snapshotId").value("weekly-20260817-r1"))
                .andExpect(jsonPath("$.data.frozenAt").exists())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].rank").value(1))
                .andExpect(jsonPath("$.data.items[0].score").value(5))
                .andExpect(jsonPath("$.data.items[0].productId").value(first.getId()))
                .andExpect(jsonPath("$.data.items[0].name").value("첫째"))
                .andExpect(jsonPath("$.data.items[0].price").value(1_000))
                .andExpect(jsonPath("$.data.items[0].brand.name").value("브랜드"))
                .andExpect(jsonPath("$.data.items[1].productId").value(second.getId()));
        }

        @DisplayName("계산 중이거나 공개 전이면, 내부 결과를 보여 주지 않고 NOT_PUBLISHED 와 빈 목록을 돌려준다.")
        @Test
        void returnsNotPublished_whenNoPublishedSnapshot() throws Exception {
            Product product = saveProduct("상품", 1_000L);
            insertSnapshot("weekly-20260817-r1", WEEK, 1, "COMPLETED");
            insertScore("weekly-20260817-r1", product, 5, null);

            mvc.perform(get(ENDPOINT).param("periodStart", WEEK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_PUBLISHED"))
                .andExpect(jsonPath("$.data.snapshotId").doesNotExist())
                .andExpect(jsonPath("$.data.frozenAt").doesNotExist())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        }

        @DisplayName("구매가 없던 주의 공개본이면, PUBLISHED 와 snapshotId · 빈 목록을 돌려준다.")
        @Test
        void returnsEmptyPublished_whenWeekHasNoPurchase() throws Exception {
            insertSnapshot("weekly-20260817-r1", WEEK, 1, "PUBLISHED");

            mvc.perform(get(ENDPOINT).param("periodStart", WEEK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.snapshotId").value("weekly-20260817-r1"))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        }

        @DisplayName("삭제된 상품은 빼고 limit 을 적용하며, 계산 순위는 그대로라 빈 순위가 생긴다.")
        @Test
        void excludesDeletedProductsBeforeLimit() throws Exception {
            Product first = saveProduct("첫째", 1_000L);
            Product deleted = saveProduct("삭제", 1_000L);
            Product third = saveProduct("셋째", 1_000L);
            Product fourth = saveProduct("넷째", 1_000L);
            deleted.delete();
            productJpaRepository.save(deleted);
            insertSnapshot("weekly-20260817-r1", WEEK, 1, "PUBLISHED");
            insertScore("weekly-20260817-r1", first, 9, 1);
            insertScore("weekly-20260817-r1", deleted, 8, 2);
            insertScore("weekly-20260817-r1", third, 7, 3);
            insertScore("weekly-20260817-r1", fourth, 6, 4);

            mvc.perform(get(ENDPOINT).param("periodStart", WEEK).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].rank").value(1))
                .andExpect(jsonPath("$.data.items[1].rank").value(3))
                .andExpect(jsonPath("$.data.items[1].productId").value(third.getId()));
        }

        @DisplayName("새 revision 이 계산 중이면 이전 공개본을, 공개 전환 뒤에는 새 공개본을 돌려준다.")
        @Test
        void followsPublishedRevision() throws Exception {
            Product product = saveProduct("상품", 1_000L);
            insertSnapshot("weekly-20260817-r1", WEEK, 1, "PUBLISHED");
            insertScore("weekly-20260817-r1", product, 5, 1);
            insertSnapshot("weekly-20260817-r2", WEEK, 2, "COMPLETED");
            insertScore("weekly-20260817-r2", product, 15, 1);

            mvc.perform(get(ENDPOINT).param("periodStart", WEEK))
                .andExpect(jsonPath("$.data.snapshotId").value("weekly-20260817-r1"))
                .andExpect(jsonPath("$.data.items[0].score").value(5));

            jdbc.update("update weekly_ranking_snapshot set status = 'COMPLETED' where snapshot_id = 'weekly-20260817-r1'");
            jdbc.update("update weekly_ranking_snapshot set status = 'PUBLISHED' where snapshot_id = 'weekly-20260817-r2'");

            mvc.perform(get(ENDPOINT).param("periodStart", WEEK))
                .andExpect(jsonPath("$.data.snapshotId").value("weekly-20260817-r2"))
                .andExpect(jsonPath("$.data.items[0].score").value(15));
        }

        @DisplayName("limit 을 생략하면 10 개까지 돌려준다.")
        @Test
        void limitsToTenByDefault() throws Exception {
            insertSnapshot("weekly-20260817-r1", WEEK, 1, "PUBLISHED");
            for (int rank = 1; rank <= 11; rank++) {
                insertScore("weekly-20260817-r1", saveProduct("상품" + rank, 1_000L), 100 - rank, rank);
            }

            mvc.perform(get(ENDPOINT).param("periodStart", WEEK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(10)));
        }

        @DisplayName("기간 시작이 월요일이 아니면, 400 과 INVALID_RANKING_PERIOD 를 돌려준다.")
        @Test
        void rejectsNonMonday() throws Exception {
            mvc.perform(get(ENDPOINT).param("periodStart", "2026-08-18"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_RANKING_PERIOD"));
        }

        @DisplayName("limit 이 1 ~ 100 밖이면, 기본값으로 바꾸지 않고 400 과 INVALID_RANKING_LIMIT 를 돌려준다.")
        @Test
        void rejectsLimitOutOfRange() throws Exception {
            mvc.perform(get(ENDPOINT).param("periodStart", WEEK).param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_RANKING_LIMIT"));
            mvc.perform(get(ENDPOINT).param("periodStart", WEEK).param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_RANKING_LIMIT"));
        }

        @DisplayName("날짜 형식이 틀리거나 기간을 빠뜨리면, 400 과 BAD_REQUEST 를 돌려준다.")
        @Test
        void rejectsMalformedOrMissingPeriod() throws Exception {
            mvc.perform(get(ENDPOINT).param("periodStart", "2026-13-45"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BAD_REQUEST"));
            mvc.perform(get(ENDPOINT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BAD_REQUEST"));
        }
    }

    private Product saveProduct(String name, long price) {
        return productService.create(brand, name, price);
    }

    private void insertSnapshot(String snapshotId, String periodStart, int revision, String status) {
        jdbc.update("""
            insert into weekly_ranking_snapshot
                (snapshot_id, period_start, revision, status, frozen_at, input_count, input_checksum, created_at, updated_at)
            values (?, ?, ?, ?, '2026-08-24 00:05:00', 0, 'checksum', now(6), now(6))""",
            snapshotId, periodStart, revision, status);
    }

    private void insertScore(String snapshotId, Product product, long score, Integer ranking) {
        jdbc.update("""
            insert into weekly_ranking_score (snapshot_id, product_id, score, ranking, created_at, updated_at)
            values (?, ?, ?, ?, now(6), now(6))""",
            snapshotId, product.getId(), score, ranking);
    }
}
