package com.loopers.interfaces.api.order;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.payment.port.LookupJitter;
import com.loopers.application.payment.port.PaymentLookupScheduleRepository;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.infrastructure.payment.PaymentLookupScheduler;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "payment.lookup.batch-size=2")
@AutoConfigureMockMvc
@Import(PaymentLookupScheduleIntegrationTest.TimeConfig.class)
class PaymentLookupScheduleIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    @Autowired private MockMvc mvc;
    @Autowired private ApplicationContext context;
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

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        server = pg.start();
        users.save(new UserJpaEntity(1));
        long brand = brands.create("브랜드").id().value();
        productId = products.create(brand, "상품", 1000, 5).id();
        orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 2))).id();
    }

    @AfterEach
    void clean() throws Exception {
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("트랜잭션 A 뒤 create 전 중단돼도 재조회 행과 5초 뒤 첫 시각이 저장된다")
    void storesScheduleBeforeCreate() {
        pg.crashBeforeSend();
        assertThatThrownBy(() -> orders.confirm(1, orderId, TestPgFixture.command())).isInstanceOf(IllegalStateException.class);
        scene("PAYMENT_PENDING", 3, "SEND_STARTED", 0, 0);
        assertThat(schedules.findByOrderId(orderId)).contains(new PaymentLookupScheduleRepository.Schedule(
            orderId, NOW.plusSeconds(5), 0, NOW, NOW));
    }

    @ParameterizedTest(name = "즉시 종결 PG 응답={0}")
    @EnumSource(value = CreateBehavior.class, names = {"ACCEPT_SUCCESS", "ACCEPT_FAILED", "REJECT_BEFORE_CREATE"})
    @DisplayName("create에서 즉시 종결되면 결과 반영 경계가 스케줄 행을 지운다")
    void removesScheduleForImmediateFinalResult(CreateBehavior behavior) {
        server.createBehavior(behavior);
        orders.confirm(1, orderId, TestPgFixture.command());
        boolean success = behavior == CreateBehavior.ACCEPT_SUCCESS;
        scene(success ? "CONFIRMED" : "PAYMENT_FAILED", success ? 3 : 5,
            success ? "SUCCESS" : behavior == CreateBehavior.ACCEPT_FAILED ? "FAILED" : "REJECTED_NO_EFFECT", 1, 0);
        assertNoSchedule();
    }

    @Test
    @DisplayName("callback 성공과 수동 reconcile 실패는 같은 결과 경계에서 스케줄 행을 지운다")
    void removesScheduleForCallbackAndManualResults() throws Exception {
        startPending();
        String key = server.transactions().get(0).transactionKey();
        server.changeStatus(key, "SUCCESS");
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionKey\":\"" + key + "\",\"orderId\":\"" + providerOrderId()
                    + "\",\"amount\":2000,\"status\":\"SUCCESS\"}"))
            .andExpect(status().isOk());
        scene("CONFIRMED", 3, "SUCCESS", 1, 1);
        assertNoSchedule();
        orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
        startPending();
        key = server.transactions().stream().filter(t -> t.orderId().equals(providerOrderId())).findFirst().orElseThrow().transactionKey();
        server.changeStatus(key, "FAILED");
        orders.reconcilePayment(orderId);
        scene("PAYMENT_FAILED", 3, "FAILED", 2, 2);
        assertNoSchedule();
    }

    @Test
    @DisplayName("중복 거래로 확인 필요가 되면 스케줄 행을 지우고 예약 재고를 유지한다")
    void removesScheduleForReview() {
        startPending();
        server.addTransaction(providerOrderId(), 2000, "SUCCESS");
        orders.reconcilePayment(orderId);
        scene("PAYMENT_PENDING", 3, "NEEDS_REVIEW", 1, 1);
        assertNoSchedule();
        assertThat(jdbc.queryForObject("select count(*) from payment_anomalies where kind = 'DUPLICATE_TRANSACTIONS'", Long.class))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("응답 유실 뒤 시각 전에는 조회하지 않고 PENDING은 10초 뒤 재조회하며 성공하면 스케줄을 지운다")
    void retriesOnlyWhenDueThenConfirms() {
        server.createBehavior(CreateBehavior.DELAY_AFTER_CREATE);
        orders.confirm(1, orderId, TestPgFixture.command());
        orders.reconcileDuePayments();
        scene("PAYMENT_PENDING", 3, "UNKNOWN", 1, 0);
        clock.advance(Duration.ofSeconds(5));
        orders.reconcileDuePayments();
        scene("PAYMENT_PENDING", 3, "PENDING", 1, 1);
        assertThat(schedules.findByOrderId(orderId)).contains(new PaymentLookupScheduleRepository.Schedule(
            orderId, NOW.plusSeconds(15), 1, NOW, NOW.plusSeconds(5)));
        orders.reconcileDuePayments();
        assertThat(server.lookupCalls()).isEqualTo(1);
        server.changeStatus(server.transactions().get(0).transactionKey(), "SUCCESS");
        clock.advance(Duration.ofSeconds(10));
        orders.reconcileDuePayments();
        scene("CONFIRMED", 3, "SUCCESS", 1, 2);
        assertNoSchedule();
        assertThat(anomalies()).isZero();
    }

    @Test
    @DisplayName("거래 0건을 12번 실제 조회하면 실패 확정 없이 확인 필요와 영구 이상을 남기고 자동 조회를 멈춘다")
    void exhaustsLimitWithoutFailingPayment() {
        server.createBehavior(CreateBehavior.ERROR_500_WITHOUT_CREATE);
        orders.confirm(1, orderId, TestPgFixture.command());
        for (int attempt = 1; attempt <= 12; attempt++) {
            clock.set(schedules.findByOrderId(orderId).orElseThrow().nextLookupAt());
            orders.reconcileDuePayments();
            assertThat(server.lookupCalls()).isEqualTo(attempt);
            if (attempt < 12) {
                assertThat(schedules.findByOrderId(orderId).orElseThrow().actualAttempts()).isEqualTo(attempt);
                assertThat(schedules.findByOrderId(orderId).orElseThrow().nextLookupAt())
                    .isEqualTo(clock.instant().plusSeconds(Math.min(60, 5L << attempt)));
            }
        }
        scene("PAYMENT_PENDING", 3, "NEEDS_REVIEW", 1, 12);
        assertNoSchedule();
        assertThat(anomalies()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select occurrences from payment_anomalies where kind = 'NO_TRANSACTION'", Long.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("select occurrences from payment_anomalies where kind = 'LOOKUP_LIMIT_EXHAUSTED'", Long.class)).isEqualTo(1);
        orders.reconcileDuePayments();
        assertThat(server.lookupCalls()).isEqualTo(12);
    }

    @ParameterizedTest(name = "자동 조회 이상={0}")
    @ValueSource(strings = {"duplicate", "mismatch"})
    @DisplayName("자동 조회의 중복·불일치는 확인 필요로 격리하고 스케줄을 지워 이후 조회하지 않는다")
    void stopsAutomaticLookupWhenReviewNeeded(String kind) {
        startPending();
        if (kind.equals("duplicate")) {
            server.addTransaction(providerOrderId(), 2000, "SUCCESS");
        } else {
            server.replaceTransaction(server.transactions().get(0).transactionKey(), providerOrderId(), 999, "SUCCESS");
        }
        clock.advance(Duration.ofSeconds(5));
        orders.reconcileDuePayments();
        scene("PAYMENT_PENDING", 3, "NEEDS_REVIEW", 1, 1);
        assertNoSchedule();
        assertThat(anomalies()).isEqualTo(1);
        orders.reconcileDuePayments();
        assertThat(server.lookupCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("A commit 뒤 발송 전 중단돼도 자동 조회가 시작되고 이후 PG 성공을 create 재전송 없이 반영한다")
    void recoversScheduleAfterCrashBeforeCreate() {
        pg.crashBeforeSend();
        assertThatThrownBy(() -> orders.confirm(1, orderId, TestPgFixture.command())).isInstanceOf(IllegalStateException.class);
        assertThat(schedules.findByOrderId(orderId)).isPresent();
        clock.advance(Duration.ofSeconds(5));
        orders.reconcileDuePayments();
        scene("PAYMENT_PENDING", 3, "SEND_STARTED", 0, 1);
        assertThat(schedules.findByOrderId(orderId).orElseThrow().actualAttempts()).isEqualTo(1);
        server.addTransaction(providerOrderId(), 2000, "SUCCESS");
        clock.set(schedules.findByOrderId(orderId).orElseThrow().nextLookupAt());
        orders.reconcileDuePayments();
        scene("CONFIRMED", 3, "SUCCESS", 0, 2);
        assertNoSchedule();
        assertThat(jdbc.queryForObject("select resolved_at is not null from payment_anomalies where kind = 'NO_TRANSACTION'", Boolean.class))
            .isTrue();
    }

    @Test
    @DisplayName("한 주문의 조회 예외가 나도 다음 주문은 조회해 확정하고 앞 주문의 스케줄은 보존한다")
    void continuesAfterOneOrderThrows() {
        startPending();
        long firstOrder = orderId;
        pg.failLookupFor(providerOrderId());
        orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
        startPending();
        String key = server.transactions().stream().filter(t -> t.orderId().equals(providerOrderId())).findFirst().orElseThrow().transactionKey();
        server.changeStatus(key, "SUCCESS");
        clock.advance(Duration.ofSeconds(5));
        orders.reconcileDuePayments();
        scene("CONFIRMED", 2, "SUCCESS", 2, 1);
        assertNoSchedule();
        assertThat(schedules.findByOrderId(firstOrder).orElseThrow().actualAttempts()).isZero();
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, firstOrder)).isEqualTo("PAYMENT_PENDING");
        assertThat(anomalies()).isZero();
    }

    @Test
    @DisplayName("자동 조회와 callback을 동시에 처리해도 실패 전이·재고 반환·스케줄 삭제는 한 번이다")
    void racesSchedulerWithCallback() throws Exception {
        startPending();
        String key = server.transactions().get(0).transactionKey();
        server.changeStatus(key, "FAILED");
        clock.advance(Duration.ofSeconds(5));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<Integer> scheduled = () -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("시작 대기 초과");
                }
                orders.reconcileDuePayments();
                return 200;
            };
            Callable<Integer> callback = () -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("시작 대기 초과");
                }
                return mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"transactionKey\":\"" + key + "\",\"orderId\":\"" + providerOrderId()
                        + "\",\"amount\":2000,\"status\":\"FAILED\"}")).andReturn().getResponse().getStatus();
            };
            List<Future<Integer>> futures = List.of(executor.submit(scheduled), executor.submit(callback));
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
            assertThat(server.lookupCalls()).isBetween(1L, 2L);
            scene("PAYMENT_FAILED", 5, "FAILED", 1, (int) server.lookupCalls());
            assertNoSchedule();
            assertThat(anomalies()).isZero();
        } finally {
            go.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("테스트 프로필은 예약 실행 빈을 만들지 않아 직접 호출한 때만 자동 조회를 실행한다")
    void disablesScheduledExecutionInTestProfile() {
        assertThat(context.getBeansOfType(PaymentLookupScheduler.class)).isEmpty();
    }

    @Test
    @DisplayName("다음 시각과 주문 ID 순으로 최대 두 건만 조회하고 연속 실행은 아직 시각이 안 된 행을 건너뛴다")
    void respectsBatchSizeAndDueOrder() {
        startPending();
        long firstOrder = orderId;
        orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
        startPending();
        long secondOrder = orderId;
        orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
        startPending();
        clock.advance(Duration.ofSeconds(5));
        assertThat(schedules.findDue(clock.instant(), 2)).containsExactly(firstOrder, secondOrder);

        orders.reconcileDuePayments();

        scene("PAYMENT_PENDING", 1, "PENDING", 3, 2);
        assertThat(schedules.findByOrderId(firstOrder).orElseThrow().actualAttempts()).isEqualTo(1);
        assertThat(schedules.findByOrderId(secondOrder).orElseThrow().actualAttempts()).isEqualTo(1);
        assertThat(schedules.findByOrderId(orderId).orElseThrow().actualAttempts()).isZero();
        orders.reconcileDuePayments();
        scene("PAYMENT_PENDING", 1, "PENDING", 3, 3);
        assertThat(schedules.findByOrderId(orderId).orElseThrow().actualAttempts()).isEqualTo(1);
        assertThat(anomalies()).isZero();
    }

    private long anomalies() {
        return jdbc.queryForObject("select count(*) from payment_anomalies", Long.class);
    }

    private void startPending() {
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        orders.confirm(1, orderId, TestPgFixture.command());
        assertThat(schedules.findByOrderId(orderId)).isPresent();
    }

    private String providerOrderId() {
        return String.format("LP-ORD-%010d", orderId);
    }

    private void assertNoSchedule() {
        assertThat(jdbc.queryForObject("select count(*) from payment_lookup_schedules where order_id = ?", Long.class, orderId)).isZero();
    }

    private void scene(String status, int stock, String pgStatus, int creates, int lookups) {
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, orderId)).isEqualTo(status);
        assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId)).isEqualTo(stock);
        assertThat(jdbc.queryForObject("select provider_status from payment_records where order_id = ?", String.class, orderId))
            .isEqualTo(pgStatus);
        assertThat(server.createCalls()).isEqualTo(creates);
        assertThat(server.lookupCalls()).isEqualTo(lookups);
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        MutablePaymentClock mutablePaymentClock() {
            return new MutablePaymentClock();
        }

        @Bean
        @Primary
        LookupJitter fixedLookupJitter() {
            return maximum -> maximum;
        }
    }
}
