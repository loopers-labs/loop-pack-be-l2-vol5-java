package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.support.JdbcWriteFailureProbe;
import com.loopers.utils.DatabaseCleanUp;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderTransactionTest {
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private BrandRepository brands;
    @Autowired
    private ProductRepository products;
    @Autowired
    private FixtureUserInitializer initializer;
    @Autowired
    private DatabaseCleanUp cleanUp;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoSpyBean(name = "mySqlMainDataSource")
    private HikariDataSource dataSource;

    private final JdbcWriteFailureProbe probe = new JdbcWriteFailureProbe();

    @BeforeEach
    void setUp() throws SQLException {
        doAnswer(invocation -> probe.wrap((Connection) invocation.callRealMethod()))
            .when(dataSource).getConnection();
        initializer.initialize();
    }

    @AfterEach
    void tearDown() {
        probe.disarm();
        cleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("W3-ORDER-TX-01: 두 상품 재고·포인트·주문 확정 결과를 함께 커밋한다")
    void commitsAllChangesForMultipleItems() {
        Fixture fixture = fixture();
        var before = storedState();

        JsonNode confirmed = success(confirm(fixture.orderId()), 200);

        assertConfirmed(fixture, confirmed);
        assertUnrelatedStateUnchanged(before, storedState(), fixture);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4})
    @DisplayName("W3-ORDER-TX-02: 실제 UPDATE 이후 다음 저장 실패 시 서비스 트랜잭션 전체가 롤백된다")
    void rollsBackAllRowsWhenNextWriteFailsAfterRealUpdates(int failOnWrite) {
        Fixture fixture = fixture();
        var before = storedState();

        ResponseEntity<JsonNode> response = confirmWithFailure(fixture.orderId(), failOnWrite);

        assertInjectedFailure(response, before, fixture, failOnWrite);
        assertThat(storedState()).isEqualTo(before);
        assertThat(success(request(HttpMethod.GET, "/api/v1/orders/" + fixture.orderId(), "alice", null), 200))
            .isEqualTo(fixture.draft());
        assertThat(storedState()).isEqualTo(before);
    }

    @Test
    @DisplayName("W3-ORDER-TX-03: 저장 실패 후 같은 DRAFT를 재시도하면 한 번만 차감하고 확정한다")
    void retriesRolledBackDraftWithoutDoubleDeduction() {
        Fixture fixture = fixture();
        var before = storedState();

        ResponseEntity<JsonNode> failed = confirmWithFailure(fixture.orderId(), 2);

        assertInjectedFailure(failed, before, fixture, 2);
        assertThat(storedState()).isEqualTo(before);

        JsonNode confirmed = success(confirm(fixture.orderId()), 200);
        assertConfirmed(fixture, confirmed);
        assertUnrelatedStateUnchanged(before, storedState(), fixture);
        var afterSuccess = storedState();

        failure(confirm(fixture.orderId()), 409, "ORDER_ALREADY_CONFIRMED", "이미 확정된 주문입니다.");

        assertThat(storedState()).isEqualTo(afterSuccess);
        assertThat(success(request(HttpMethod.GET, "/api/v1/orders/" + fixture.orderId(), "alice", null), 200))
            .isEqualTo(confirmed);
    }

    private Fixture fixture() {
        Brand brand = brands.save(new Brand("주문 대상 브랜드"));
        Product first = products.save(new Product(brand, "상품 A", 1000, 5));
        Product second = products.save(new Product(brand, "상품 B", 2000, 4));
        Product unrelated = products.save(new Product(brands.save(new Brand("별도 브랜드")), "별도 상품", 300, 9));
        jdbc.update("UPDATE user SET point_balance=10000 WHERE id IN (1, 2)");
        long otherOrder = success(request(HttpMethod.POST, "/api/v1/orders", "bob",
            "{\"items\":[{\"productId\":" + unrelated.getId() + ",\"quantity\":1}]}"), 201)
            .path("orderId").asLong();
        success(request(HttpMethod.POST, "/api/v1/orders/" + otherOrder + "/confirm", "bob", null), 200);
        success(request(HttpMethod.POST, "/api/v1/products/" + first.getId() + "/likes", "alice", null), 200);
        success(request(HttpMethod.POST, "/api/v1/products/" + unrelated.getId() + "/likes", "bob", null), 200);
        JsonNode draft = success(request(HttpMethod.POST, "/api/v1/orders", "alice",
            "{\"items\":[{\"productId\":" + first.getId() + ",\"quantity\":2},"
                + "{\"productId\":" + second.getId() + ",\"quantity\":1}]}"), 201);
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("totalAmount").asLong()).isEqualTo(4000);
        assertThat(draft.path("items").size()).isEqualTo(2);
        assertThat(draft.has("paidAmount")).isFalse();
        assertThat(draft.has("confirmedAt")).isFalse();
        long orderId = draft.path("orderId").asLong();
        JsonNode storedDraft = success(request(HttpMethod.GET, "/api/v1/orders/" + orderId, "alice", null), 200);
        assertThat(ZonedDateTime.parse(storedDraft.path("createdAt").asText()).toInstant())
            .isEqualTo(ZonedDateTime.parse(draft.path("createdAt").asText()).toInstant());
        // 생성 직후 객체와 재조회 객체의 시간대 표시는 다를 수 있어, 전후 GET 전체 응답을 비교한다.
        return new Fixture(first.getId(), second.getId(), orderId, storedDraft);
    }

    private ResponseEntity<JsonNode> confirmWithFailure(long orderId, int failOnWrite) {
        // Fixture 작업은 커밋된 상태다. 요청 자체의 서비스 트랜잭션에만 장애를 주입한다.
        probe.arm(failOnWrite);
        try {
            return confirm(orderId);
        } finally {
            probe.disarm();
        }
    }

    private void assertInjectedFailure(ResponseEntity<JsonNode> response,
                                       Map<String, List<Map<String, Object>>> before,
                                       Fixture fixture, int failOnWrite) {
        failure(response, 500, "INTERNAL_ERROR", "일시적인 오류가 발생했습니다.");
        assertThat(response.getBody().toString()).doesNotContain("test-only", "SQLException", "update ");
        JdbcWriteFailureProbe.Snapshot evidence = probe.snapshot();
        assertThat(evidence.completedWrites()).isEqualTo(failOnWrite - 1);
        assertThat(evidence.injectedFailures()).isEqualTo(1);
        assertThat(evidence.executedSql()).hasSize(failOnWrite - 1);
        assertThat(evidence.failedSql()).isNotBlank();
        assertThat(evidence.transactionActive()).isTrue();
        assertThat(evidence.autoCommit()).isFalse();
        // SQL 준비만 관찰한 것이 아니라 같은 DB 연결에서 변경된 행을 읽었다는 증거다.
        assertThat(evidence.observedState()).containsOnlyKeys("brands", "products", "orders", "items", "users", "likes");
        assertUnrelatedStateUnchanged(before, evidence.observedState(), fixture);
        long changedRows = before.entrySet().stream().mapToLong(entry -> entry.getValue().stream()
            .filter(row -> !row.equals(rowById(evidence.observedState(), entry.getKey(), id(row)))).count()).sum();
        assertThat(changedRows).isEqualTo(failOnWrite - 1);
    }

    private void assertConfirmed(Fixture fixture, JsonNode confirmed) {
        assertThat(confirmed.path("orderId").asLong()).isEqualTo(fixture.orderId());
        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("totalAmount").asLong()).isEqualTo(4000);
        assertThat(confirmed.path("paidAmount").asLong()).isEqualTo(4000);
        assertThat(confirmed.path("confirmedAt").asText()).isNotBlank();
        assertThat(confirmed.path("items")).isEqualTo(fixture.draft().path("items"));
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, fixture.firstId()))
            .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, fixture.secondId()))
            .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isEqualTo(6000);
        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM `order` WHERE id=?", fixture.orderId());
        assertThat(order.get("status")).isEqualTo("CONFIRMED");
        assertThat(order.get("paid_amount")).isEqualTo(4000L);
        assertThat(order.get("confirmed_at")).isNotNull();
        assertThat(success(request(HttpMethod.GET, "/api/v1/orders/" + fixture.orderId(), "alice", null), 200))
            .isEqualTo(confirmed);
    }

    private void assertUnrelatedStateUnchanged(Map<String, List<Map<String, Object>>> before,
                                               Map<String, List<Map<String, Object>>> after, Fixture fixture) {
        for (String table : List.of("brands", "items", "likes")) {
            assertThat(after.get(table)).as(table).isEqualTo(before.get(table));
        }
        for (String table : List.of("products", "users", "orders")) {
            assertThat(after.get(table)).as(table + " row count").hasSize(before.get(table).size());
            before.get(table).stream().filter(row -> !isTarget(table, id(row), fixture))
                .forEach(row -> assertThat(rowById(after, table, id(row))).as(table + " unrelated row").isEqualTo(row));
        }
    }

    private boolean isTarget(String table, long rowId, Fixture fixture) {
        return switch (table) {
            case "products" -> rowId == fixture.firstId() || rowId == fixture.secondId();
            case "users" -> rowId == 1;
            case "orders" -> rowId == fixture.orderId();
            default -> false;
        };
    }

    private long id(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }

    private Map<String, Object> rowById(Map<String, List<Map<String, Object>>> state, String table, long rowId) {
        return state.get(table).stream().filter(row -> id(row) == rowId).findFirst().orElseThrow();
    }

    private Map<String, List<Map<String, Object>>> storedState() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            "brands", jdbc.queryForList("SELECT * FROM brand ORDER BY id"),
            "likes", jdbc.queryForList("SELECT * FROM `like` ORDER BY id"));
    }

    private ResponseEntity<JsonNode> confirm(long orderId) {
        return request(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", "alice", null);
    }

    private JsonNode success(ResponseEntity<JsonNode> response, int status) {
        assertThat(response.getStatusCode().value()).as(response.toString()).isEqualTo(status);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        return response.getBody().path("data");
    }

    private void failure(ResponseEntity<JsonNode> response, int status, String code, String message) {
        assertThat(response.getStatusCode().value()).as(response.toString()).isEqualTo(status);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("FAIL");
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo(code);
        assertThat(response.getBody().path("meta").path("message").asText()).isEqualTo(message);
        assertThat(response.getBody().has("data")).isFalse();
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, String requester, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", requester);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private record Fixture(long firstId, long secondId, long orderId, JsonNode draft) {
    }
}
