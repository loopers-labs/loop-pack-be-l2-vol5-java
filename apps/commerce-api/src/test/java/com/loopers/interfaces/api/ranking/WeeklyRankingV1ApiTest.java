package com.loopers.interfaces.api.ranking;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;

/** GET /api/v1/rankings/weekly (설계 D14, R5). 랭킹 행은 JDBC 로 직접 넣고, 상품은 기존 리포지터리로 만들어 실제 id 를 쓴다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WeeklyRankingV1ApiTest {

    private static final String ENDPOINT = "/api/v1/rankings/weekly";
    private static final LocalDate PERIOD = LocalDate.of(2026, 8, 17);
    private static final String R1 = "weekly-20260817-r1";
    private static final String R2 = "weekly-20260817-r2";
    private static final LocalDateTime FROZEN_AT = LocalDateTime.of(2026, 8, 24, 0, 10, 0);

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;
    private final WeeklyRankingTableSupport tables;

    private long product7;
    private long product8;
    private long product9;

    @Autowired
    WeeklyRankingV1ApiTest(
        TestRestTemplate testRestTemplate,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        DatabaseCleanUp databaseCleanUp,
        JdbcTemplate jdbcTemplate
    ) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
        this.tables = new WeeklyRankingTableSupport(jdbcTemplate);
    }

    @BeforeAll
    void createRankingTables() {
        tables.createTables();
    }

    @BeforeEach
    void createProducts() {
        Brand brand = brandJpaRepository.save(new Brand("루퍼스"));
        product7 = productJpaRepository.save(new Product(brand.getId(), "상품 7", new Price(1000))).getId();
        product8 = productJpaRepository.save(new Product(brand.getId(), "상품 8", new Price(1000))).getId();
        product9 = productJpaRepository.save(new Product(brand.getId(), "상품 9", new Price(1000))).getId();
    }

    @AfterEach
    void tearDown() {
        tables.deletePeriod(PERIOD);
        databaseCleanUp.truncateAllTables();
    }

    /** §6 기대 결과: 7=5점 1위, 9=5점 2위, 8=1점 3위 를 r1 완성본으로 공개한다. */
    private void publishFixture() {
        tables.insertRevision(R1, PERIOD, 1, "COMPLETED", FROZEN_AT, 3);
        tables.insertScore(R1, product7, 5, 1, FROZEN_AT);
        tables.insertScore(R1, product9, 5, 2, FROZEN_AT);
        tables.insertScore(R1, product8, 1, 3, FROZEN_AT);
        tables.insertPublication(PERIOD, R1, 1, FROZEN_AT);
    }

    private void softDelete(long productId) {
        Product product = productJpaRepository.findById(productId).orElseThrow();
        product.delete();
        productJpaRepository.save(product);
    }

    private ResponseEntity<ApiResponse<RankingV1Dto.WeeklyRankingResponse>> get(String query) {
        return testRestTemplate.exchange(ENDPOINT + "?" + query, HttpMethod.GET, null,
            new ParameterizedTypeReference<>() { });
    }

    @DisplayName("GET /api/v1/rankings/weekly — 공개본 조회")
    @Nested
    class Published {

        @DisplayName("공개된 완성본을 ranking 순으로 돌려주고 기간·snapshotId·frozenAt 을 함께 준다.")
        @Test
        void returnsPublishedRanking() {
            publishFixture();

            ResponseEntity<ApiResponse<RankingV1Dto.WeeklyRankingResponse>> response = get("periodStart=2026-08-17&limit=10");
            RankingV1Dto.WeeklyRankingResponse body = response.getBody().data();

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(body.status()).isEqualTo("PUBLISHED"),
                () -> assertThat(body.periodStart()).isEqualTo("2026-08-17"),
                () -> assertThat(body.periodEnd()).isEqualTo("2026-08-24"),
                () -> assertThat(body.snapshotId()).isEqualTo(R1),
                () -> assertThat(body.frozenAt()).isEqualTo("2026-08-24T00:10:00Z"),
                () -> assertThat(body.items()).extracting(RankingV1Dto.Item::productId).containsExactly(product7, product9, product8),
                () -> assertThat(body.items()).extracting(RankingV1Dto.Item::ranking).containsExactly(1, 2, 3),
                () -> assertThat(body.items()).extracting(RankingV1Dto.Item::score).containsExactly(5L, 5L, 1L),
                () -> assertThat(body.items()).extracting(RankingV1Dto.Item::productName).containsExactly("상품 7", "상품 9", "상품 8")
            );
        }

        @DisplayName("응답 JSON 은 ranking 필드명과 초 단위 UTC frozenAt 을 그대로 쓴다.")
        @Test
        void rawJsonShape() {
            publishFixture();

            String json = testRestTemplate.getForObject(ENDPOINT + "?periodStart=2026-08-17", String.class);

            System.out.println("[ranking-api] " + json);
            assertThat(json).contains("\"frozenAt\":\"2026-08-24T00:10:00Z\"")
                .contains("\"snapshotId\":\"weekly-20260817-r1\"")
                .contains("\"ranking\":1")
                .contains("\"status\":\"PUBLISHED\"");
        }

        @DisplayName("삭제 상품은 제외하되 저장된 순위는 유지해 1·3위처럼 간격이 생긴다.")
        @Test
        void excludesDeletedProductKeepingRanking() {
            publishFixture();
            softDelete(product9);

            RankingV1Dto.WeeklyRankingResponse body = get("periodStart=2026-08-17").getBody().data();

            assertThat(body.items()).extracting(RankingV1Dto.Item::productId, RankingV1Dto.Item::ranking)
                .containsExactly(tuple(product7, 1), tuple(product8, 3));
        }

        @DisplayName("limit 은 삭제 상품을 제외한 뒤 적용한다.")
        @Test
        void appliesLimitAfterExclusion() {
            publishFixture();
            softDelete(product9);

            assertThat(get("periodStart=2026-08-17&limit=1").getBody().data().items())
                .extracting(RankingV1Dto.Item::productId).containsExactly(product7);
            assertThat(get("periodStart=2026-08-17&limit=2").getBody().data().items())
                .extracting(RankingV1Dto.Item::productId).containsExactly(product7, product8);
        }

        @DisplayName("pointer 가 없으면 NOT_PUBLISHED 와 빈 items 를 200 으로 돌려준다.")
        @Test
        void notPublished() {
            ResponseEntity<ApiResponse<RankingV1Dto.WeeklyRankingResponse>> response = get("periodStart=2026-08-17");
            RankingV1Dto.WeeklyRankingResponse body = response.getBody().data();

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(body.status()).isEqualTo("NOT_PUBLISHED"),
                () -> assertThat(body.snapshotId()).isNull(),
                () -> assertThat(body.frozenAt()).isNull(),
                () -> assertThat(body.items()).isEmpty()
            );
        }

        @DisplayName("구매 0건 완성본은 PUBLISHED 와 snapshotId 를 주고 items 만 비어 있다.")
        @Test
        void publishedButEmpty() {
            tables.insertRevision(R1, PERIOD, 1, "COMPLETED", FROZEN_AT, 0);
            tables.insertPublication(PERIOD, R1, 1, FROZEN_AT);

            RankingV1Dto.WeeklyRankingResponse body = get("periodStart=2026-08-17").getBody().data();

            assertAll(
                () -> assertThat(body.status()).isEqualTo("PUBLISHED"),
                () -> assertThat(body.snapshotId()).isEqualTo(R1),
                () -> assertThat(body.items()).isEmpty()
            );
        }

        @DisplayName("새 revision 이 계산 중(CALCULATING, ranking NULL)이어도 응답은 공개된 이전 revision 만 보여 준다 (R5).")
        @Test
        void keepsPreviousPublicationWhileNewRevisionIsCalculating() {
            publishFixture();
            tables.insertRevision(R2, PERIOD, 2, "CALCULATING", FROZEN_AT.plusDays(1), 3);
            tables.insertScore(R2, product7, 99, null, FROZEN_AT.plusDays(1));
            tables.insertScore(R2, product8, 99, null, FROZEN_AT.plusDays(1));

            RankingV1Dto.WeeklyRankingResponse body = get("periodStart=2026-08-17").getBody().data();

            assertAll(
                () -> assertThat(body.snapshotId()).isEqualTo(R1),
                () -> assertThat(body.items()).extracting(RankingV1Dto.Item::score).containsExactly(5L, 5L, 1L)
            );
        }
    }

    @DisplayName("GET /api/v1/rankings/weekly — 잘못된 요청")
    @Nested
    class BadRequest {

        @DisplayName("월요일 아님·형식 오류·limit 범위 밖·limit 정수 아님은 400 FAIL 이다.")
        @ParameterizedTest(name = "{0}")
        @CsvSource({
            "periodStart=2026-08-18",
            "periodStart=2026/08/17",
            "periodStart=2026-08-17&limit=0",
            "periodStart=2026-08-17&limit=101",
            "periodStart=2026-08-17&limit=abc"
        })
        void rejects(String query) {
            ResponseEntity<ApiResponse<RankingV1Dto.WeeklyRankingResponse>> response = get(query);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL)
            );
        }

        @DisplayName("periodStart 가 없으면 400 FAIL 이다.")
        @Test
        void rejectsMissingPeriodStart() {
            ResponseEntity<ApiResponse<RankingV1Dto.WeeklyRankingResponse>> response = get("limit=10");

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL)
            );
        }
    }
}
