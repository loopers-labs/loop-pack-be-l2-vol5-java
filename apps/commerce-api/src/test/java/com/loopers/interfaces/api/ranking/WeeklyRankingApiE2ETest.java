package com.loopers.interfaces.api.ranking;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.fixture.ExposedProducts;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** batch 가 공개한 랭킹 테이블을 SQL 로 준비하고, 공개 pointer 기준 조회 계약을 HTTP 로 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WeeklyRankingApiE2ETest {

    private static final String WEEKLY = "/api/v1/rankings/weekly";

    @Autowired
    private TestRestTemplate testRestTemplate;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ExposedProducts exposedProducts;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("공개 pointer 의 snapshot 만 읽고, 삭제 상품을 뺀 뒤 limit 을 적용하며 저장된 계산 순위를 그대로 보여 준다.")
    @Test
    void returnsPublishedSnapshot_excludingDeletedProducts_withStoredRanks() {
        // arrange: r1(이전 공개본)과 r2(현재 공개본). r2 의 2위 상품은 삭제됐다
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Brand brand = brandRepository.save(Brand.create("루퍼스"));
        Product first = exposedProducts.save(Product.create(brand.getId(), "운동화", new Money(1_000), new Stock(10)));
        Product deleted = deletedProduct(brand, "단종 상품");
        Product third = exposedProducts.save(Product.create(brand.getId(), "모자", new Money(3_000), new Stock(10)));
        Product fourth = exposedProducts.save(Product.create(brand.getId(), "양말", new Money(500), new Stock(10)));
        insertSnapshot(jdbc, "weekly-20260817-r1", "2026-08-17", 1);
        insertRank(jdbc, "weekly-20260817-r1", fourth.getId(), 9, 1);
        insertSnapshot(jdbc, "weekly-20260817-r2", "2026-08-17", 2);
        insertRank(jdbc, "weekly-20260817-r2", first.getId(), 5, 1);
        insertRank(jdbc, "weekly-20260817-r2", deleted.getId(), 4, 2);
        insertRank(jdbc, "weekly-20260817-r2", third.getId(), 3, 3);
        insertRank(jdbc, "weekly-20260817-r2", fourth.getId(), 1, 4);
        insertPublication(jdbc, "2026-08-17", 2, "weekly-20260817-r2", 2);

        // act
        Map<String, Object> data = dataOf(get(WEEKLY + "?periodStart=2026-08-17&limit=2"));

        // assert
        assertThat(data).containsEntry("status", "PUBLISHED")
            .containsEntry("periodStart", "2026-08-17")
            .containsEntry("periodEnd", "2026-08-24")
            .containsEntry("snapshotId", "weekly-20260817-r2")
            .containsEntry("revision", 2);
        assertThat(ZonedDateTime.parse((String) data.get("frozenAt")).toInstant())
            .isEqualTo(Instant.parse("2026-08-24T01:00:00Z"));
        assertThat(data.get("items")).isEqualTo(List.of(
            Map.of("rank", 1, "productId", first.getId().intValue(), "name", "운동화", "price", 1000,
                "brand", Map.of("id", brand.getId().intValue(), "name", "루퍼스"), "score", 5),
            Map.of("rank", 3, "productId", third.getId().intValue(), "name", "모자", "price", 3000,
                "brand", Map.of("id", brand.getId().intValue(), "name", "루퍼스"), "score", 3)
        ));
    }

    @DisplayName("요청한 주에 공개본이 없으면 다른 주의 공개본이 있어도 NOT_PUBLISHED 와 빈 items 이고 snapshot 정보가 없다.")
    @Test
    void returnsNotPublished_whenRequestedWeekHasNoPublication() {
        // arrange: 요청 주는 입력만 고정되고 아직 공개 전, 이전 주는 공개됨
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertSnapshot(jdbc, "weekly-20260817-r1", "2026-08-17", 1);
        insertPublication(jdbc, "2026-08-17", 1, null, null);
        insertSnapshot(jdbc, "weekly-20260810-r1", "2026-08-10", 1);
        insertPublication(jdbc, "2026-08-10", 1, "weekly-20260810-r1", 1);

        // act
        Map<String, Object> data = dataOf(get(WEEKLY + "?periodStart=2026-08-17"));

        // assert
        assertThat(data).containsEntry("status", "NOT_PUBLISHED")
            .containsEntry("periodStart", "2026-08-17")
            .containsEntry("periodEnd", "2026-08-24")
            .containsEntry("items", List.of())
            .doesNotContainKeys("snapshotId", "revision", "frozenAt");
    }

    @DisplayName("구매가 없던 주의 완성본은 PUBLISHED 와 snapshotId, 빈 items 다.")
    @Test
    void returnsPublishedEmpty_whenWeekHadNoPurchases() {
        // arrange
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertSnapshot(jdbc, "weekly-20260817-r1", "2026-08-17", 1);
        insertPublication(jdbc, "2026-08-17", 1, "weekly-20260817-r1", 1);

        // act
        Map<String, Object> data = dataOf(get(WEEKLY + "?periodStart=2026-08-17"));

        // assert
        assertThat(data).containsEntry("status", "PUBLISHED")
            .containsEntry("snapshotId", "weekly-20260817-r1")
            .containsEntry("items", List.of());
    }

    @DisplayName("periodStart 가 월요일 날짜가 아니거나 limit 이 1~100 밖이면 400 INVALID_INPUT 이다.")
    @ParameterizedTest
    @ValueSource(strings = {
        "periodStart=2026-08-18",
        "periodStart=2026-8-17",
        "periodStart=2026-08-17&limit=0",
        "periodStart=2026-08-17&limit=101"
    })
    void rejectsInvalidInput(String query) {
        // act
        ResponseEntity<ApiResponse<Map<String, Object>>> response = get(WEEKLY + "?" + query);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_INPUT");
    }

    private Product deletedProduct(Brand brand, String name) {
        Product product = Product.create(brand.getId(), name, new Money(2_000), new Stock(10));
        product.delete();
        return productRepository.save(product);
    }

    private static void insertSnapshot(JdbcTemplate jdbc, String snapshotId, String periodStart, int revision) {
        jdbc.update("""
            INSERT INTO weekly_ranking_snapshot
              (snapshot_id, period_start, revision, status, frozen_at, input_count, product_count,
               input_checksum, job_execution_id, created_at, updated_at)
            VALUES (?, ?, ?, 'PUBLISHED', '2026-08-24 01:00:00', 0, 0, 'checksum', 1, NOW(6), NOW(6))""",
            snapshotId, periodStart, revision);
    }

    private static void insertRank(JdbcTemplate jdbc, String snapshotId, Long productId, long score, int ranking) {
        jdbc.update("""
            INSERT INTO weekly_ranking (snapshot_id, product_id, score, ranking, created_at, updated_at)
            VALUES (?, ?, ?, ?, NOW(6), NOW(6))""", snapshotId, productId, score, ranking);
    }

    private static void insertPublication(JdbcTemplate jdbc, String periodStart, int lastRevision,
                                          String publishedSnapshotId, Integer publishedRevision) {
        jdbc.update("""
            INSERT INTO weekly_ranking_publication
              (period_start, last_revision, published_snapshot_id, published_revision, created_at, updated_at)
            VALUES (?, ?, ?, ?, NOW(6), NOW(6))""", periodStart, lastRevision, publishedSnapshotId, publishedRevision);
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> get(String url) {
        return testRestTemplate.exchange(url, HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
    }

    private static Map<String, Object> dataOf(ResponseEntity<ApiResponse<Map<String, Object>>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().data();
    }
}
