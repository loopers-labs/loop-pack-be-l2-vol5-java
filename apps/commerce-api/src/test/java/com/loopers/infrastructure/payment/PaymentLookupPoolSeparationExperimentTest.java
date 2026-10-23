package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.support.TestClock;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.support.payment.LookupSettingsFixture;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 PG(400ms), 연결 총 5개, 조회 20건·생성 POST 1건으로 세 후보를 비교한다.
 * 공유 풀 5개, 공유 풀 5개·조회 실행 3개 제한, 조회 풀 3개·생성 풀 2개 분리다.
 * 실제 adapter와 retry·회로를 쓰되, 주문 DB 처리 없이 HTTP 경계에서 자원 경쟁만 확인한다.
 * 예측: 풀 분리는 POST를 보호하지만, 조회 풀 대기 실패로 조회 회로는 열릴 수 있다.
 */
@Tag("experiment")
class PaymentLookupPoolSeparationExperimentTest {

    private static final int TOTAL_CONNECTIONS = 5;
    private static final int LOOKUP_CONNECTIONS = 3;
    private static final int CONCURRENT_LOOKUPS = 20;

    private FakePaymentServer pg;
    private final List<TestPgPaymentAdapter> adapters = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        pg = new FakePaymentServer();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            for (TestPgPaymentAdapter adapter : adapters) {
                adapter.close();
            }
        } finally {
            pg.close();
        }
    }

    @ParameterizedTest(name = "{0}: lookups and create compete for five total connections")
    @EnumSource(Policy.class)
    void comparesSharedPoolBulkheadAndSeparatePools(Policy policy) throws Exception {
        boolean split = policy == Policy.SEPARATE_POOLS;
        TestPgPaymentAdapter lookupAdapter = adapter(split ? LOOKUP_CONNECTIONS : TOTAL_CONNECTIONS);
        TestPgPaymentAdapter createAdapter = split ? adapter(TOTAL_CONNECTIONS - LOOKUP_CONNECTIONS) : lookupAdapter;
        ResilientPaymentProvider provider = new ResilientPaymentProvider(
            lookupAdapter, LookupSettingsFixture.LEARNING, new TestClock());
        CircuitBreaker circuit = provider.lookupCircuitBreaker();
        AtomicInteger failuresAtOpen = new AtomicInteger();
        circuit.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                failuresAtOpen.set(circuit.getMetrics().getNumberOfFailedCalls());
            }
        });
        PaymentObservation existing = createAdapter.create(request("existing"));
        assertThat(existing.kind()).isEqualTo(PaymentObservation.Kind.OBSERVED);
        String key = existing.transactions().getFirst().transactionKey();
        int createsBefore = pg.createCalls();
        pg.delayEveryResponse(Duration.ofMillis(400));

        Semaphore bulkhead = policy == Policy.BULKHEAD ? new Semaphore(LOOKUP_CONNECTIONS) : null;
        int initialLookups = policy == Policy.SHARED_POOL ? TOTAL_CONNECTIONS : LOOKUP_CONNECTIONS;
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_LOOKUPS);
        List<Future<Result>> lookups = new ArrayList<>();
        try {
            lookups.addAll(startLookups(executor, initialLookups, provider, key, bulkhead));
            awaitLookupsReceived(initialLookups);
            lookups.addAll(startLookups(executor, CONCURRENT_LOOKUPS - initialLookups, provider, key, bulkhead));

            long started = System.nanoTime();
            PaymentObservation created = createAdapter.create(request("new-payment"));
            long createMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

            int verified = 0;
            int rejected = 0;
            int unverified = 0;
            for (Future<Result> lookup : lookups) {
                switch (lookup.get(10, TimeUnit.SECONDS)) {
                    case VERIFIED -> verified++;
                    case REJECTED -> rejected++;
                    case UNVERIFIED -> unverified++;
                }
            }
            int createReceived = pg.createCalls() - createsBefore;
            System.out.printf("pool-separation,%s,verified=%d,rejected=%d,unverified=%d,pgLookups=%d,"
                    + "circuit=%s,failuresAtOpen=%d,createKind=%s,createReceived=%d,createMillis=%d%n",
                policy, verified, rejected, unverified, pg.lookupCalls(), circuit.getState(),
                failuresAtOpen.get(), created.kind(), createReceived, createMillis);

            assertThat(verified).isEqualTo(initialLookups);
            assertThat(pg.lookupCalls()).isEqualTo(initialLookups);
            assertThat(rejected).isEqualTo(policy == Policy.BULKHEAD ? CONCURRENT_LOOKUPS - initialLookups : 0);
            assertThat(unverified).isEqualTo(policy == Policy.BULKHEAD ? 0 : CONCURRENT_LOOKUPS - initialLookups);
            if (policy == Policy.BULKHEAD) {
                assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
                assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
            } else {
                assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
                // OPEN 뒤 도착한 정상 응답이 창을 바꿀 수 있으므로, 열리는 순간의 실패 표본을 확인한다.
                assertThat(failuresAtOpen.get()).isGreaterThanOrEqualTo(4);
            }
            if (policy == Policy.SHARED_POOL && createReceived == 0) {
                assertThat(created.kind()).isEqualTo(PaymentObservation.Kind.NOT_SENT);
            } else {
                assertThat(createReceived).isEqualTo(1);
                assertThat(created.kind()).isEqualTo(PaymentObservation.Kind.OBSERVED);
                assertThat(created.transactions().getFirst().status()).isEqualTo(PaymentTransaction.Status.PENDING);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private TestPgPaymentAdapter adapter(int connections) {
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(connections).setMaxConnPerRoute(connections).build();
        TestPgPaymentAdapter adapter = new TestPgPaymentAdapter(pg.baseUrl(), "http://127.0.0.1/callback",
            Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(500),
            HttpClients.custom().setConnectionManager(manager));
        adapters.add(adapter);
        return adapter;
    }

    private List<Future<Result>> startLookups(ExecutorService executor, int count, PaymentProvider provider,
                                              String key, Semaphore bulkhead) {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Result>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(() -> {
                go.await();
                if (bulkhead != null && !bulkhead.tryAcquire()) {
                    return Result.REJECTED;
                }
                try {
                    return provider.findByTransactionKey(key).kind() == PaymentObservation.Kind.OBSERVED
                        ? Result.VERIFIED : Result.UNVERIFIED;
                } finally {
                    if (bulkhead != null) {
                        bulkhead.release();
                    }
                }
            }));
        }
        go.countDown();
        return futures;
    }

    private void awaitLookupsReceived(int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (pg.lookupCalls() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(pg.lookupCalls()).isEqualTo(count);
    }

    private PaymentRequest request(String orderId) {
        return new PaymentRequest("LP-POOL-" + orderId, "TEST", "0000-0000-0000-0000", 7_000);
    }

    private enum Policy { SHARED_POOL, BULKHEAD, SEPARATE_POOLS }
    private enum Result { VERIFIED, UNVERIFIED, REJECTED }
}
