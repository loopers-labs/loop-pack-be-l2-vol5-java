package com.loopers.interfaces.api;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.interfaces.api.ranking.RankingV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/v1/rankings/weekly. 랭킹 테이블은 commerce-batch가 채우는 것이므로, 여기서는 batch가 남기는 모양 그대로 SQL로 넣는다.
 * 랭킹 테이블은 JPA 엔티티가 아니라 DatabaseCleanUp이 비우지 않으므로 직접 비운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WeeklyRankingV1ApiE2ETest {

    private static final String URL = "/api/v1/rankings/weekly";
    private static final ParameterizedTypeReference<ApiResponse<RankingV1Dto.WeeklyRankingResponse>> TYPE = new ParameterizedTypeReference<>() {};

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        clearRankingTables();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        clearRankingTables();
    }

    @DisplayName("그 주의 공개본이 없으면 NOT_PUBLISHED와 빈 items를 주고, 다른 주의 공개본이나 공개되지 않은 계산 결과로 대신하지 않는다.")
    @Test
    void returnsNotPublished_whenNoPublicationForWeek() {
        BrandModel brand = brandJpaRepository.save(new BrandModel("나이키"));
        ProductModel product = productJpaRepository.save(new ProductModel(brand.getId(), "runner", 10_000L, 5));
        completedSnapshot("weekly-20260810-r1", "2026-08-10", 1);
        rankingRow("weekly-20260810-r1", product.getId(), 3, 1);
        publish("2026-08-10", "weekly-20260810-r1", 1);
        // 요청한 주(8/17)에는 계산 중(FROZEN)인 snapshot과 stage만 있다.
        snapshot("weekly-20260817-r1", "2026-08-17", 1, "FROZEN");
        jdbc.update("insert into ranking_score_stage (snapshot_id, product_id, score) values (?, ?, ?)", "weekly-20260817-r1", product.getId(), 9);

        ResponseEntity<ApiResponse<RankingV1Dto.WeeklyRankingResponse>> response = get("?periodStart=2026-08-17");

        RankingV1Dto.WeeklyRankingResponse body = response.getBody().data();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body.status()).isEqualTo("NOT_PUBLISHED");
        assertThat(body.periodStart()).isEqualTo(LocalDate.parse("2026-08-17"));
        assertThat(body.periodEnd()).isEqualTo(LocalDate.parse("2026-08-24"));
        assertThat(body.snapshotId()).isNull();
        assertThat(body.items()).isEmpty();
    }

    @DisplayName("구매가 없던 주의 완성본이 공개돼 있으면 PUBLISHED·snapshotId와 빈 items로, 공개 전 상태와 구분된다.")
    @Test
    void returnsPublishedEmpty_whenCompletedWeekHasNoPurchase() {
        completedSnapshot("weekly-20260817-r1", "2026-08-17", 1);
        publish("2026-08-17", "weekly-20260817-r1", 1);

        RankingV1Dto.WeeklyRankingResponse body = get("?periodStart=2026-08-17").getBody().data();

        assertThat(body.status()).isEqualTo("PUBLISHED");
        assertThat(body.snapshotId()).isEqualTo("weekly-20260817-r1");
        assertThat(body.revision()).isEqualTo(1);
        assertThat(body.frozenAt()).isEqualTo(Instant.parse("2026-08-24T01:00:00Z"));
        assertThat(body.items()).isEmpty();
    }

    @DisplayName("공개 pointer가 가리키는 snapshot의 순위만 보여 주고, 삭제 상품은 빼되 계산 순위를 다시 매기지 않으며, 그다음 limit을 적용한다.")
    @Test
    void returnsPublishedRanking_excludingDeletedProducts() {
        BrandModel brand = brandJpaRepository.save(new BrandModel("나이키"));
        ProductModel p7 = productJpaRepository.save(new ProductModel(brand.getId(), "상품7", 7_000L, 5));
        ProductModel p9 = productJpaRepository.save(new ProductModel(brand.getId(), "상품9", 9_000L, 5));
        ProductModel p8 = productJpaRepository.save(new ProductModel(brand.getId(), "상품8", 8_000L, 5));
        completedSnapshot("weekly-20260817-r1", "2026-08-17", 1);
        rankingRow("weekly-20260817-r1", p7.getId(), 5, 1);
        rankingRow("weekly-20260817-r1", p9.getId(), 5, 2);
        rankingRow("weekly-20260817-r1", p8.getId(), 1, 3);
        publish("2026-08-17", "weekly-20260817-r1", 1);
        // 같은 주의 다음 revision이 계산 중이어도 공개 전에는 보이지 않는다.
        snapshot("weekly-20260817-r2", "2026-08-17", 2, "FROZEN");
        rankingRow("weekly-20260817-r2", p8.getId(), 99, 1);
        p9.delete();
        productJpaRepository.save(p9);

        RankingV1Dto.WeeklyRankingResponse all = get("?periodStart=2026-08-17").getBody().data();
        RankingV1Dto.WeeklyRankingResponse limited = get("?periodStart=2026-08-17&limit=1").getBody().data();

        assertThat(all.status()).isEqualTo("PUBLISHED");
        assertThat(all.snapshotId()).isEqualTo("weekly-20260817-r1");
        assertThat(all.items()).containsExactly(
            new RankingV1Dto.Item(1, p7.getId(), 5, "상품7", "나이키", 7_000L),
            new RankingV1Dto.Item(3, p8.getId(), 1, "상품8", "나이키", 8_000L)
        );
        assertThat(limited.items()).extracting(RankingV1Dto.Item::rank).containsExactly(1);
    }

    @DisplayName("periodStart가 월요일이 아니거나 형식이 틀리거나, limit이 1~100 밖이면 400을 응답한다.")
    @Test
    void returns400_whenRequestIsInvalid() {
        for (String query : List.of(
            "?periodStart=2026-08-18",
            "?periodStart=2026/08/17",
            "",
            "?periodStart=2026-08-17&limit=0",
            "?periodStart=2026-08-17&limit=101"
        )) {
            assertThat(get(query).getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    private ResponseEntity<ApiResponse<RankingV1Dto.WeeklyRankingResponse>> get(String query) {
        return testRestTemplate.exchange(URL + query, HttpMethod.GET, new HttpEntity<>(null), TYPE);
    }

    private void completedSnapshot(String snapshotId, String periodStart, int revision) {
        snapshot(snapshotId, periodStart, revision, "COMPLETED");
    }

    private void snapshot(String snapshotId, String periodStart, int revision, String status) {
        LocalDate start = LocalDate.parse(periodStart);
        jdbc.update("""
                insert into ranking_snapshot (snapshot_id, period_start, period_end, revision, status, frozen_at,
                  input_count, product_count, input_checksum)
                values (?, ?, ?, ?, ?, ?, 0, 0, ?)""",
            snapshotId, start, start.plusDays(7), revision, status,
            LocalDateTime.ofInstant(start.plusDays(7).atStartOfDay().plusHours(1).toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
            "0".repeat(64));
    }

    private void rankingRow(String snapshotId, long productId, long score, int rank) {
        jdbc.update("insert into weekly_ranking (snapshot_id, product_id, score, rank_no) values (?, ?, ?, ?)", snapshotId, productId, score, rank);
    }

    private void publish(String periodStart, String snapshotId, int revision) {
        jdbc.update("insert into ranking_publication (period_start, snapshot_id, revision, published_at) values (?, ?, ?, now(6))",
            LocalDate.parse(periodStart), snapshotId, revision);
    }

    private void clearRankingTables() {
        for (String table : List.of("ranking_snapshot", "ranking_snapshot_input", "ranking_score_stage", "weekly_ranking", "ranking_publication")) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
    }
}
