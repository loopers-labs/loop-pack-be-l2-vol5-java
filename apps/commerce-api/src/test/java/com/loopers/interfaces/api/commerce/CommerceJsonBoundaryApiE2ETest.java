package com.loopers.interfaces.api.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.user.PointService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.support.AdminMockMvc;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class CommerceJsonBoundaryApiE2ETest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private FixtureUserInitializer initializer;
    @Autowired
    private PointService pointService;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeEach
    void setUp() {
        initializer.initialize();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("JSON-01: 충전 객체 뒤의 추가 JSON 값·쓰레기 문자는 400이며 저장 잔액을 보존한다.")
    @ParameterizedTest
    @ValueSource(strings = {" {}", " true", " invalid"})
    void rejectsTrailingTokensWithoutCharging(String suffix) {
        pointService.charge("alice", 2000L);
        var before = jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id");

        assertFailure(post("/api/v1/points/charge", "alice", "{\"amount\":3000}" + suffix),
            400, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");

        assertThat(jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id")).isEqualTo(before);
    }

    @DisplayName("JSON-02: 주문 객체 뒤의 추가 JSON 값은 400이며 주문·항목을 저장하지 않는다.")
    @Test
    void rejectsTrailingTokensWithoutCreatingOrder() {
        Product product = productRepository.save(new Product(brandRepository.save(new Brand("브랜드")), "상품", 1000L, 5));
        String body = "{\"items\":[{\"productId\":" + product.getId() + ",\"quantity\":1}]} []";
        var usersBefore = jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id");
        var productsBefore = jdbcTemplate.queryForList("SELECT * FROM product ORDER BY id");

        assertFailure(post("/api/v1/orders", "alice", body), 400, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `order`", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_item", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id")).isEqualTo(usersBefore);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM product ORDER BY id")).isEqualTo(productsBefore);
    }

    @DisplayName("JSON-03: 관리자 브랜드 객체 뒤의 추가 JSON 값은 400이며 기존 브랜드를 보존한다.")
    @Test
    void rejectsTrailingTokensWithoutCreatingBrand() {
        brandRepository.save(new Brand("기존 브랜드"));
        var before = jdbcTemplate.queryForList("SELECT * FROM brand ORDER BY id");

        assertFailure(post("/api-admin/v1/brands", "admin", "{\"name\":\"브랜드\"} {}"),
            400, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");

        assertThat(jdbcTemplate.queryForList("SELECT * FROM brand ORDER BY id")).isEqualTo(before);
    }

    @DisplayName("JSON-04: 같은 잘못된 관리자 JSON에도 고객의 권한 오류가 먼저다.")
    @Test
    void authorizesAdministratorBeforeReadingTrailingTokens() {
        assertFailure(post("/api-admin/v1/brands", "alice", "{\"name\":\"브랜드\"} {}"),
            403, "FORBIDDEN", "관리자 권한이 필요합니다.");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM brand", Long.class)).isZero();
    }

    @DisplayName("JSON-05: 정상 JSON 뒤의 공백·개행은 허용한다.")
    @Test
    void acceptsWhitespaceAfterCompleteJsonDocument() {
        ResponseEntity<JsonNode> response = post("/api/v1/points/charge", "alice", "{\"amount\":3000} \t\r\n");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("data").path("balance").asLong()).isEqualTo(3000L);
        assertThat(pointService.balance("alice").balance()).isEqualTo(3000L);
    }

    private ResponseEntity<JsonNode> post(String path, String requester, String body) {
        if (path.startsWith("/api-admin/")) {
            return AdminMockMvc.exchange(mvc, HttpMethod.POST, path, requester, body);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", requester);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private void assertFailure(ResponseEntity<JsonNode> response, int status, String code, String message) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.path("meta").size()).isEqualTo(3);
        assertThat(body.path("meta").path("result").asText()).isEqualTo("FAIL");
        assertThat(body.path("meta").path("errorCode").asText()).isEqualTo(code);
        assertThat(body.path("meta").path("message").asText()).isEqualTo(message);
        assertThat(body.has("data")).isFalse();
    }
}
