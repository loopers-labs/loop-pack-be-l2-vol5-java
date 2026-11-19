package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.WeeklyRankingStatus;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * 배치가 남기는 모양 그대로 회차 정보와 점수·순위를 넣고 실제 HTTP로 확인한다. 시각은 UTC 날짜·시각으로 넣는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WeeklyRankingV1ApiE2ETest {

    private static final String PERIOD_START = "2026-08-17";
    private static final LocalDateTime CALCULATED_AT_UTC = LocalDateTime.parse("2026-08-24T06:00:00");

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Brand brand;
    private Product product7;
    private Product product8;
    private Product product9;

    @BeforeEach
    void setUp() {
        brand = brandRepository.save(Brand.create("Nike"));
        product7 = productRepository.save(Product.create(brand.getId(), "상품 7", 12_000L));
        product8 = productRepository.save(Product.create(brand.getId(), "상품 8", 8_000L));
        product9 = productRepository.save(Product.create(brand.getId(), "상품 9", 9_000L));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("완성된 회차를 조회하면 PUBLISHED, 기간, 회차 정보와 계산 순위 순의 점수와 현재 상품 정보를 준다.")
    @Test
    void returnsPublishedRanking() {
        completeFixture(1);

        ResponseEntity<ApiResponse<WeeklyRankingV1Dto.WeeklyRankingResponse>> response = get(PERIOD_START, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        WeeklyRankingV1Dto.WeeklyRankingResponse body = response.getBody().data();
        assertThat(body.status()).isEqualTo(WeeklyRankingStatus.PUBLISHED);
        assertThat(body.periodStart()).isEqualTo(LocalDate.parse("2026-08-17"));
        assertThat(body.periodEnd()).isEqualTo(LocalDate.parse("2026-08-24"));
        assertThat(body.revision()).isEqualTo(1);
        assertThat(body.calculatedAt()).isEqualTo(Instant.parse("2026-08-24T06:00:00Z"));
        assertThat(body.items()).containsExactly(
            new WeeklyRankingV1Dto.Item(1, product7.getId(), "상품 7", brand.getId(), "Nike", 12_000L, 5),
            new WeeklyRankingV1Dto.Item(2, product9.getId(), "상품 9", brand.getId(), "Nike", 9_000L, 5),
            new WeeklyRankingV1Dto.Item(3, product8.getId(), "상품 8", brand.getId(), "Nike", 8_000L, 1));
    }

    @DisplayName("calculatedAt은 계산 시각을 9시간 밀리지 않은 UTC로 준다.")
    @Test
    void calculatedAtIsUtc() {
        completeFixture(1);

        String json = testRestTemplate.getForObject("/api/v1/rankings/weekly?periodStart=" + PERIOD_START, String.class);

        assertThat(json).contains("\"calculatedAt\":\"2026-08-24T06:00:00Z\"");
    }

    @DisplayName("완성된 회차가 없거나, 계산 중이거나 닫은 회차만 있으면 NOT_PUBLISHED, 회차 값 null, 빈 목록이다.")
    @Test
    void returnsNotPublishedWithoutCompletedRevision() {
        assertNotPublished(get(PERIOD_START, null));

        calculatingFixture(1);
        assertNotPublished(get(PERIOD_START, null));

        close(1);
        completeFixture(2);
        close(2);
        assertNotPublished(get(PERIOD_START, null));
    }

    @DisplayName("순위가 0행인 완성 회차면 PUBLISHED와 회차 정보, 빈 목록이다.")
    @Test
    void returnsPublishedEmptyRanking() {
        insertRevision(1, "COMPLETED");

        WeeklyRankingV1Dto.WeeklyRankingResponse body = get(PERIOD_START, null).getBody().data();

        assertThat(body.status()).isEqualTo(WeeklyRankingStatus.PUBLISHED);
        assertThat(body.revision()).isEqualTo(1);
        assertThat(body.items()).isEmpty();
    }

    @DisplayName("가장 최근에 완성된 회차를 보여 주고 계산 중인 회차는 보여 주지 않는다. 그 완성 회차를 닫으면 이전 완성 회차를 보여 준다.")
    @Test
    void showsLatestCompletedRevision() {
        completeFixture(1);
        insertRevision(2, "COMPLETED");
        insertScore(2, product9.getId(), 15, 1);
        insertScore(2, product7.getId(), 5, 2);
        calculatingFixture(3);

        WeeklyRankingV1Dto.WeeklyRankingResponse latest = get(PERIOD_START, null).getBody().data();

        assertThat(latest.revision()).isEqualTo(2);
        assertThat(latest.items()).extracting(WeeklyRankingV1Dto.Item::productId, WeeklyRankingV1Dto.Item::score)
            .containsExactly(tuple(product9.getId(), 15L), tuple(product7.getId(), 5L));

        close(2);

        WeeklyRankingV1Dto.WeeklyRankingResponse previous = get(PERIOD_START, null).getBody().data();

        assertThat(previous.revision()).isEqualTo(1);
        assertThat(previous.items()).extracting(WeeklyRankingV1Dto.Item::productId)
            .containsExactly(product7.getId(), product9.getId(), product8.getId());
    }

    @DisplayName("삭제된 상품은 빼고 남은 상품의 저장된 계산 순위를 그대로 준다. 순위 행은 남아 있다.")
    @Test
    void excludesDeletedProduct() {
        completeFixture(1);
        deleteProduct(product9);

        WeeklyRankingV1Dto.WeeklyRankingResponse body = get(PERIOD_START, null).getBody().data();

        assertThat(body.items()).extracting(WeeklyRankingV1Dto.Item::rank, WeeklyRankingV1Dto.Item::productId)
            .containsExactly(tuple(1, product7.getId()), tuple(3, product8.getId()));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM weekly_ranking_scores WHERE revision = 1", Integer.class)).isEqualTo(3);
    }

    @DisplayName("삭제된 상품을 먼저 거른 뒤 limit을 적용한다.")
    @Test
    void appliesLimitAfterFiltering() {
        completeFixture(1);
        deleteProduct(product7);

        WeeklyRankingV1Dto.WeeklyRankingResponse body = get(PERIOD_START, 2).getBody().data();

        assertThat(body.items()).extracting(WeeklyRankingV1Dto.Item::productId)
            .containsExactly(product9.getId(), product8.getId());
    }

    @DisplayName("다른 기간의 결과로 대신하지 않는다.")
    @Test
    void doesNotFallBackToOtherPeriod() {
        completeFixture(1);

        assertNotPublished(get("2026-08-24", null));
    }

    @DisplayName("아직 오지 않은 주의 월요일은 오류가 아니라 NOT_PUBLISHED다.")
    @Test
    void futureWeekIsNotPublished() {
        assertNotPublished(get("2030-01-07", null));
    }

    @DisplayName("periodStart가 없거나 모양이 다르거나 월요일이 아니면 400이다.")
    @ParameterizedTest
    @ValueSource(strings = {"", "?periodStart=2026-8-17", "?periodStart=20260817", "?periodStart=2026-02-30",
        "?periodStart=2026-08-19"})
    void rejectsInvalidPeriodStart(String query) {
        ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
            "/api/v1/rankings/weekly" + query, HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @DisplayName("periodStart 날짜 뒤에 시간대 표기가 붙어 있으면 버리지 않고 400이다.")
    @ParameterizedTest
    @ValueSource(strings = {"2026-08-17Z", "2026-08-17%2B09:00"})
    void rejectsPeriodStartWithOffset(String periodStart) {
        // 문자열 URL은 다시 인코딩되므로, 서버가 '+'를 그대로 받도록 인코딩한 URI를 넘긴다.
        ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
            URI.create("/api/v1/rankings/weekly?periodStart=" + periodStart), HttpMethod.GET, null,
            new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @DisplayName("limit이 0이거나 101이거나 숫자가 아니면 400이다.")
    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "abc"})
    void rejectsInvalidLimit(String limit) {
        ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
            "/api/v1/rankings/weekly?periodStart=" + PERIOD_START + "&limit=" + limit, HttpMethod.GET, null,
            new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private void deleteProduct(Product product) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            productRepository.softDeleteActiveById(product.getId(), ZonedDateTime.parse("2026-08-25T00:00:00Z")));
    }

    private ResponseEntity<ApiResponse<WeeklyRankingV1Dto.WeeklyRankingResponse>> get(String periodStart, Integer limit) {
        String url = "/api/v1/rankings/weekly?periodStart=" + periodStart + (limit == null ? "" : "&limit=" + limit);
        return testRestTemplate.exchange(url, HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
    }

    private void assertNotPublished(ResponseEntity<ApiResponse<WeeklyRankingV1Dto.WeeklyRankingResponse>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        WeeklyRankingV1Dto.WeeklyRankingResponse body = response.getBody().data();
        assertThat(body.status()).isEqualTo(WeeklyRankingStatus.NOT_PUBLISHED);
        assertThat(body.revision()).isNull();
        assertThat(body.calculatedAt()).isNull();
        assertThat(body.items()).isEmpty();
    }

    // 검증 기록의 fixture 결과: 상품 7 = 5점 1위, 9 = 5점 2위, 8 = 1점 3위
    private void completeFixture(int revision) {
        insertRevision(revision, "COMPLETED");
        insertScore(revision, product7.getId(), 5, 1);
        insertScore(revision, product9.getId(), 5, 2);
        insertScore(revision, product8.getId(), 1, 3);
    }

    // 순위를 매기다 멈춘 회차: checksum이 없고 1·2위만 매겨졌다.
    private void calculatingFixture(int revision) {
        jdbcTemplate.update("""
            INSERT INTO weekly_ranking_revisions (period_start, revision, status, calculated_at, job_execution_id)
            VALUES (?, ?, 'CALCULATING', ?, ?)""",
            LocalDate.parse(PERIOD_START), revision, CALCULATED_AT_UTC, 100L + revision);
        insertScore(revision, product7.getId(), 5, 1);
        insertScore(revision, product9.getId(), 5, 2);
        insertScore(revision, product8.getId(), 1, null);
    }

    private void insertRevision(int revision, String status) {
        jdbcTemplate.update("""
            INSERT INTO weekly_ranking_revisions
                (period_start, revision, status, calculated_at, result_checksum, job_execution_id)
            VALUES (?, ?, ?, ?, ?, ?)""",
            LocalDate.parse(PERIOD_START), revision, status, CALCULATED_AT_UTC,
            "745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2", 100L + revision);
    }

    private void insertScore(int revision, long productId, long score, Integer rank) {
        jdbcTemplate.update("""
            INSERT INTO weekly_ranking_scores (period_start, revision, product_id, score, rank_no)
            VALUES (?, ?, ?, ?, ?)""", LocalDate.parse(PERIOD_START), revision, productId, score, rank);
    }

    private void close(int revision) {
        jdbcTemplate.update("""
            UPDATE weekly_ranking_revisions SET status = 'CANCELLED', cancelled_at = ?, cancel_reason = '검증 오류'
            WHERE period_start = ? AND revision = ? AND status IN ('CALCULATING', 'COMPLETED')""",
            LocalDateTime.parse("2026-08-25T00:00:00"), LocalDate.parse(PERIOD_START), revision);
    }
}
