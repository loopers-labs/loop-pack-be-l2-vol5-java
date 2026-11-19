package com.loopers.interfaces.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RepositorySqlScripts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.List;

@AutoConfigureMockMvc
@SpringBootTest
class WeeklyRankingApiTest {
    private static final String WEEK = "2026-08-17";
    private static final String R1 = "weekly-20260817-r1";
    private static final String R2 = "weekly-20260817-r2";

    @Autowired private MockMvc mvc;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductFixture products;
    @Autowired private BrandFixture brands;
    @Autowired private DatabaseCleanUp cleanUp;

    private Brand brand;

    @BeforeEach
    void prepareRankingTables() {
        List.of("ranking_publication", "ranking_result", "ranking_score_stage", "ranking_snapshot_input",
                "ranking_snapshot").forEach(table -> jdbc.execute("DROP TABLE IF EXISTS " + table));
        RepositorySqlScripts.run(dataSource, "docs/week9/sql/weekly-ranking-tables.sql");
        brand = brands.createBrand("브랜드");
    }

    @AfterEach
    void tearDown() {
        cleanUp.deleteAllEntities();
    }

    @Test
    void 공개된_snapshot의_계산_순위와_현재_상품_정보를_반환한다() throws Exception {
        Product seven = products.createProduct(brand.getId(), "seven", 7_000, 1);
        Product nine = products.createProduct(brand.getId(), "nine", 9_000, 1);
        Product eight = products.createProduct(brand.getId(), "eight", 8_000, 1);
        completed(R1, 1, new long[][] {{seven.getId(), 5}, {nine.getId(), 5}, {eight.getId(), 1}});
        publish(R1, 1);

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", WEEK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.periodStart").value(WEEK))
                .andExpect(jsonPath("$.data.periodEnd").value("2026-08-24"))
                .andExpect(jsonPath("$.data.snapshotId").value(R1))
                .andExpect(jsonPath("$.data.frozenAt").value("2026-08-24T00:10:00Z"))
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[0].rank").value(1))
                .andExpect(jsonPath("$.data.items[0].productId").value(seven.getId()))
                .andExpect(jsonPath("$.data.items[0].score").value(5))
                .andExpect(jsonPath("$.data.items[0].name").value("seven"))
                .andExpect(jsonPath("$.data.items[0].price").value(7_000))
                .andExpect(jsonPath("$.data.items[0].brand.name").value("브랜드"))
                .andExpect(jsonPath("$.data.items[1].productId").value(nine.getId()))
                .andExpect(jsonPath("$.data.items[2].productId").value(eight.getId()));
    }

    @Test
    void pointer가_가리키는_snapshot만_보여주고_계산만_끝난_새_revision은_숨긴다() throws Exception {
        Product first = products.createProduct(brand.getId(), "first", 1_000, 1);
        Product second = products.createProduct(brand.getId(), "second", 1_000, 1);
        completed(R1, 1, new long[][] {{first.getId(), 3}});
        publish(R1, 1);
        completed(R2, 2, new long[][] {{second.getId(), 9}, {first.getId(), 3}});

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", WEEK))
                .andExpect(jsonPath("$.data.snapshotId").value(R1))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].productId").value(first.getId()));

        publish(R2, 2);

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", WEEK))
                .andExpect(jsonPath("$.data.snapshotId").value(R2))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].productId").value(second.getId()));
    }

    @Test
    void 공개본이_없으면_다른_주가_공개돼_있어도_NOT_PUBLISHED와_빈_items를_반환한다() throws Exception {
        Product product = products.createProduct(brand.getId(), "product", 1_000, 1);
        completed("weekly-20260810-r1", 1, "2026-08-10", new long[][] {{product.getId(), 1}});
        jdbc.update("INSERT INTO ranking_publication VALUES ('2026-08-10', 'weekly-20260810-r1', 1, NOW(6))");

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", WEEK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_PUBLISHED"))
                .andExpect(jsonPath("$.data.periodEnd").value("2026-08-24"))
                .andExpect(jsonPath("$.data.snapshotId").doesNotExist())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void 구매가_없던_주의_완성본은_PUBLISHED와_snapshotId_빈_items로_반환한다() throws Exception {
        completed(R1, 1, new long[0][]);
        publish(R1, 1);

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", WEEK))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.snapshotId").value(R1))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void 삭제된_상품과_브랜드는_제외하고_저장된_순위를_유지한_뒤_limit을_적용한다() throws Exception {
        Product first = products.createProduct(brand.getId(), "first", 1_000, 1);
        Product deleted = products.createProduct(brand.getId(), "deleted", 1_000, 1);
        Product third = products.createProduct(brand.getId(), "third", 1_000, 1);
        Brand removedBrand = brands.createBrand("삭제 브랜드");
        Product ofRemovedBrand = products.createProduct(removedBrand.getId(), "hidden", 1_000, 1);
        Product fifth = products.createProduct(brand.getId(), "fifth", 1_000, 1);
        completed(R1, 1, new long[][] {{first.getId(), 9}, {deleted.getId(), 8}, {third.getId(), 7},
                {ofRemovedBrand.getId(), 6}, {fifth.getId(), 5}});
        publish(R1, 1);
        products.deleteProduct(deleted.getId());
        brands.deleteBrand(removedBrand.getId());

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", WEEK).param("limit", "2"))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].rank").value(1))
                .andExpect(jsonPath("$.data.items[1].rank").value(3))
                .andExpect(jsonPath("$.data.items[1].productId").value(third.getId()));
        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", WEEK))
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[2].rank").value(5));
        assertRankingRowsKept(5);
    }

    @Test
    void 공개된_순위의_상품이_모두_삭제되면_PUBLISHED와_빈_items를_반환한다() throws Exception {
        Product product = products.createProduct(brand.getId(), "product", 1_000, 1);
        completed(R1, 1, new long[][] {{product.getId(), 1}});
        publish(R1, 1);
        products.deleteProduct(product.getId());

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", WEEK))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-08-18, 10, INVALID_REQUEST",
        "2026-08-17, 0, INVALID_REQUEST",
        "2026-08-17, 101, INVALID_REQUEST",
        "2026/08/17, 10, Bad Request",
        "2026-08-17, ten, Bad Request"
    })
    void 월요일이_아닌_날짜_형식_오류와_limit_범위_밖은_400이다(String periodStart, String limit, String code)
            throws Exception {
        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", periodStart).param("limit", limit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value(code));
    }

    @Test
    void periodStart가_없으면_400이다() throws Exception {
        mvc.perform(get("/api/v1/rankings/weekly"))
                .andExpect(status().isBadRequest());
    }

    private void completed(String snapshotId, int revision, long[][] productScores) {
        completed(snapshotId, revision, WEEK, productScores);
    }

    private void completed(String snapshotId, int revision, String periodStart, long[][] productScores) {
        jdbc.update("""
                INSERT INTO ranking_snapshot (snapshot_id, period_start, period_end, revision, status, frozen_at,
                                              input_count, product_count, input_checksum)
                VALUES (?, ?, DATE_ADD(?, INTERVAL 7 DAY), ?, 'FROZEN', '2026-08-24 00:10:00', ?, ?, ?)
                """, snapshotId, periodStart, periodStart, revision, productScores.length, productScores.length,
                "0".repeat(64));
        for (int i = 0; i < productScores.length; i++) {
            jdbc.update("INSERT INTO ranking_result (snapshot_id, product_id, score, rank_no) VALUES (?, ?, ?, ?)",
                    snapshotId, productScores[i][0], productScores[i][1], i + 1);
        }
    }

    private void publish(String snapshotId, int revision) {
        jdbc.update("""
                INSERT INTO ranking_publication (period_start, snapshot_id, revision, published_at)
                VALUES (?, ?, ?, NOW(6))
                ON DUPLICATE KEY UPDATE snapshot_id = VALUES(snapshot_id), revision = VALUES(revision)
                """, WEEK, snapshotId, revision);
        jdbc.update("UPDATE ranking_snapshot SET status = 'PUBLISHED' WHERE snapshot_id = ?", snapshotId);
    }

    private void assertRankingRowsKept(int expected) {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM ranking_result WHERE snapshot_id = ?",
                Integer.class, R1);
        assertThat(rows).isEqualTo(expected);
    }
}
