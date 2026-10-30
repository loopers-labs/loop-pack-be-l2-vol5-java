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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W3 ADR-W3-04: 다른 트랜잭션이 상품 행을 잠근 채 끝나지 않으면, 잠금 경로는 3초 뒤 500이 아니라
 * 409 + Concurrency Conflict로 답하고 아무것도 바꾸지 않는다. 재고 부족(Conflict)과 errorCode가 다르다.
 * W5: 확정은 PG 결제 요청 TX A(주문 → 상품 FOR UPDATE)다. 잠금 대기에서 거절되면 재고 선점·결제 시도·PG 호출이 모두 0이다.
 */
// W5: PG fixture 주소(또는 발송 mock) 때문에 이 클래스만 쓰는 컨텍스트가 생긴다. 다른 클래스가 재사용하지 않으므로 끝나면 닫아
// DB 커넥션을 돌려준다 — 캐시된 컨텍스트마다 Hikari 풀을 잡아 로컬 MySQL max_connections(151)를 넘기지 않게.
@DirtiesContext
@SpringBootTest
@AutoConfigureMockMvc
class LockWaitTimeoutE2ETest {

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
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PaymentJpaRepository paymentJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    // W5: "다시 확정하면 성공"은 같은 Idempotency-Key로 다시 요청하면 접수(202·재고 선점)되고, PG 성공 확인 뒤 CONFIRMED가 되는 것으로 옮긴다.
    @DisplayName("상품 행이 잠긴 동안 확정·관리자 재고 설정은 409 Concurrency Conflict이고 재고·잔액·주문이 그대로다. 잠금이 풀린 뒤 다시 확정하면 성공한다.")
    @Test
    void respondsConcurrencyConflict_whenLockWaitTimesOut() throws Exception {
        // arrange
        PG.reset();
        UserModel buyer = userJpaRepository.save(new UserModel("고객"));
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        ProductModel airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 5));
        mockMvc.perform(post("/api/v1/points/charge").header(USER_HEADER, buyer.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 10000}"))
            .andExpect(status().isOk());
        String created = mockMvc.perform(post("/api/v1/orders").header(USER_HEADER, buyer.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\": [{\"productId\": " + airMax.getId() + ", \"quantity\": 1}]}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Long orderId = JsonPath.parse(created).read("$.data.id", Long.class);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            // 다른 트랜잭션이 상품 행을 잠근 채 머문다 (테스트에서만 만드는 상황)
            Future<?> holding = holder.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("SELECT id FROM products WHERE id = ? FOR UPDATE", Long.class, airMax.getId());
                locked.countDown();
                awaitQuietly(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            // act & assert: 잠금 대기 3초를 넘기면 혼잡으로 답한다
            mockMvc.perform(confirm(buyer, orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("Concurrency Conflict"));
            mockMvc.perform(put("/api-admin/v1/products/" + airMax.getId() + "/stock")
                    .with(user("admin").roles("ADMIN")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"stock\": 100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("Concurrency Conflict"));

            release.countDown();
            holding.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.shutdownNow();
            holder.awaitTermination(5, TimeUnit.SECONDS);
        }

        // assert: 아무것도 바뀌지 않았다
        assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getStock()).isEqualTo(5);
        mockMvc.perform(get("/api/v1/orders/" + orderId).header(USER_HEADER, buyer.getId()))
            .andExpect(jsonPath("$.data.status").value("DRAFT"));
        mockMvc.perform(get("/api/v1/points").header(USER_HEADER, buyer.getId()))
            .andExpect(jsonPath("$.data.balance").value(10_000));
        assertThat(paymentJpaRepository.count()).isZero();
        assertThat(PG.createCalls()).isZero();

        // act & assert: 잠금이 풀린 뒤 같은 키로 다시 시도하면 접수되고(재고 선점 5 → 4), PG 성공 확인 뒤 확정된다
        mockMvc.perform(confirm(buyer, orderId))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.orderStatus").value("PAYMENT_PENDING"));
        assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getStock()).isEqualTo(4);
        await().atMost(WAIT).until(() -> paymentJpaRepository.findByOrderId(orderId).orElseThrow().getStatus() == PaymentStatus.PENDING);
        PG.complete(paymentJpaRepository.findByOrderId(orderId).orElseThrow().getTransactionKey(), "SUCCESS", null);
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/payment/reconcile").header(USER_HEADER, buyer.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"));
        assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getStock()).isEqualTo(4);
    }

    private MockHttpServletRequestBuilder confirm(UserModel buyer, Long orderId) {
        return post("/api/v1/orders/" + orderId + "/confirm").header(USER_HEADER, buyer.getId())
            .header("Idempotency-Key", "attempt-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"cardType\": \"SAMSUNG\", \"cardNo\": \"1234-5678-9814-1451\"}");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
