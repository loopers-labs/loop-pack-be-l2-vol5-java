package com.loopers.domains.ranking.in;

import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.product.app.ProductApplicationService;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 9주차 3단계 고객 API. 실제 Controller~MySQL로 확인한다. 랭킹 테이블은 batch SQL 파일(빌드 때 복사, D155)을 그대로 실행해 만들고,
// 완성 랭킹·pointer 행은 batch가 쓴 결과처럼 이 테스트가 직접 넣는다.
@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "classpath:sql/ranking/create-ranking-tables.sql")
class WeeklyRankingApiIntegrationTest {
    private static final String PERIOD = "2026-08-17";
    private static final String R1 = "weekly-20260817-r1";
    private static final String R2 = "weekly-20260817-r2";

    @Autowired private MockMvc mvc;
    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    private long first;
    private long second;
    private long third;

    @BeforeEach
    void prepare() {
        users.save(new UserJpaEntity(1));
        long brandId = brands.create("브랜드").id();
        first = products.create(brandId, "일등", 1000, 3).id();
        second = products.create(brandId, "이등", 2000, 3).id();
        third = products.create(brandId, "삼등", 3000, 3).id();
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from ranking_publication");
        jdbc.update("delete from ranking_result");
        jdbc.update("delete from ranking_snapshot");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("A1: 공개본이 있으면 PUBLISHED와 공개 snapshot의 snapshotId·frozenAt·구간 끝을 주고 표시 순위 1..3으로 상품 정보·점수를 준다(공개 전 r2는 보이지 않는다)")
    void returnsPublishedRanking() throws Exception {
        publishFixture();
        snapshot(R2, 2);
        result(R2, 1, third, 99);

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", PERIOD).header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.periodStart").value(PERIOD))
            .andExpect(jsonPath("$.data.periodEnd").value("2026-08-24"))
            .andExpect(jsonPath("$.data.snapshotId").value(R1))
            .andExpect(jsonPath("$.data.frozenAt").value("2026-08-24T00:10:00Z"))
            .andExpect(jsonPath("$.data.items[*].rank").value(contains(1, 2, 3)))
            .andExpect(jsonPath("$.data.items[*].productId").value(contains((int) first, (int) second, (int) third)))
            .andExpect(jsonPath("$.data.items[*].score").value(contains(5, 5, 1)))
            .andExpect(jsonPath("$.data.items[0].name").value("일등"))
            .andExpect(jsonPath("$.data.items[0].price").value(1000))
            .andExpect(jsonPath("$.data.items[0].brandName").value("브랜드"))
            .andExpect(jsonPath("$.data.items[0].soldOut").value(false));
    }

    @Test
    @DisplayName("A2: 2위 상품이 삭제되면 그 상품을 뺀 뒤 표시 순위를 1부터 다시 매기고 limit은 거른 뒤에 채운다")
    void deletedProductIsExcludedBeforeLimit() throws Exception {
        publishFixture();
        products.delete(second);

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", PERIOD).param("limit", "2").header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", hasSize(2)))
            .andExpect(jsonPath("$.data.items[*].productId").value(contains((int) first, (int) third)))
            .andExpect(jsonPath("$.data.items[*].rank").value(contains(1, 2)));
    }

    @Test
    @DisplayName("A3: 그 주에 공개본이 없으면 NOT_PUBLISHED와 빈 items를 준다")
    void notPublishedWeek() throws Exception {
        snapshot(R1, 1);
        result(R1, 1, first, 5);

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", PERIOD).header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("NOT_PUBLISHED"))
            .andExpect(jsonPath("$.data.periodStart").value(PERIOD))
            .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    @DisplayName("A3: 구매가 0건인 주의 완성본이 공개돼 있으면 PUBLISHED와 빈 items를 준다")
    void publishedEmptyWeek() throws Exception {
        snapshot(R1, 1);
        jdbc.update("insert into ranking_publication(period_start, snapshot_id) values (?, ?)", PERIOD, R1);

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", PERIOD).header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.snapshotId").value(R1))
            .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    @DisplayName("A4: 재고가 0인 상품은 순위에서 빠지지 않고 soldOut=true로 표시된다")
    void soldOutProductStaysWithFlag() throws Exception {
        publishFixture();
        jdbc.update("update products set stock = 0 where id = ?", third);

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", PERIOD).header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[*].productId").value(contains((int) first, (int) second, (int) third)))
            .andExpect(jsonPath("$.data.items[*].soldOut").value(contains(false, false, true)));
    }

    @ParameterizedTest(name = "{0}: periodStart={1}, limit={2}")
    @CsvSource({
        "월요일 아님, 2026-08-18, 10",
        "날짜 형식 아님, 2026/08/17, 10",
        "limit 0, 2026-08-17, 0",
        "limit 101, 2026-08-17, 101"
    })
    @DisplayName("A5: X-USER-ID 없이도 periodStart가 월요일이 아니거나 날짜 형식이 아니거나 limit이 1~100 밖이면 400이다")
    void invalidRequestIsBadRequest(String scene, String periodStart, String limit) throws Exception {
        publishFixture();

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", periodStart).param("limit", limit))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A5: 비로그인 공개라 X-USER-ID가 없어도 200과 공개본 랭킹을 준다")
    void anonymousRequestIsAllowed() throws Exception {
        publishFixture();

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", PERIOD))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.items", hasSize(3)));
    }

    @ParameterizedTest(name = "X-USER-ID={0}")
    @ValueSource(strings = {"999", "abc"})
    @DisplayName("A5: X-USER-ID가 와도 무시해 모르는 사용자·숫자가 아닌 값이어도 200과 공개본 랭킹을 준다")
    void userHeaderIsIgnored(String user) throws Exception {
        publishFixture();

        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", PERIOD).header("X-USER-ID", user))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.items", hasSize(3)));
    }

    // 과제 §6 결과(7·9·8, 점수 5·5·1)를 이 테스트의 상품 셋으로 옮긴 공개본 r1.
    private void publishFixture() {
        snapshot(R1, 1);
        result(R1, 1, first, 5);
        result(R1, 2, second, 5);
        result(R1, 3, third, 1);
        jdbc.update("insert into ranking_publication(period_start, snapshot_id) values (?, ?)", PERIOD, R1);
    }

    private void snapshot(String snapshotId, int revision) {
        jdbc.update("insert into ranking_snapshot(snapshot_id, period_start, revision, frozen_at, input_count, input_checksum)"
            + " values (?, ?, ?, ?, 0, ?)", snapshotId, PERIOD, revision, LocalDateTime.of(2026, 8, 24, 0, 10), "0".repeat(64));
    }

    private void result(String snapshotId, int calcRank, long productId, long score) {
        jdbc.update("insert into ranking_result(snapshot_id, calc_rank, product_id, score) values (?, ?, ?, ?)", snapshotId,
            calcRank, productId, score);
    }
}
