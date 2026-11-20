package com.loopers.interfaces.api.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.support.ApiTestClient;
import com.loopers.support.fixture.Fixtures;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/v1/rankings/weekly 의 HTTP 계약 (설계 10-6). 랭킹 행은 commerce-batch 가 쓰는 모양 그대로 SQL 로 넣는다.
 * 테이블은 배포 SQL(docs/week9/weekly-ranking.sql)로 만들고, 테스트가 넣은 랭킹 행은 끝나면 지운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WeeklyRankingApiTest {
    private static final String PATH = "/api/v1/rankings/weekly";
    private static final LocalDate WEEK = LocalDate.parse("2026-08-17");

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private DataSource dataSource;

    private ApiTestClient api;
    private JdbcTemplate jdbc;
    private BrandModel brand;

    @BeforeEach
    void setUp() throws SQLException {
        api = new ApiTestClient(restTemplate, objectMapper);
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(System.getProperty("ranking.weekly-sql")));
        }
        brand = fixtures.brand("브랜드");
    }

    @AfterEach
    void tearDown() {
        for (String table : List.of("weekly_ranking_publication", "weekly_ranking", "ranking_score_stage", "ranking_snapshot_input", "ranking_snapshot")) {
            jdbc.update("delete from " + table);
        }
        databaseCleanUp.truncateAllTables();
    }

    /** batch 의 입력 고정 + 공개 결과와 같은 행: snapshot(PUBLISHED 또는 FROZEN) + 완성 ranking (productId, score, rank). */
    private String snapshot(LocalDate week, long revision, String status, long[]... ranking) {
        String snapshotId = "weekly:" + week + ":r" + revision;
        jdbc.update("insert into ranking_snapshot (snapshot_id, period_start, period_end, revision, status, frozen_at, input_count, "
                + "product_count, input_checksum, frozen_by_execution_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
            snapshotId, week, week.plusWeeks(1), revision, status, LocalDateTime.parse("2026-08-24T01:00:00.123456"),
            ranking.length, ranking.length, "0".repeat(64));
        for (long[] row : ranking) {
            jdbc.update("insert into weekly_ranking (snapshot_id, product_id, score, rank_no) values (?, ?, ?, ?)", snapshotId, row[0], row[1], row[2]);
        }
        return snapshotId;
    }

    private void publish(LocalDate week, String snapshotId, long revision) {
        jdbc.update("insert into weekly_ranking_publication (period_start, snapshot_id, revision, published_at) values (?, ?, ?, ?) "
            + "on duplicate key update snapshot_id = values(snapshot_id), revision = values(revision)", week, snapshotId, revision, LocalDateTime.now());
    }

    private List<ProductModel> products(int count) {
        List<ProductModel> products = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            products.add(fixtures.product(brand.getId(), "상품 " + i, 1_000L * i, 10));
        }
        return products;
    }

    private static List<List<Long>> rankScoreProduct(JsonNode data) {
        return StreamSupport.stream(data.path("items").spliterator(), false)
            .map(item -> List.of(item.path("rank").asLong(), item.path("score").asLong(), item.path("product").path("id").asLong()))
            .toList();
    }

    @Nested
    @DisplayName("공개 여부")
    class Publication {
        @DisplayName("공개 pointer 가 없으면 NOT_PUBLISHED·빈 items 이고 snapshotId 가 없다. 계산 중(FROZEN) snapshot 의 행도 보여주지 않는다.")
        @Test
        void notPublished() {
            List<ProductModel> products = products(1);
            snapshot(WEEK, 1, "FROZEN", new long[]{products.get(0).getId(), 3, 1});

            JsonNode data = api.get(PATH + "?periodStart=2026-08-17", null).assertSuccess(HttpStatus.OK).data();

            assertThat(data.path("status").asText()).isEqualTo("NOT_PUBLISHED");
            assertThat(data.path("periodStart").asText()).isEqualTo("2026-08-17");
            assertThat(data.path("periodEnd").asText()).isEqualTo("2026-08-24");
            assertThat(data.has("snapshotId")).isFalse();
            assertThat(data.path("items").isArray()).isTrue();
            assertThat(data.path("items")).isEmpty();
        }

        @DisplayName("구매가 없었던 완성본은 PUBLISHED·snapshotId·revision·frozenAt 과 빈 items 다.")
        @Test
        void publishedEmpty() {
            String snapshotId = snapshot(WEEK, 1, "PUBLISHED");
            publish(WEEK, snapshotId, 1);

            JsonNode data = api.get(PATH + "?periodStart=2026-08-17", null).assertSuccess(HttpStatus.OK).data();

            assertThat(data.path("status").asText()).isEqualTo("PUBLISHED");
            assertThat(data.path("snapshotId").asText()).isEqualTo("weekly:2026-08-17:r1");
            assertThat(data.path("revision").asLong()).isEqualTo(1);
            assertThat(data.path("frozenAt").asText()).isEqualTo("2026-08-24T01:00:00.123456Z");
            assertThat(data.path("items")).isEmpty();
        }

        @DisplayName("pointer 가 r2 면 r2 의 행만, 다른 주를 요청하면 그 주의 결과(없으면 NOT_PUBLISHED)만 돌려준다.")
        @Test
        void followsPointerOnly() {
            List<ProductModel> products = products(2);
            long p1 = products.get(0).getId();
            long p2 = products.get(1).getId();
            snapshot(WEEK, 1, "PUBLISHED", new long[]{p1, 5, 1}, new long[]{p2, 1, 2});
            String r2 = snapshot(WEEK, 2, "PUBLISHED", new long[]{p2, 7, 1}, new long[]{p1, 5, 2});
            publish(WEEK, r2, 2);

            JsonNode data = api.get(PATH + "?periodStart=2026-08-17", null).assertSuccess(HttpStatus.OK).data();
            JsonNode otherWeek = api.get(PATH + "?periodStart=2026-08-10", null).assertSuccess(HttpStatus.OK).data();

            assertThat(data.path("snapshotId").asText()).isEqualTo(r2);
            assertThat(rankScoreProduct(data)).containsExactly(List.of(1L, 7L, p2), List.of(2L, 5L, p1));
            assertThat(otherWeek.path("status").asText()).isEqualTo("NOT_PUBLISHED");
            assertThat(otherWeek.path("items")).isEmpty();
        }
    }

    @Nested
    @DisplayName("공개 조건과 limit")
    class Visibility {
        @DisplayName("삭제된 상품은 빠지고 저장된 순위는 유지되어 1·3위처럼 간격이 생긴다. limit 은 공개 조건을 적용한 뒤 센다. 표시 정보는 현재 상품 정보다.")
        @Test
        void excludesDeletedProductsKeepingRanks() {
            List<ProductModel> products = products(3);
            ProductModel deleted = fixtures.deletedProduct(brand.getId(), "삭제 상품", 500L, 10);
            long p1 = products.get(0).getId();
            long p2 = products.get(1).getId();
            long p3 = products.get(2).getId();
            fixtures.like(fixtures.user().getId(), p3);
            String snapshotId = snapshot(WEEK, 1, "PUBLISHED",
                new long[]{p1, 9, 1}, new long[]{deleted.getId(), 8, 2}, new long[]{p3, 5, 3}, new long[]{p2, 1, 4});
            publish(WEEK, snapshotId, 1);

            JsonNode all = api.get(PATH + "?periodStart=2026-08-17", null).assertSuccess(HttpStatus.OK).data();
            JsonNode two = api.get(PATH + "?periodStart=2026-08-17&limit=2", null).assertSuccess(HttpStatus.OK).data();

            assertThat(rankScoreProduct(all)).containsExactly(List.of(1L, 9L, p1), List.of(3L, 5L, p3), List.of(4L, 1L, p2));
            assertThat(rankScoreProduct(two)).containsExactly(List.of(1L, 9L, p1), List.of(3L, 5L, p3));
            JsonNode second = all.path("items").get(1).path("product");
            assertThat(second.path("name").asText()).isEqualTo("상품 3");
            assertThat(second.path("price").asLong()).isEqualTo(3_000L);
            assertThat(second.path("brand").path("id").asLong()).isEqualTo(brand.getId());
            assertThat(second.path("brand").path("name").asText()).isEqualTo("브랜드");
            assertThat(second.path("likeCount").asLong()).isEqualTo(1);
        }

        @DisplayName("limit 이 없으면 10 개, 100 이면 100 개까지 돌려준다.")
        @Test
        void defaultAndMaxLimit() {
            List<ProductModel> products = products(12);
            long[][] ranking = new long[12][];
            for (int i = 0; i < 12; i++) {
                ranking[i] = new long[]{products.get(i).getId(), 100 - i, i + 1};
            }
            publish(WEEK, snapshot(WEEK, 1, "PUBLISHED", ranking), 1);

            assertThat(api.get(PATH + "?periodStart=2026-08-17", null).data().path("items")).hasSize(10);
            assertThat(api.get(PATH + "?periodStart=2026-08-17&limit=100", null).data().path("items")).hasSize(12);
            assertThat(api.get(PATH + "?periodStart=2026-08-17&limit=1", null).data().path("items")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("입력 검증")
    class Validation {
        @DisplayName("periodStart 가 없거나 형식이 틀리거나 월요일이 아니면 400 INVALID_RANKING_PERIOD.")
        @Test
        void rejectsInvalidPeriod() {
            for (String query : List.of("", "?periodStart=", "?periodStart=2026-8-17", "?periodStart=2026-08-18", "?periodStart=2026-02-30")) {
                api.get(PATH + query, null).assertError(HttpStatus.BAD_REQUEST, "INVALID_RANKING_PERIOD");
            }
        }

        @DisplayName("limit 이 정수가 아니거나 1 ~ 100 밖이면 400 INVALID_RANKING_LIMIT.")
        @Test
        void rejectsInvalidLimit() {
            for (String limit : List.of("0", "101", "-1", "abc", "1.5", "")) {
                api.get(PATH + "?periodStart=2026-08-17&limit=" + limit, null).assertError(HttpStatus.BAD_REQUEST, "INVALID_RANKING_LIMIT");
            }
        }
    }
}
