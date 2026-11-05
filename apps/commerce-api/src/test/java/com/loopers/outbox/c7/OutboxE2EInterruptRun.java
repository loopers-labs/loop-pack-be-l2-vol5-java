package com.loopers.outbox.c7;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase.RelayResult;
import com.loopers.outbox.application.port.out.OrderOutboxPort;
import com.loopers.outbox.domain.OrderOutbox;
import com.loopers.outbox.domain.OrderOutboxStatus;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.domain.ProductModel;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.KafkaTopicReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Tag;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C-6 3막 E-1 — W7 코드만으로 한 주문(상품 7×2, 상품 8×1)을 주문 API·pg-sim callback으로 확정하고,
 * relay의 첫 broker 확인 뒤 PUBLISHED 저장 전에 멈춘다(통제된 지점). 이 테스트가 끝나면 JVM이 끝난다 = relay 프로세스 종료.
 * 재시작은 다음 막(E-2, 새 JVM)이 한다. 정리를 하지 않는다.
 * <p>
 * docs/week7/c7/run.sh가 {@code ./gradlew :apps:commerce-api:isolatedRun --tests '*OutboxE2EInterruptRun'}로 실행한다.
 */
@Tag("isolated")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {"management.server.port=0", "spring.jpa.hibernate.ddl-auto=none", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
class OutboxE2EInterruptRun {

    private static final int PORT = freePort();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("datasource.mysql-jpa.main.jdbc-url", () -> System.getenv("C7_JDBC_URL"));
        registry.add("spring.kafka.bootstrap-servers", () -> System.getenv("C7_BOOTSTRAP"));
        registry.add("pg.local.base-url", () -> System.getenv("C7_PG_URL"));
        registry.add("pg.local.callback-url", () -> "http://127.0.0.1:" + PORT + "/api/v1/payments/callback");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderOutboxRelayUseCase relay;

    @MockitoSpyBean
    private OrderOutboxPort orderOutboxPort;

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
    void confirmThenStopAfterAckBeforeSavingPublished() throws Exception {
        // arrange: 상품 7·8 (재고 5), 포인트 10,000을 충전한 고객 — PG 주문이 포인트를 건드리지 않는지 보려고
        jdbcTemplate.execute("ALTER TABLE products AUTO_INCREMENT = 7");
        UserModel user = userJpaRepository.save(new UserModel("C-6 고객"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("C-6 브랜드", null));
        ProductModel product7 = productJpaRepository.save(new ProductModel(brand.getId(), "C-6 상품 7", 1_000, 5));
        ProductModel product8 = productJpaRepository.save(new ProductModel(brand.getId(), "C-6 상품 8", 2_000, 5));
        assertThat(List.of(product7.getId(), product8.getId())).containsExactly(7L, 8L);
        mockMvc.perform(post("/api/v1/points/charge").header("X-USER-ID", user.getId())
            .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 10000}")).andExpect(status().isOk());

        // act 1: 주문 API → 결제 요청(202) → pg-sim 1초 뒤 SUCCESS callback → 확정 (확정 TX가 Outbox 행을 남김)
        Long orderId = createAndConfirm(user, product7, product8);
        OrderOutbox row = orderOutboxPort.findByEventId(eventIdOf(orderId)).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        Map<String, Object> afterConfirm = IsolatedFacts.business(jdbcTemplate, orderId, user.getId(), List.of(7L, 8L));
        Map<String, Object> pgAfterConfirm = IsolatedFacts.pgStats(objectMapper);

        // act 2: relay — broker 확인(ack) 뒤 PUBLISHED 저장 TX를 한 번 실패시킨다 = 그 순간 프로세스가 멈춤
        doThrow(new IllegalStateException("PUBLISHED 저장 직전 중단 (주입)"))
            .doCallRealMethod()
            .when(orderOutboxPort).save(argThat(o -> o != null && o.getStatus() == OrderOutboxStatus.PUBLISHED));
        RelayResult interrupted = relay.relayOnce();

        // assert: broker에는 1건, 행은 SENDING. consumer는 첫 수신으로 구매 활동을 저장했다
        assertThat(interrupted.stoppedAt()).isEqualTo(row.getEventId());
        List<ConsumerRecord<String, String>> records = recordsOf(row.getEventId(), 1);
        assertThat(records).hasSize(1);
        assertThat(orderOutboxPort.findByEventId(row.getEventId()).orElseThrow().getStatus()).isEqualTo(OrderOutboxStatus.SENDING);
        await().atMost(Duration.ofSeconds(30)).until(() -> IsolatedFacts.activity(jdbcTemplate, orderId).size() == 2);

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("orderId", orderId);
        facts.put("userId", user.getId());
        facts.put("eventId", row.getEventId());
        facts.put("payload", row.getPayload());
        facts.put("leaseUntil", String.valueOf(orderOutboxPort.findByEventId(row.getEventId()).orElseThrow().getNextAttemptAt()));
        facts.put("firstRecord", Map.of("partition", records.get(0).partition(), "offset", records.get(0).offset()));
        facts.put("businessAfterConfirm", afterConfirm);
        facts.put("pgAfterConfirm", pgAfterConfirm);
        facts.put("activityAfterFirst", IsolatedFacts.activity(jdbcTemplate, orderId));
        Files.writeString(Path.of(System.getenv("C7_E2E")), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(facts));
        System.out.println("[C-6 E-1] " + objectMapper.writeValueAsString(facts));
    }

    private Long createAndConfirm(UserModel user, ProductModel product7, ProductModel product8) throws Exception {
        String created = mockMvc.perform(post("/api/v1/orders").header("X-USER-ID", user.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\": [{\"productId\": " + product7.getId() + ", \"quantity\": 2}, {\"productId\": " + product8.getId() + ", \"quantity\": 1}]}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Long orderId = JsonPath.parse(created).read("$.data.id", Long.class);
        mockMvc.perform(post("/api/v1/orders/{orderId}/confirm", orderId).header("X-USER-ID", user.getId())
                .header("Idempotency-Key", "c6-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardType\": \"SAMSUNG\", \"cardNo\": \"1234-5678-9814-1451\"}"))
            .andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(15)).until(() ->
            "CONFIRMED".equals(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId)));
        return orderId;
    }

    private String eventIdOf(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT event_id FROM order_outbox WHERE order_id = ?", String.class, orderId);
    }

    private List<ConsumerRecord<String, String>> recordsOf(String eventId, int expected) {
        return KafkaTopicReader.read(System.getenv("C7_BOOTSTRAP"), "order-events",
            r -> r.value().contains("\"eventId\":\"" + eventId + "\""), expected, Duration.ofSeconds(10));
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
