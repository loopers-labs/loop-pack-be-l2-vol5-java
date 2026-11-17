package com.loopers.interfaces.api.ranking;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.fixture.BrandFixture;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/v1/rankings/weekly 계약(랭킹 설계 7·11.3). batch 가 만든 완성본 대신 SQL 로 공개본을 준비하며,
 * 데이터를 저장하는 case 마다 고유한 주를 쓴다. 랭킹 테이블은 Entity 가 아니어서 DatabaseCleanUp 대상이 아니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WeeklyRankingApiTest {

    private static final String ENDPOINT = "/api/v1/rankings/weekly";
    private static final ParameterizedTypeReference<ApiResponse<WeeklyRankingV1Dto.WeeklyResponse>> TYPE =
        new ParameterizedTypeReference<>() {};
    private static final String CHECKSUM = "0".repeat(64);
    private static final LocalDateTime INPUT_FIXED_AT = LocalDateTime.of(2026, 11, 9, 1, 23, 45, 123_456_000);

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private BrandFixture brandFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private JdbcTemplate jdbc;
    private String userId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        WeeklyRankingSchemaSupport.prepare(dataSource);
        userId = String.valueOf(userFixture.createUserWithPoint().getId());
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("2026-11-02: 삭제 상품·삭제 브랜드 상품을 제외한 뒤 limit 을 적용하고 저장 rank·문자열 score·UTC inputFixedAt 을 그대로 준다.")
    @Test
    void publishedRankingExcludesHiddenProductsBeforeLimit() {
        BrandModel brand = brandFixture.createBrand("나이키");
        BrandModel deletedBrand = brandFixture.createDeletedBrand("단종 브랜드");
        ProductModel shoes = productFixture.createProduct(brand.getId(), "운동화", 1_000L, 1L);
        ProductModel deleted = productFixture.createDeletedProduct("단종 상품", 1_000L, 1L);
        ProductModel hiddenByBrand = productFixture.createProduct(deletedBrand.getId(), "숨김 상품", 1_000L, 1L);
        ProductModel cap = productFixture.createProduct(brand.getId(), "모자", 1_000L, 0L);
        LocalDate week = LocalDate.of(2026, 11, 2);
        publish(week, "123456789012345678901234567890", shoes.getId(), "5", deleted.getId(), "4", hiddenByBrand.getId(),
            "1", cap.getId());

        ResponseEntity<ApiResponse<WeeklyRankingV1Dto.WeeklyResponse>> response = get("?periodStart=2026-11-02&limit=2");

        WeeklyRankingV1Dto.WeeklyResponse data = response.getBody().data();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data.status()).isEqualTo("PUBLISHED");
        assertThat(data.periodStart()).isEqualTo("2026-11-02");
        assertThat(data.periodEnd()).isEqualTo("2026-11-09");
        assertThat(data.snapshotId()).isEqualTo("weekly-20261102-r1");
        assertThat(data.inputFixedAt()).isEqualTo("2026-11-09T01:23:45.123456Z");
        assertThat(data.items()).containsExactly(
            new WeeklyRankingV1Dto.ItemResponse(shoes.getId(), "운동화", "123456789012345678901234567890", 1L),
            new WeeklyRankingV1Dto.ItemResponse(cap.getId(), "모자", "1", 4L));
    }

    @DisplayName("2026-11-09: limit 을 생략하면 10개, 100 이면 공개 가능한 전부(12개)를 rank 순으로 준다.")
    @Test
    void appliesDefaultAndMaximumLimit() {
        BrandModel brand = brandFixture.createBrand("브랜드");
        List<Object> rows = new ArrayList<>();
        for (int rank = 1; rank <= 12; rank++) {
            rows.add(String.valueOf(100 - rank));
            rows.add(productFixture.createProduct(brand.getId(), "상품" + rank, 1_000L, 1L).getId());
        }
        publish(LocalDate.of(2026, 11, 9), rows.toArray());

        assertThat(get("?periodStart=2026-11-09").getBody().data().items())
            .extracting(WeeklyRankingV1Dto.ItemResponse::rank).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(get("?periodStart=2026-11-09&limit=100").getBody().data().items()).hasSize(12);
    }

    @DisplayName("2026-11-16: 구매 0건의 완성본은 PUBLISHED·빈 items 이고, 공개본이 없는 2026-11-23 은 NOT_PUBLISHED 이며 snapshot 필드를 생략한다.")
    @Test
    void distinguishesEmptyPublicationFromNotPublished() {
        publish(LocalDate.of(2026, 11, 16));

        WeeklyRankingV1Dto.WeeklyResponse empty = get("?periodStart=2026-11-16").getBody().data();
        assertThat(empty.status()).isEqualTo("PUBLISHED");
        assertThat(empty.snapshotId()).isEqualTo("weekly-20261116-r1");
        assertThat(empty.items()).isEmpty();

        ResponseEntity<String> notPublished = restTemplate.exchange(
            ENDPOINT + "?periodStart=2026-11-23", HttpMethod.GET, request(userId), String.class);
        assertThat(notPublished.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notPublished.getBody())
            .contains("\"status\":\"NOT_PUBLISHED\"", "\"periodStart\":\"2026-11-23\"", "\"periodEnd\":\"2026-11-30\"",
                "\"items\":[]")
            .doesNotContain("snapshotId", "inputFixedAt");
    }

    @DisplayName("2026-11-30·12-07: 공개 pointer 가 없는 snapshot 이나 미완성 snapshot 을 가리키면 미공개로 숨기지 않고 500 으로 실패한다.")
    @Test
    void brokenPublicationIsInternalError() {
        insertPublication(LocalDate.of(2026, 11, 30), "weekly-20261130-r1");
        LocalDate readyWeek = LocalDate.of(2026, 12, 7);
        insertSnapshot(readyWeek, "READY");
        insertPublication(readyWeek, "weekly-20261207-r1");

        for (String query : List.of("?periodStart=2026-11-30", "?periodStart=2026-12-07")) {
            ResponseEntity<ApiResponse<WeeklyRankingV1Dto.WeeklyResponse>> response = get(query);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().meta().errorCode()).isEqualTo("Internal Server Error");
        }
    }

    @DisplayName("잘못된 날짜·limit 은 조회 전에 400 으로 거절한다.")
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "''|INVALID_REQUEST|periodStart가 필요합니다.",
        "?periodStart=|INVALID_REQUEST|periodStart가 필요합니다.",
        "?periodStart=2026-11-3|INVALID_REQUEST|periodStart는 YYYY-MM-DD 형식의 유효한 날짜여야 합니다.",
        "?periodStart=2026-02-30|INVALID_REQUEST|periodStart는 YYYY-MM-DD 형식의 유효한 날짜여야 합니다.",
        "?periodStart=2026-11-03|INVALID_REQUEST|periodStart는 UTC 기준 월요일이어야 합니다.",
        "?periodStart=2026-11-02&limit=|Bad Request|limit은 정수여야 합니다.",
        "?periodStart=2026-11-02&limit=abc|Bad Request|limit은 정수여야 합니다.",
        "?periodStart=2026-11-02&limit=99999999999|Bad Request|limit은 정수여야 합니다.",
        "?periodStart=2026-11-02&limit=0|INVALID_REQUEST|limit은 1~100이어야 합니다.",
        "?periodStart=2026-11-02&limit=101|INVALID_REQUEST|limit은 1~100이어야 합니다."
    })
    void rejectsInvalidQuery(String query, String errorCode, String message) {
        ResponseEntity<ApiResponse<WeeklyRankingV1Dto.WeeklyResponse>> response = get(query);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().meta().errorCode()).isEqualTo(errorCode);
        assertThat(response.getBody().meta().message()).isEqualTo(message);
    }

    @DisplayName("고객 식별 오류가 날짜 오류보다 먼저다: 헤더 없음 400 INVALID_REQUEST, 없는 고객 404 USER_NOT_FOUND.")
    @Test
    void customerIdentificationComesFirst() {
        ResponseEntity<ApiResponse<WeeklyRankingV1Dto.WeeklyResponse>> missing = restTemplate.exchange(
            ENDPOINT + "?periodStart=2026-11-03", HttpMethod.GET, request(null), TYPE);
        ResponseEntity<ApiResponse<WeeklyRankingV1Dto.WeeklyResponse>> unknown = restTemplate.exchange(
            ENDPOINT + "?periodStart=2026-11-03", HttpMethod.GET, request("999999"), TYPE);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().meta().errorCode()).isEqualTo("INVALID_REQUEST");
        assertThat(missing.getBody().meta().message()).doesNotContain("periodStart");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getBody().meta().errorCode()).isEqualTo("USER_NOT_FOUND");
    }

    private ResponseEntity<ApiResponse<WeeklyRankingV1Dto.WeeklyResponse>> get(String query) {
        return restTemplate.exchange(ENDPOINT + query, HttpMethod.GET, request(userId), TYPE);
    }

    private static HttpEntity<Object> request(String userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set("X-USER-ID", userId);
        }
        return new HttpEntity<>(null, headers);
    }

    /** r1 COMPLETED snapshot·결과·pointer 를 저장한다. scoreAndProduct 는 rank 순서의 (score, productId) 쌍이다. */
    private void publish(LocalDate week, Object... scoreAndProduct) {
        String snapshotId = insertSnapshot(week, "COMPLETED");
        for (int i = 0; i < scoreAndProduct.length; i += 2) {
            jdbc.update("INSERT INTO ranking_result (snapshot_id, product_id, score, calculated_rank, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                snapshotId, scoreAndProduct[i + 1], new BigDecimal((String) scoreAndProduct[i]), i / 2 + 1);
        }
        insertPublication(week, snapshotId);
    }

    private String insertSnapshot(LocalDate week, String status) {
        String snapshotId = "weekly-" + week.toString().replace("-", "") + "-r1";
        boolean completed = status.equals("COMPLETED");
        jdbc.update("INSERT INTO ranking_snapshot (snapshot_id, period_start, period_end, revision, status, input_fixed_at, "
                + "input_count, product_count, input_checksum, result_checksum, completed_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, 1, ?, ?, 0, 0, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
            snapshotId, week, week.plusDays(7), status, INPUT_FIXED_AT, CHECKSUM,
            completed ? CHECKSUM : null, completed ? INPUT_FIXED_AT : null);
        return snapshotId;
    }

    private void insertPublication(LocalDate week, String snapshotId) {
        jdbc.update("INSERT INTO ranking_publication (period_start, published_snapshot_id, published_at, created_at, updated_at) "
            + "VALUES (?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", week, snapshotId);
    }
}
