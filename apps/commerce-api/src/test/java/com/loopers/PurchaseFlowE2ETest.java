package com.loopers;

import com.jayway.jsonpath.JsonPath;
import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.payment.adapter.out.persistence.PaymentJpaRepository;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.pg.LocalTestPgServer;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 연결 흐름 (plan.md 12장): 충전 → 여러 품목 주문 생성 → 확정 → 내 주문·잔액 조회를 HTTP만으로 이어서 확인한다.
 * W5부터 확정은 PG 결제 요청이다 — 로컬 테스트 PG fixture로 접수 → PG 성공 → reconcile 반영까지 HTTP로 잇는다.
 */
// W5: PG fixture 주소(또는 발송 mock) 때문에 이 클래스만 쓰는 컨텍스트가 생긴다. 다른 클래스가 재사용하지 않으므로 끝나면 닫아
// DB 커넥션을 돌려준다 — 캐시된 컨텍스트마다 Hikari 풀을 잡아 로컬 MySQL max_connections(151)를 넘기지 않게.
@DirtiesContext
@SpringBootTest
@AutoConfigureMockMvc
class PurchaseFlowE2ETest {

    private static final String USER_HEADER = "X-USER-ID";
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final LocalTestPgServer PG = LocalTestPgServer.start();

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("pg.local.base-url", PG::baseUrl);
    }

    @AfterAll
    static void stopPg() {
        PG.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private PaymentJpaRepository paymentJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    // W5 이전: 확정이 포인트 7,000원을 써서 잔액 3,000원. 이제 확정은 PG 결제라(C-1) 잔액은 10,000원 그대로이고,
    // 확정 요청은 202·PAYMENT_PENDING(재고 선점) → PG 성공 확인 뒤 CONFIRMED·결제액 7,000원·결제 수단 PG가 된다.
    @DisplayName("PNT-02·ORD-01·ORD-05·C-1 잔액 0원 → 10,000원 충전 → 7,000원 주문 생성(DRAFT) → 확정 요청(202, 재고 선점) → PG 성공 → 주문 CONFIRMED·결제액 7,000원(PG), 잔액 10,000원 그대로, 재고 감소")
    @Test
    void chargeCreateConfirmAndCheckBalance() throws Exception {
        // arrange
        UserModel user = userJpaRepository.save(new UserModel("고객"));
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        ProductModel airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 10));
        ProductModel airForce = productJpaRepository.save(new ProductModel(nike.getId(), "에어포스", 2_000, 10));

        // act & assert: 충전 전 잔액 0원
        mockMvc.perform(get("/api/v1/points").header(USER_HEADER, user.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(0));

        // act & assert: 10,000원 충전
        mockMvc.perform(post("/api/v1/points/charge").header(USER_HEADER, user.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 10000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(10_000));

        // act & assert: 에어맥스 3개 + 에어포스 2개 = 7,000원 주문은 DRAFT이고 아직 아무것도 차감하지 않는다
        String created = mockMvc.perform(post("/api/v1/orders").header(USER_HEADER, user.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\": ["
                    + "{\"productId\": " + airMax.getId() + ", \"quantity\": 3},"
                    + "{\"productId\": " + airForce.getId() + ", \"quantity\": 2}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.totalAmount").value(7_000))
            .andReturn().getResponse().getContentAsString();
        Long orderId = JsonPath.parse(created).read("$.data.id", Long.class);
        mockMvc.perform(get("/api/v1/points").header(USER_HEADER, user.getId()))
            .andExpect(jsonPath("$.data.balance").value(10_000));

        // act & assert: 확정 요청 — 접수 202, 아직 결제액 없이 PAYMENT_PENDING이고 재고는 이미 선점됐다
        PG.reset();
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm").header(USER_HEADER, user.getId())
                .header("Idempotency-Key", "attempt-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardType\": \"SAMSUNG\", \"cardNo\": \"1234-5678-9814-1451\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.orderStatus").value("PAYMENT_PENDING"));
        assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getStock()).isEqualTo(7);
        assertThat(productJpaRepository.findById(airForce.getId()).orElseThrow().getStock()).isEqualTo(8);

        // act & assert: PG가 성공으로 끝내고, reconcile(권위 조회)이 반영한다
        await().atMost(WAIT).until(() -> paymentJpaRepository.findByOrderId(orderId).orElseThrow().getStatus() == PaymentStatus.PENDING);
        PG.complete(paymentJpaRepository.findByOrderId(orderId).orElseThrow().getTransactionKey(), "SUCCESS", null);
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/payment/reconcile").header(USER_HEADER, user.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.paymentStatus").value("SUCCESS"));

        // assert: 잔액·내 주문·재고 — 포인트는 쓰지 않았다 (C-1)
        mockMvc.perform(get("/api/v1/points").header(USER_HEADER, user.getId()))
            .andExpect(jsonPath("$.data.balance").value(10_000));
        mockMvc.perform(get("/api/v1/orders").header(USER_HEADER, user.getId()))
            .andExpect(jsonPath("$.data.content[0].id").value(orderId))
            .andExpect(jsonPath("$.data.content[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.content[0].paidAmount").value(7_000));
        mockMvc.perform(get("/api/v1/orders/" + orderId).header(USER_HEADER, user.getId()))
            .andExpect(jsonPath("$.data.paymentMethod").value("PG"));
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getStock()).isEqualTo(7);
        assertThat(productJpaRepository.findById(airForce.getId()).orElseThrow().getStock()).isEqualTo(8);
    }
}
