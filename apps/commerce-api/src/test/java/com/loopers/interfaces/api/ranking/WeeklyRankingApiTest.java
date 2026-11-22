package com.loopers.interfaces.api.ranking;

import com.loopers.domain.brand.BrandService;
import com.loopers.application.product.ProductFacade;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주간 랭킹 API는 공개 pointer가 가리키는 완성본만 읽는다. 랭킹 테이블은 commerce-batch의 스키마 파일로 만들고,
 * 배치가 남기는 행을 직접 넣는다. 테스트마다 다른 주를 써서 랭킹 행을 지우지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WeeklyRankingApiTest {
    private static final String BATCH_SCHEMA = "../commerce-batch/src/main/resources/schema/weekly-ranking.sql";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private BrandService brands;

    /** 좋아요 수 카운터까지 만드는 실제 상품 생성 경로를 쓴다. */
    @Autowired
    private ProductFacade products;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatabaseCleanUp cleanUp;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        new ResourceDatabasePopulator(new FileSystemResource(BATCH_SCHEMA)).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void tearDown() {
        cleanUp.truncateAllTables();  // 상품·브랜드 등 엔티티 테이블만 비운다
    }

    @DisplayName("공개본이 없는 주는 NOT_PUBLISHED와 빈 items이며 다른 주의 결과를 대신 보여 주지 않는다.")
    @Test
    void notPublishedWeek() throws Exception {
        publishSnapshot("2026-06-01", "weekly-20260601-r1", 1);  // 앞 주에는 공개본이 있다

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", "2026-06-08"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("NOT_PUBLISHED"))
            .andExpect(jsonPath("$.data.periodStart").value("2026-06-08"))
            .andExpect(jsonPath("$.data.periodEnd").value("2026-06-15"))
            .andExpect(jsonPath("$.data.snapshotId").doesNotExist())
            .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @DisplayName("구매가 없던 주의 완성본은 PUBLISHED와 snapshotId, 빈 items다.")
    @Test
    void publishedEmptyWeek() throws Exception {
        publishSnapshot("2026-06-22", "weekly-20260622-r1", 1);

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", "2026-06-22"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.snapshotId").value("weekly-20260622-r1"))
            .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @DisplayName("완성본의 계산 순위·점수와 현재 상품 표시 정보, 기간·snapshotId·입력 고정 시점을 준다.")
    @Test
    void publishedRanking() throws Exception {
        long brandId = brands.create("나이키", "설명").getId();
        long p7 = products.create(brandId, "상품7", 7_000, 10).id();
        long p8 = products.create(brandId, "상품8", 8_000, 10).id();
        long p9 = products.create(brandId, "상품9", 9_000, 10).id();
        publishSnapshot("2026-07-20", "weekly-20260720-r1", 1, entry(p7, 5, 1), entry(p9, 5, 2), entry(p8, 1, 3));

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", "2026-07-20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.periodStart").value("2026-07-20"))
            .andExpect(jsonPath("$.data.periodEnd").value("2026-07-27"))
            .andExpect(jsonPath("$.data.snapshotId").value("weekly-20260720-r1"))
            .andExpect(jsonPath("$.data.revision").value(1))
            .andExpect(jsonPath("$.data.inputFrozenAt").value("2026-07-27T00:30:00Z"))
            .andExpect(jsonPath("$.data.items", hasSize(3)))
            .andExpect(jsonPath("$.data.items[0].productId").value(p7))
            .andExpect(jsonPath("$.data.items[0].rank").value(1))
            .andExpect(jsonPath("$.data.items[0].score").value(5))
            .andExpect(jsonPath("$.data.items[0].productName").value("상품7"))
            .andExpect(jsonPath("$.data.items[0].brandName").value("나이키"))
            .andExpect(jsonPath("$.data.items[1].productId").value(p9))
            .andExpect(jsonPath("$.data.items[1].rank").value(2))
            .andExpect(jsonPath("$.data.items[2].productId").value(p8))
            .andExpect(jsonPath("$.data.items[2].rank").value(3));
    }

    @DisplayName("삭제 상품·삭제 브랜드 상품은 빼고, 계산 순위는 그대로 두며(빈칸 허용), 뺀 뒤에 limit을 채운다.")
    @Test
    void excludesHiddenProductsBeforeLimit() throws Exception {
        long brandId = brands.create("나이키", "설명").getId();
        long hiddenBrandId = brands.create("숨김", "설명").getId();
        long p1 = products.create(brandId, "상품1", 1_000, 10).id();
        long deleted = products.create(brandId, "삭제상품", 1_000, 10).id();
        long ofHiddenBrand = products.create(hiddenBrandId, "숨김브랜드상품", 1_000, 10).id();
        long p4 = products.create(brandId, "상품4", 1_000, 10).id();
        publishSnapshot("2026-08-03", "weekly-20260803-r1", 1,
            entry(p1, 9, 1), entry(deleted, 8, 2), entry(ofHiddenBrand, 7, 3), entry(p4, 6, 4));
        products.delete(deleted);  // 고객 화면의 상품 삭제 경로
        brands.delete(hiddenBrandId);  // 브랜드만 삭제된 상태로 브랜드 공개 조건을 따로 확인한다

        // limit 2: 첫 두 순위 중 하나만 보이므로 다음 순위까지 읽어 채운다.
        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", "2026-08-03").param("limit", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", hasSize(2)))
            .andExpect(jsonPath("$.data.items[0].productId").value(p1))
            .andExpect(jsonPath("$.data.items[0].rank").value(1))
            .andExpect(jsonPath("$.data.items[1].productId").value(p4))
            .andExpect(jsonPath("$.data.items[1].rank").value(4));

        // 내부 계산 사실은 지우지 않는다.
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM ranking_entry WHERE snapshot_id = 'weekly-20260803-r1'", Integer.class)).isEqualTo(4);
    }

    @DisplayName("새 revision의 완성 랭킹·stage가 있어도 pointer를 바꾸기 전에는 이전 revision을, 바꾼 뒤에는 새 revision을 보여 준다.")
    @Test
    void followsPublicationPointer() throws Exception {
        long brandId = brands.create("나이키", "설명").getId();
        long p1 = products.create(brandId, "상품1", 1_000, 10).id();
        long p2 = products.create(brandId, "상품2", 1_000, 10).id();
        publishSnapshot("2026-08-10", "weekly-20260810-r1", 1, entry(p1, 3, 1));
        // r2: 늦은 구매로 순위가 바뀐 완성본과 stage가 만들어졌지만 아직 공개 전
        insertSnapshot("weekly-20260810-r2", "2026-08-10", 2, entry(p2, 4, 1), entry(p1, 3, 2));
        jdbc.update("INSERT INTO ranking_score_stage VALUES ('weekly-20260810-r2', ?, 4)", p2);

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", "2026-08-10"))
            .andExpect(jsonPath("$.data.snapshotId").value("weekly-20260810-r1"))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].productId").value(p1));

        jdbc.update("""
            UPDATE ranking_publication SET snapshot_id = 'weekly-20260810-r2', revision = 2
            WHERE period_start = '2026-08-10' AND revision = 1
            """);

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", "2026-08-10"))
            .andExpect(jsonPath("$.data.snapshotId").value("weekly-20260810-r2"))
            .andExpect(jsonPath("$.data.revision").value(2))
            .andExpect(jsonPath("$.data.items[0].productId").value(p2))
            .andExpect(jsonPath("$.data.items[1].productId").value(p1));
    }

    @DisplayName("periodStart는 월요일 yyyy-MM-dd가 필수이고, limit은 1~100이다.")
    @Test
    void validatesRequest() throws Exception {
        mvc.perform(get("/api/v1/rankings/weekly")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", "2026-08-18")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", "20260817")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", "2026-08-17").param("limit", "0"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", "2026-08-17").param("limit", "101"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", "2026-08-17").param("limit", "abc"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", "2026-08-17").param("limit", "100"))
            .andExpect(status().isOk());
    }

    private record Entry(long productId, long score, int rank) {}

    private static Entry entry(long productId, long score, int rank) {
        return new Entry(productId, score, rank);
    }

    /** 배치의 공개 단계가 남기는 것: COMPLETED snapshot 정보, 완성 랭킹, 공개 pointer. */
    private void publishSnapshot(String periodStart, String snapshotId, int revision, Entry... entries) {
        insertSnapshot(snapshotId, periodStart, revision, entries);
        jdbc.update("""
            INSERT INTO ranking_publication (period_start, snapshot_id, revision, published_at)
            VALUES (?, ?, ?, UTC_TIMESTAMP(6))
            """, periodStart, snapshotId, revision);
    }

    private void insertSnapshot(String snapshotId, String periodStart, int revision, Entry... entries) {
        // 입력 고정 시점: 기간이 끝난 다음 월요일 00:30 UTC
        String frozenAt = LocalDate.parse(periodStart).plusWeeks(1) + " 00:30:00";
        jdbc.update("""
            INSERT INTO ranking_snapshot (snapshot_id, period_start, revision, status, frozen_at,
              input_count, product_count, input_checksum, result_checksum, completed_at)
            VALUES (?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
            """, snapshotId, periodStart, revision, frozenAt, entries.length, entries.length, "0".repeat(64), "0".repeat(64));
        for (Entry e : entries) {
            jdbc.update("INSERT INTO ranking_entry (snapshot_id, product_id, score, ranking) VALUES (?, ?, ?, ?)",
                snapshotId, e.productId(), e.score(), e.rank());
        }
    }
}
