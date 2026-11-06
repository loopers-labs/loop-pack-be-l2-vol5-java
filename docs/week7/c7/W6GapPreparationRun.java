package com.loopers.c7;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.outbox.application.port.in.OutboxPublishUseCase;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.domain.ProductModel;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C-7 1막 — W6 revision(388bc941)의 코드로 W6 누락 상태를 새 격리 환경에 만든다 (docs/week7/c7/run.sh가 W6 worktree에 복사해 실행).
 * 이 파일은 W7 저장소의 테스트가 아니다. W6 코드의 클래스(OutboxPublishUseCase 등)를 쓴다.
 * <p>
 * 환경은 스크립트가 소유한다: 격리 MySQL · Kafka 3대 · pg-sim(auto: 1초 뒤 SUCCESS + callback) · W6 streamer.
 * 주문 API(MockMvc)와 pg-sim의 실제 callback으로 두 합성 주문을 확정한다.
 * <ul>
 *   <li>A — 확정 commit 뒤 after-commit send 직전에서 멈춘다 (W6 S-13과 같은 통제된 지점: publish 한 번 건너뜀)</li>
 *   <li>B — 실제로 발행하고 streamer가 소비를 끝낸다</li>
 * </ul>
 * 정리(@AfterEach truncate)를 하지 않는다 — 2막(W7 복구)이 같은 DB·broker·PG를 그대로 이어받는다.
 * 관찰한 사건 사실은 C7_FACTS 파일(JSON)로 남긴다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {"management.server.port=0", "spring.jpa.hibernate.ddl-auto=update", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
class W6GapPreparationRun {

    private static final int PORT = freePort();
    private static final String PG_SIM = System.getenv("C7_PG_URL");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("datasource.mysql-jpa.main.jdbc-url", () -> System.getenv("C7_JDBC_URL"));
        registry.add("spring.kafka.bootstrap-servers", () -> System.getenv("C7_BOOTSTRAP"));
        registry.add("pg.local.base-url", () -> PG_SIM);
        registry.add("pg.local.callback-url", () -> "http://127.0.0.1:" + PORT + "/api/v1/payments/callback");
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private OutboxPublishUseCase outboxPublishUseCase;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void prepareW6Gap() throws Exception {
        // arrange: 고객 1 · 상품 7·8 자리에 쓸 상품 2개 (재고 5)
        UserModel user = userJpaRepository.save(new UserModel("C-7 고객"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("C-7 브랜드", null));
        ProductModel shoes = productJpaRepository.save(new ProductModel(brand.getId(), "C-7 운동화", 1_000, 5));
        ProductModel socks = productJpaRepository.save(new ProductModel(brand.getId(), "C-7 양말", 2_000, 5));
        Map<String, Object> pgBefore = pgStats();

        // A: 첫 publish만 건너뛴다 = 확정 commit 뒤 send 직전 중단
        doNothing().doCallRealMethod().when(outboxPublishUseCase).publish(any());
        Long orderA = confirmByCallback(user, "c7-a", item(shoes, 2), item(socks, 1));
        Long orderB = confirmByCallback(user, "c7-b", item(shoes, 1));

        // B는 streamer가 소비를 끝낼 때까지 기다린다
        await().atMost(Duration.ofSeconds(30)).until(() -> activityRows(orderB) > 0);

        // assert: 둘 다 CONFIRMED · 예약 CONSUMED. W7 Outbox(order_outbox)는 아직 없다. A만 구매 활동 0
        for (Long orderId : List.of(orderA, orderB)) {
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("CONFIRMED");
            assertThat(jdbcTemplate.queryForList("SELECT status FROM stock_reservations WHERE order_id = ?", String.class, orderId))
                .isNotEmpty().allMatch("CONSUMED"::equals);
        }
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'order_outbox'", Long.class)).isZero();
        assertThat(activityRows(orderA)).isZero();
        assertThat(published(orderA)).isFalse();
        assertThat(published(orderB)).isTrue();

        // 사건 사실 기록 — 2막의 입력이자 incident-w7.md의 원본
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("revision", System.getenv("C7_W6_REVISION"));
        facts.put("pgBefore", pgBefore);
        facts.put("pgAfterPreparation", pgStats());
        facts.put("A", factsOf(orderA, "commit 뒤 send 직전 중단"));
        facts.put("B", factsOf(orderB, "발행·소비 완료"));
        Files.writeString(Path.of(System.getenv("C7_FACTS")), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(facts));
        System.out.println("[C-7 준비] " + objectMapper.writeValueAsString(facts));
    }

    private Long confirmByCallback(UserModel user, String key, String... items) throws Exception {
        String created = mockMvc.perform(post("/api/v1/orders").header("X-USER-ID", user.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"items\": [" + String.join(",", items) + "]}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Long orderId = JsonPath.parse(created).read("$.data.id", Long.class);
        mockMvc.perform(post("/api/v1/orders/{orderId}/confirm", orderId).header("X-USER-ID", user.getId())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardType\": \"SAMSUNG\", \"cardNo\": \"1234-5678-9814-1451\"}"))
            .andExpect(status().isAccepted());
        // pg-sim이 1초 뒤 SUCCESS callback을 보내고, callback이 결과 적용 경계를 지나 확정한다
        await().atMost(Duration.ofSeconds(15)).until(() ->
            "CONFIRMED".equals(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId)));
        return orderId;
    }

    private static String item(ProductModel product, int quantity) {
        return "{\"productId\": " + product.getId() + ", \"quantity\": " + quantity + "}";
    }

    private int activityRows(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_purchase_activity WHERE order_id = ?", Integer.class, orderId);
    }

    private boolean published(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT published_at IS NOT NULL FROM event_outbox WHERE aggregate_id = ?", Boolean.class, orderId);
    }

    private Map<String, Object> factsOf(Long orderId, String scenario) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("scenario", scenario);
        f.put("orderId", orderId);
        f.putAll(jdbcTemplate.queryForMap("SELECT event_id AS eventId, published_at AS publishedAt, last_error AS lastError, payload FROM event_outbox WHERE aggregate_id = ?", orderId));
        f.put("orderStatus", jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId));
        f.put("confirmedAt", String.valueOf(jdbcTemplate.queryForObject("SELECT confirmed_at FROM orders WHERE id = ?", Object.class, orderId)));
        f.put("payment", jdbcTemplate.queryForMap("SELECT provider_order_id AS providerOrderId, transaction_key AS transactionKey, status FROM payments WHERE order_id = ?", orderId));
        f.put("reservations", jdbcTemplate.queryForList("SELECT product_id AS productId, quantity, status FROM stock_reservations WHERE order_id = ? ORDER BY product_id", orderId));
        f.put("activity", jdbcTemplate.queryForList("SELECT product_id AS productId, quantity FROM product_purchase_activity WHERE order_id = ? ORDER BY product_id", orderId));
        return f;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pgStats() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(PG_SIM + "/_stats")).GET().build(), HttpResponse.BodyHandlers.ofString());
        return objectMapper.readValue(response.body(), Map.class);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
