package com.loopers.application.order;

import com.loopers.application.product.ProductFacade;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.ResilientPaymentProvider;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.utils.DatabaseCleanUp;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조회가 동시에 쓸 수 있는 수를 3개로 제한(bulkhead)하면, 연결 5개 중 2개가 결제 생성 몫으로 남는지 본다.
 * 장면은 PaymentCreateConnectionPoolExperimentTest와 같다. PG는 정상(모든 응답 400ms)이고, 조회 20개가 몰린 순간 결제를 시작한다.
 * 프로젝트에 Resilience4j Bulkhead 모듈이 없으므로 이 테스트 안에서 세마포어로 같은 원리를 만든다.
 * 자리가 없으면 기다리지 않고 바로 확인 불가로 돌려보내며, 이 거절은 회로와 연결 풀을 거치지 않는다. 제품 코드는 바꾸지 않는다.
 */
// 가짜 PG 주소가 달라 전용 컨텍스트가 생긴다. 실험이 끝나면 닫아 DB 연결을 돌려준다.
@Tag("experiment")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentLookupBulkheadExperimentTest {

    private static final FakePaymentServer PG = startPg();
    private static final int INITIAL_STOCK = 100;
    private static final int LOOKUP_LIMIT = 3;
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
    @Autowired private OrderJpaRepository orderJpaRepository;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private final Semaphore lookupBulkhead = new Semaphore(LOOKUP_LIMIT);
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
    void limitingConcurrentLookupsLeavesConnectionsForCreateAndKeepsTheCircuitClosed() throws Exception {
        Long existing = draft(3);
        confirm(existing);
        int createsBefore = PG.createCalls();
        Long paying = draft(3);
        PG.delayEveryResponse(Duration.ofMillis(400));

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_LOOKUPS);
        List<Future<Result>> lookups = new ArrayList<>();
        try {
            lookups.addAll(startLookups(executor, LOOKUP_LIMIT));
            awaitLookupsReceived(LOOKUP_LIMIT);
            lookups.addAll(startLookups(executor, CONCURRENT_LOOKUPS - LOOKUP_LIMIT));

            long started = System.nanoTime();
            OrderPaymentInfo confirmed = confirm(paying);
            long confirmMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

            int verified = 0;
            int rejected = 0;
            int other = 0;
            for (Future<Result> lookup : lookups) {
                switch (lookup.get(10, TimeUnit.SECONDS)) {
                    case VERIFIED -> verified++;
                    case REJECTED_BY_BULKHEAD -> rejected++;
                    default -> other++;
                }
            }
            CircuitBreaker circuit = resilientPaymentProvider.lookupCircuitBreaker();
            System.out.printf("bulkhead-experiment,lookupVerified=%d,rejectedByBulkhead=%d,otherUnverified=%d,"
                    + "pgLookups=%d,circuit=%s,circuitFailures=%d,confirmStatus=%s,checked=%s,confirmMs=%d,createReceivedByPg=%d%n",
                verified, rejected, other, PG.lookupCalls(), circuit.getState(), circuit.getMetrics().getNumberOfFailedCalls(),
                confirmed.paymentStatus(), confirmed.checked(), confirmMillis, PG.createCalls() - createsBefore);

            assertThat(verified).isEqualTo(LOOKUP_LIMIT);
            assertThat(rejected).isEqualTo(CONCURRENT_LOOKUPS - LOOKUP_LIMIT);
            assertThat(PG.lookupCalls()).isEqualTo(LOOKUP_LIMIT);
            assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
            // 비어 있던 연결로 결제 생성 POST가 바로 나갔다.
            assertThat(PG.createCalls()).isEqualTo(createsBefore + 1);
            assertThat(confirmed.paymentStatus()).isEqualTo("PENDING");
            assertOrder(paying, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        } finally {
            executor.shutdownNow();
        }
    }

    private List<Future<Result>> startLookups(ExecutorService executor, int count) {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Result>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(() -> {
                go.await();
                return lookupWithBulkhead();
            }));
        }
        go.countDown();
        return futures;
    }

    /** bulkhead는 retry·회로보다 바깥에 둔다. 자리가 없으면 회로와 연결 풀을 거치지 않고 바로 돌려보낸다. */
    private Result lookupWithBulkhead() {
        if (!lookupBulkhead.tryAcquire()) {
            return Result.REJECTED_BY_BULKHEAD;
        }
        try {
            PaymentObservation observation = resilientPaymentProvider.findByTransactionKey("TEST-TX-1");
            return observation.kind() == PaymentObservation.Kind.OBSERVED ? Result.VERIFIED : Result.UNVERIFIED;
        } finally {
            lookupBulkhead.release();
        }
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

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private enum Result { VERIFIED, UNVERIFIED, REJECTED_BY_BULKHEAD }
}
