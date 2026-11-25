package com.loopers.ranking.adapter.in.web;

import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.domain.ProductModel;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/rankings/weekly (W9 D-6). 랭킹 테이블은 commerce-batch가 쓰므로 여기서는 SQL로 넣는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RankingApiE2ETest {

    private static final String ENDPOINT = "/api/v1/rankings/weekly";
    private static final LocalDateTime FROZEN_AT = LocalDateTime.parse("2026-08-24T01:00:00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void 그_주에_공개본이_없으면_NOT_PUBLISHED와_빈_목록을_돌려준다() throws Exception {
        // given: 아무것도 공개되지 않았다

        // when
        ResultActions result = mockMvc.perform(get(ENDPOINT).param("periodStart", "2026-08-17"));

        // then
        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("NOT_PUBLISHED"))
            .andExpect(jsonPath("$.data.periodStart").value("2026-08-17"))
            .andExpect(jsonPath("$.data.periodEnd").value("2026-08-24"))
            .andExpect(jsonPath("$.data.snapshotId").doesNotExist())   // null 필드는 응답에서 빠진다 (JacksonConfig NON_NULL)
            .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void 공개된_주는_순위_순으로_점수와_상품_이름_가격_브랜드를_돌려준다() throws Exception {
        // given: 발제 fixture 결과 — 상품 7 = 5점·1위, 9 = 5점·2위, 8 = 1점·3위
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드1", null));
        ProductModel p7 = productJpaRepository.save(new ProductModel(brand.getId(), "상품7", 9_000, 10));
        ProductModel p8 = productJpaRepository.save(new ProductModel(brand.getId(), "상품8", 12_000, 10));
        ProductModel p9 = productJpaRepository.save(new ProductModel(brand.getId(), "상품9", 30_000, 10));
        publishedSnapshot("2026-08-17", "weekly-20260817-r1", 1);
        ranking("weekly-20260817-r1", p7.getId(), 5, 1);
        ranking("weekly-20260817-r1", p9.getId(), 5, 2);
        ranking("weekly-20260817-r1", p8.getId(), 1, 3);

        // when
        ResultActions result = mockMvc.perform(get(ENDPOINT).param("periodStart", "2026-08-17"));

        // then
        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.snapshotId").value("weekly-20260817-r1"))
            .andExpect(jsonPath("$.data.frozenAt").value("2026-08-24T01:00:00Z"))
            .andExpect(jsonPath("$.data.items.length()").value(3))
            .andExpect(jsonPath("$.data.items[0].rank").value(1))
            .andExpect(jsonPath("$.data.items[0].productId").value(p7.getId()))
            .andExpect(jsonPath("$.data.items[0].score").value(5))
            .andExpect(jsonPath("$.data.items[0].name").value("상품7"))
            .andExpect(jsonPath("$.data.items[0].price").value(9_000))
            .andExpect(jsonPath("$.data.items[0].brand.id").value(brand.getId()))
            .andExpect(jsonPath("$.data.items[0].brand.name").value("브랜드1"))
            .andExpect(jsonPath("$.data.items[0].likeCount").doesNotExist())
            .andExpect(jsonPath("$.data.items[1].productId").value(p9.getId()))
            .andExpect(jsonPath("$.data.items[2].productId").value(p8.getId()));
    }

    @Test
    void 구매가_0건인_완성본은_PUBLISHED와_빈_목록을_돌려준다() throws Exception {
        // given
        publishedSnapshot("2026-08-17", "weekly-20260817-r1", 1);

        // when
        ResultActions result = mockMvc.perform(get(ENDPOINT).param("periodStart", "2026-08-17"));

        // then
        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.snapshotId").value("weekly-20260817-r1"))
            .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void 삭제된_상품은_빼고_저장된_순위_번호는_그대로_둔다() throws Exception {
        // given: 2위 상품이 삭제됐다
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드1", null));
        ProductModel first = productJpaRepository.save(new ProductModel(brand.getId(), "1위", 1_000, 10));
        ProductModel deleted = productJpaRepository.save(new ProductModel(brand.getId(), "2위", 1_000, 10));
        ProductModel third = productJpaRepository.save(new ProductModel(brand.getId(), "3위", 1_000, 10));
        deleted.delete();
        productJpaRepository.save(deleted);
        publishedSnapshot("2026-08-17", "weekly-20260817-r1", 1);
        ranking("weekly-20260817-r1", first.getId(), 9, 1);
        ranking("weekly-20260817-r1", deleted.getId(), 8, 2);
        ranking("weekly-20260817-r1", third.getId(), 7, 3);

        // when
        ResultActions result = mockMvc.perform(get(ENDPOINT).param("periodStart", "2026-08-17"));

        // then: 1위, 3위 (2위 번호가 빈다)
        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].rank").value(1))
            .andExpect(jsonPath("$.data.items[1].rank").value(3))
            .andExpect(jsonPath("$.data.items[1].productId").value(third.getId()));
    }

    @Test
    void limit은_삭제된_상품을_뺀_뒤에_적용한다() throws Exception {
        // given: 1위 상품이 삭제됐다
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드1", null));
        ProductModel deleted = productJpaRepository.save(new ProductModel(brand.getId(), "1위", 1_000, 10));
        ProductModel second = productJpaRepository.save(new ProductModel(brand.getId(), "2위", 1_000, 10));
        ProductModel third = productJpaRepository.save(new ProductModel(brand.getId(), "3위", 1_000, 10));
        deleted.delete();
        productJpaRepository.save(deleted);
        publishedSnapshot("2026-08-17", "weekly-20260817-r1", 1);
        ranking("weekly-20260817-r1", deleted.getId(), 9, 1);
        ranking("weekly-20260817-r1", second.getId(), 8, 2);
        ranking("weekly-20260817-r1", third.getId(), 7, 3);

        // when
        ResultActions result = mockMvc.perform(get(ENDPOINT).param("periodStart", "2026-08-17").param("limit", "1"));

        // then: 빈 목록이 아니라 남은 상품 중 첫 번째(2위)
        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].rank").value(2))
            .andExpect(jsonPath("$.data.items[0].productId").value(second.getId()));
    }

    @Test
    void 계산_중인_새_revision은_공개_전까지_보이지_않고_공개본을_그대로_보여_준다() throws Exception {
        // given: r1 공개, r2는 점수만 일부 계산된 상태(순위 없음, FROZEN)
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드1", null));
        ProductModel p7 = productJpaRepository.save(new ProductModel(brand.getId(), "상품7", 9_000, 10));
        ProductModel p8 = productJpaRepository.save(new ProductModel(brand.getId(), "상품8", 12_000, 10));
        publishedSnapshot("2026-08-17", "weekly-20260817-r1", 1);
        ranking("weekly-20260817-r1", p7.getId(), 5, 1);
        snapshot("2026-08-17", "weekly-20260817-r2", 2, "FROZEN");
        ranking("weekly-20260817-r2", p8.getId(), 11, null);

        // when
        ResultActions result = mockMvc.perform(get(ENDPOINT).param("periodStart", "2026-08-17"));

        // then
        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.data.snapshotId").value("weekly-20260817-r1"))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].productId").value(p7.getId()));
    }

    @Test
    void 다른_주의_공개본을_대신_내주지_않는다() throws Exception {
        // given: 2026-08-10 주만 공개됐다
        publishedSnapshot("2026-08-10", "weekly-20260810-r1", 1);

        // when
        ResultActions result = mockMvc.perform(get(ENDPOINT).param("periodStart", "2026-08-17"));

        // then
        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("NOT_PUBLISHED"))
            .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @ParameterizedTest(name = "periodStart={0}, limit={1} → 400")
    @CsvSource(value = {
        "NONE, NONE",
        "2026-08-18, NONE",
        "2026/08/17, NONE",
        "2026-08-17, 0",
        "2026-08-17, 101"
    }, nullValues = "NONE")
    void 입력이_범위_밖이면_400이다(String periodStart, String limit) throws Exception {
        // given: periodStart 없음 / 화요일 / 형식 오류 / limit 0 / limit 101
        var request = get(ENDPOINT);
        if (periodStart != null) {
            request.param("periodStart", periodStart);
        }
        if (limit != null) {
            request.param("limit", limit);
        }

        // when
        ResultActions result = mockMvc.perform(request);

        // then
        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.meta.errorCode").value("Bad Request"));
    }

    private void publishedSnapshot(String periodStart, String snapshotId, long revision) {
        snapshot(periodStart, snapshotId, revision, "COMPLETED");
        jdbc.update("""
            INSERT INTO ranking_publication (period_start, snapshot_id, revision, published_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, periodStart, snapshotId, revision, FROZEN_AT, FROZEN_AT, FROZEN_AT);
    }

    private void snapshot(String periodStart, String snapshotId, long revision, String status) {
        jdbc.update("""
            INSERT INTO ranking_snapshot (snapshot_id, period_start, revision, status, frozen_at, input_count, input_quantity_sum, input_checksum, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 0, 0, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', ?, ?)
            """, snapshotId, periodStart, revision, status, FROZEN_AT, FROZEN_AT, FROZEN_AT);
    }

    private void ranking(String snapshotId, Long productId, long score, Integer rank) {
        jdbc.update("""
            INSERT INTO weekly_ranking (snapshot_id, product_id, score, rank_no, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, snapshotId, productId, score, rank, FROZEN_AT, FROZEN_AT);
    }
}
