package com.loopers.application.order;

import com.loopers.application.product.ProductFacade;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.payment.ResilientPaymentProvider;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG는 정상(모든 응답 400ms)인데 조회 20개가 연결 5개를 모두 차지한 순간 고객이 결제를 시작하면,
 * 같은 연결 풀을 쓰는 생성 POST도 연결을 빌리지 못하는지 본다.
 * POST는 연결을 300ms까지 기다리고 조회의 연결은 약 400ms에 풀리므로, POST가 줄에 선 시점에 따라 결과가 갈린다.
 * 몇십 ms 차이로 두 결과가 모두 나와서, 결과를 고정하지 않고 각 결과에서 지켜야 할 사실만 확인한다.
 * - POST가 PG에 닿지 못함: 보내지 못한 것이 확실하므로 바로 실패로 닫고 예약을 돌려준다. PG 거래 없음.
 *   (이 정책 전에는 UNKNOWN·예약 유지로 남아 조회해도 0건이라 끝나지 않았다.)
 * - 연결이 풀린 뒤 POST가 나감: 정상 PENDING, PG 거래 생성.
 */
// 가짜 PG 주소가 달라 전용 컨텍스트가 생긴다. 실험이 끝나면 닫아 DB 연결을 돌려준다.
@Tag("experiment")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentCreateConnectionPoolExperimentTest {

    private static final FakePaymentServer PG = startPg();
    private static final int INITIAL_STOCK = 100;
    private static final int POOL_PER_ROUTE = 5;
    private static final int CONCURRENT_LOOKUPS = 20;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
    }

    @Autowired private OrderFacade orderFacade;
    @Autowired private UserRegistrationService registration;
    @Autowired private ResilientPaymentProvider resilientPaymentProvider;
    @Autowired private BrandJpaRepository brandJpaRepository;
    @Autowired private ProductFacade productFacade;
    @Autowired private ProductJpaRepository productJpaRepository;
    @Autowired private OrderJpaRepository orderJpaRepository;
    @Autowired private PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private Long userId;
    private Long productId;

    @BeforeEach
    void setUp() {
        PG.reset();
        resilientPaymentProvider.lookupCircuitBreaker().reset();
        userId = registration.register().getId();
        Long brandId = brandJpaRepository.save(new BrandModel("나이키", null)).getId();
        productId = productFacade.create(brandId, "양말", 1_000L, INITIAL_STOCK).id();
    }

    @AfterEach
    void tearDown() {
        PG.delayEveryResponse(null);
        databaseCleanUp.truncateAllTables();
    }

    @AfterAll
    static void stopPg() throws InterruptedException {
        PG.close();
    }

    @Test
    void createPostWaitsInTheSameConnectionPoolAsLookupsAndIsClosedAsNotSentIfItCannotBorrowOne() throws Exception {
        // 조회 대상이 될 기존 결제 하나를 정상으로 만든다(PG 거래 TEST-TX-1).
        Long existing = draft(3);
        confirm(existing);
        int createsBefore = PG.createCalls();
        // 주문은 미리 만들어 둔다. 조회가 연결을 잡은 직후 곧바로 결제를 시작해야 POST가 같은 줄에 선다.
        Long paying = draft(3);
        PG.delayEveryResponse(Duration.ofMillis(400));
        long lookupsStarted = System.nanoTime();

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_LOOKUPS);
        List<Future<PaymentObservation>> lookups = new ArrayList<>();
        try {
            lookups.addAll(startLookups(executor, POOL_PER_ROUTE));
            awaitLookupsReceived(POOL_PER_ROUTE);
            lookups.addAll(startLookups(executor, CONCURRENT_LOOKUPS - POOL_PER_ROUTE));

            // 조회가 연결 5개를 모두 잡고 15개가 줄을 선 순간, 다른 고객이 결제를 시작한다.
            long started = System.nanoTime();
            long confirmStartedAfterLookupsMs = Duration.ofNanos(started - lookupsStarted).toMillis();
            OrderPaymentInfo confirmed = confirm(paying);
            long confirmMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

            int lookupVerified = 0;
            for (Future<PaymentObservation> lookup : lookups) {
                if (lookup.get(10, TimeUnit.SECONDS).kind() == PaymentObservation.Kind.OBSERVED) {
                    lookupVerified++;
                }
            }
            System.out.printf("create-pool-experiment,confirmStartedAfterLookupsMs=%d,confirmStatus=%s,checked=%s,confirmMs=%d,createReceivedByPg=%d,"
                    + "pgTransactions=%d,lookupVerified=%d,circuit=%s%n",
                confirmStartedAfterLookupsMs, confirmed.paymentStatus(), confirmed.checked(), confirmMillis, PG.createCalls() - createsBefore,
                PG.transactionCount(), lookupVerified, resilientPaymentProvider.lookupCircuitBreaker().getState());

            if (PG.createCalls() > createsBefore) {
                // 연결이 풀린 뒤 POST가 나갔다.
                assertThat(confirmed.paymentStatus()).isEqualTo("PENDING");
                assertOrder(paying, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
                assertThat(stock()).isEqualTo(INITIAL_STOCK - 6);
                assertThat(PG.transactionCount()).isEqualTo(2);
                return;
            }
            // PG는 정상이었지만 POST는 연결을 빌리지 못해 PG에 닿지 못했다. 보내지 못한 것이 확실하므로 바로 닫는다.
            assertThat(confirmed.paymentStatus()).isEqualTo("FAILED");
            assertThat(confirmed.checked()).isTrue();
            assertThat(attemptStatus(paying)).isEqualTo(PaymentAttemptStatus.FAILED);
            assertOrder(paying, OrderStatus.PAYMENT_FAILED, ReservationStatus.RELEASED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - 3);
            assertThat(PG.transactionCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private List<Future<PaymentObservation>> startLookups(ExecutorService executor, int count) {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PaymentObservation>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(() -> {
                go.await();
                return resilientPaymentProvider.findByTransactionKey("TEST-TX-1");
            }));
        }
        go.countDown();
        return futures;
    }

    private void awaitLookupsReceived(int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (PG.lookupCalls() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(PG.lookupCalls()).isEqualTo(count);
    }

    private Long draft(int quantity) {
        return orderFacade.create(userId, List.of(new OrderFacade.OrderLine(productId, quantity))).id();
    }

    private OrderPaymentInfo confirm(Long orderId) {
        return orderFacade.confirm(userId, orderId,
            new OrderFacade.PaymentCommand("attempt-" + orderId, "TEST", "0000-0000-0000-0000"));
    }

    private void assertOrder(Long orderId, OrderStatus status, ReservationStatus reservation) {
        OrderModel order = orderJpaRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(status);
        assertThat(order.getReservationStatus()).isEqualTo(reservation);
    }

    private PaymentAttemptStatus attemptStatus(Long orderId) {
        return paymentAttemptJpaRepository.findByOrderId(orderId).map(PaymentAttempt::getStatus).orElseThrow();
    }

    private int stock() {
        return productJpaRepository.findById(productId).orElseThrow().getStockQuantity();
    }

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
