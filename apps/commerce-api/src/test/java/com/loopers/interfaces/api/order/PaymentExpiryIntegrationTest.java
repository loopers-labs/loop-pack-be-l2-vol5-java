package com.loopers.interfaces.api.order;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.payment.port.PaymentLookupScheduleRepository;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.infrastructure.payment.TestPgServer;
import com.loopers.infrastructure.payment.TestPgServer.CreateBehavior;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentLookupScheduleIntegrationTest.TimeConfig.class)
class PaymentExpiryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofMinutes(2));
    @Autowired private MockMvc mvc;
    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private OrderApplicationService orders;
    @Autowired private PaymentLookupScheduleRepository schedules;
    @Autowired private MutablePaymentClock clock;
    @Autowired private TestPgFixture pg;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    private TestPgServer server;
    private long productId;
    private long orderId;
    private String attemptKey;

    @BeforeEach
    void prepare() throws Exception {
        clock.set(NOW);
        server = pg.start();
        users.save(new UserJpaEntity(1));
        long brand = brands.create("브랜드").id().value();
        productId = products.create(brand, "상품", 1000, 5).id();
        orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 2))).id();
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        attemptKey = UUID.randomUUID().toString();
        confirm(attemptKey).andExpect(status().isAccepted());
    }

    @AfterEach
    void clean() throws Exception {
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("결제 대기 1분 59초에는 만료하지 않고 2분이 지나면 결제 만료·재고 반환·스케줄 삭제·일시적 이상 해결을 하며 create는 더 보내지 않는다")
    void expiresPendingPaymentAfterTwoMinutes() {
        server.lookupBehavior(TestPgServer.LookupBehavior.ERROR_500);
        orders.reconcilePayment(orderId);
        clock.set(EXPIRES_AT.minusSeconds(1));

        orders.expireOverduePayments();

        scene("PAYMENT_PENDING", 3, "PENDING", "PENDING", 1, 1);
        assertThat(schedules.findByOrderId(orderId)).isPresent();
        assertThat(unresolved("LOOKUP_FAILED")).isEqualTo(1);

        clock.set(EXPIRES_AT);
        orders.expireOverduePayments();

        scene("PAYMENT_EXPIRED", 5, "EXPIRED", "EXPIRED", 1, 1);
        assertNoSchedule();
        assertThat(unresolved("LOOKUP_FAILED")).isZero();
        assertThat(anomalies()).isEqualTo(1);
    }

    @Test
    @DisplayName("만료를 두 번 실행해도 재고는 한 번만 반환된다")
    void returnsStockOnceWhenExpiredTwice() {
        clock.set(EXPIRES_AT);

        orders.expireOverduePayments();
        orders.expireOverduePayments();

        scene("PAYMENT_EXPIRED", 5, "EXPIRED", "EXPIRED", 1, 0);
    }

    @Test
    @DisplayName("중복 거래로 확인 필요가 된 주문도 2분이 지나면 만료·재고 반환되고 영구 이상은 미해결로 남는다")
    void expiresReviewOrderAndKeepsPermanentAnomaly() {
        server.addTransaction(providerOrderId(), 2000, "SUCCESS");
        orders.reconcilePayment(orderId);
        scene("PAYMENT_PENDING", 3, "NEEDS_REVIEW", "PENDING", 1, 1);
        assertNoSchedule();
        clock.set(EXPIRES_AT);

        orders.expireOverduePayments();

        scene("PAYMENT_EXPIRED", 5, "EXPIRED", "EXPIRED", 1, 1);
        assertThat(unresolved("DUPLICATE_TRANSACTIONS")).isEqualTo(1);
    }

    @ParameterizedTest(name = "늦은 성공 경로={0}")
    @ValueSource(strings = {"callback", "customer-reconcile"})
    @DisplayName("만료 뒤 PG 성공이 확인돼도 주문·재고를 덮어쓰지 않고 충돌 이상을 한 행 남기며 응답은 만료 200이다")
    void recordsConflictForLateSuccess(String path) throws Exception {
        clock.set(EXPIRES_AT);
        orders.expireOverduePayments();
        String key = server.transactions().get(0).transactionKey();
        server.changeStatus(key, "SUCCESS");

        if (path.equals("callback")) {
            mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"transactionKey\":\"" + key + "\",\"providerOrderId\":\"" + providerOrderId()
                        + "\",\"status\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        } else {
            mvc.perform(post("/api/v1/orders/{id}/payment/reconcile", orderId).header("X-USER-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAYMENT_EXPIRED"))
                .andExpect(jsonPath("$.data.providerStatus").value("EXPIRED"));
        }

        scene("PAYMENT_EXPIRED", 5, "EXPIRED", "EXPIRED", 1, 1);
        assertThat(unresolved("CONFLICTING_RESULT")).isEqualTo(1);
        assertThat(anomalies()).isEqualTo(1);
    }

    @Test
    @DisplayName("만료 주문에 같은 키 confirm은 만료 결과 200, 다른 키는 409이고 create를 다시 보내지 않으며 주문 조회도 만료를 보여 준다")
    void answersConfirmForExpiredOrder() throws Exception {
        clock.set(EXPIRES_AT);
        orders.expireOverduePayments();

        confirm(attemptKey)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PAYMENT_EXPIRED"))
            .andExpect(jsonPath("$.data.paymentResult").value("EXPIRED"))
            .andExpect(jsonPath("$.data.providerStatus").value("EXPIRED"))
            .andExpect(jsonPath("$.data.paidAmount").value(0));
        confirm(UUID.randomUUID().toString()).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/orders/{id}", orderId).header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PAYMENT_EXPIRED"))
            .andExpect(jsonPath("$.data.providerStatus").value("EXPIRED"));

        scene("PAYMENT_EXPIRED", 5, "EXPIRED", "EXPIRED", 1, 0);
    }

    @Test
    @DisplayName("만료와 성공 callback이 동시에 오면 하나만 이기고 재고 차감·반환과 충돌 기록이 이긴 쪽과 맞는다")
    void racesExpiryWithSuccessCallback() throws Exception {
        String key = server.transactions().get(0).transactionKey();
        server.changeStatus(key, "SUCCESS");
        clock.set(EXPIRES_AT);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<Integer> expiry = () -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("시작 대기 초과");
                }
                orders.expireOverduePayments();
                return 200;
            };
            Callable<Integer> callback = () -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("시작 대기 초과");
                }
                return mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"transactionKey\":\"" + key + "\",\"providerOrderId\":\"" + providerOrderId()
                        + "\",\"status\":\"SUCCESS\"}")).andReturn().getResponse().getStatus();
            };
            List<Future<Integer>> futures = List.of(executor.submit(expiry), executor.submit(callback));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            int success = 0;
            int rejected = 0;
            int technical = 0;
            for (Future<Integer> future : futures) {
                try {
                    int response = future.get(10, TimeUnit.SECONDS);
                    if (response == 200) {
                        success++;
                    } else if (response >= 400 && response < 500) {
                        rejected++;
                    } else {
                        technical++;
                    }
                } catch (ExecutionException | TimeoutException e) {
                    technical++;
                }
            }
            assertThat(success + rejected + technical).isEqualTo(2);
            assertThat(success).isEqualTo(2);
            assertThat(rejected).isZero();
            assertThat(technical).isZero();
            String finalStatus = jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
            if (finalStatus.equals("CONFIRMED")) {
                scene("CONFIRMED", 3, "SUCCESS", "SUCCESS", 1, 1);
                assertThat(anomalies()).isZero();
            } else {
                scene("PAYMENT_EXPIRED", 5, "EXPIRED", "EXPIRED", 1, 1);
                assertThat(unresolved("CONFLICTING_RESULT")).isEqualTo(1);
                assertThat(anomalies()).isEqualTo(1);
            }
            assertNoSchedule();
        } finally {
            go.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ResultActions confirm(String key) throws Exception {
        String body = "{\"paymentAttemptKey\":\"" + key + "\",\"cardType\":\"" + TestPgFixture.CARD_TYPE
            + "\",\"cardNo\":\"" + TestPgFixture.CARD_NO + "\"}";
        return mvc.perform(post("/api/v1/orders/{id}/confirm", orderId).header("X-USER-ID", "1")
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String providerOrderId() {
        return String.format("LP-ORD-%010d", orderId);
    }

    private long anomalies() {
        return jdbc.queryForObject("select count(*) from payment_anomalies", Long.class);
    }

    private long unresolved(String kind) {
        return jdbc.queryForObject("select count(*) from payment_anomalies where kind = ? and resolved_at is null", Long.class, kind);
    }

    private void assertNoSchedule() {
        assertThat(jdbc.queryForObject("select count(*) from payment_lookup_schedules where order_id = ?", Long.class, orderId)).isZero();
    }

    private void scene(String status, int stock, String providerStatus, String result, int creates, int lookups) {
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, orderId)).isEqualTo(status);
        assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId)).isEqualTo(stock);
        assertThat(jdbc.queryForObject("select provider_status from payment_records where order_id = ?", String.class, orderId))
            .isEqualTo(providerStatus);
        assertThat(jdbc.queryForObject("select result from payment_records where order_id = ?", String.class, orderId))
            .isEqualTo(result);
        assertThat(server.createCalls()).isEqualTo(creates);
        assertThat(server.lookupCalls()).isEqualTo(lookups);
    }
}
