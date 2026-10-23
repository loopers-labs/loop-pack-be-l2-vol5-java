package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentObservation.UnknownCause;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.support.TestClock;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.support.payment.LookupSettingsFixture;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG는 모든 조회에 400ms 뒤 정상 응답한다. 우리 쪽 연결 풀(같은 주소당 5개, 빌리기 대기 300ms)보다 많은 조회가
 * 동시에 몰리면, 연결을 빌리지 못한 실패가 PG 장애처럼 회로에 기록되는지 본다.
 * 앞의 5개가 실제로 PG에 도착해 연결을 점유한 것을 확인한 뒤 나머지를 보낸다.
 * 회로의 창은 최근 4개만 기억하므로, 시도 수는 회로 이벤트를 받아 따로 센다.
 */
@Tag("experiment")
class PaymentLookupConnectionPoolExperimentTest {

    private static final int POOL_PER_ROUTE = 5;
    private static final Duration PG_RESPONSE = Duration.ofMillis(400);

    private FakePaymentServer pg;
    private TestPgPaymentAdapter adapter;
    private String transactionKey;

    @BeforeEach
    void setUp() throws Exception {
        pg = new FakePaymentServer();
        adapter = new PaymentProviderConfig().paymentProvider(pg.baseUrl(), "http://127.0.0.1:8080/api/v1/payments/callback",
            Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(500));
        transactionKey = adapter.create(new PaymentRequest("LP-POOL-0", "TEST", "0000-0000-0000-0000", 7000))
            .transactions().getFirst().transactionKey();
        pg.delayEveryResponse(PG_RESPONSE);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            adapter.close();
        } finally {
            pg.close();
        }
    }

    @ParameterizedTest(name = "concurrent={0}: verified={1}, unverified={2}, circuit={3}")
    @CsvSource({"5, 5, 0, CLOSED", "10, 5, 5, OPEN", "20, 5, 15, OPEN"})
    void connectionShortageIsRecordedAsPgFailureAndOpensTheCircuitWhilePgAnswersNormally(
        int concurrent, int expectedVerified, int expectedUnverified, State expectedState
    ) throws Exception {
        ResilientPaymentProvider provider = new ResilientPaymentProvider(adapter, LookupSettingsFixture.LEARNING, new TestClock());
        Map<CircuitBreakerEvent.Type, AtomicInteger> events = new ConcurrentHashMap<>();
        provider.lookupCircuitBreaker().getEventPublisher()
            .onEvent(event -> events.computeIfAbsent(event.getEventType(), type -> new AtomicInteger()).incrementAndGet());

        ExecutorService executor = Executors.newFixedThreadPool(concurrent);
        List<Future<Timed>> futures = new ArrayList<>();
        try {
            futures.addAll(start(executor, provider, Math.min(concurrent, POOL_PER_ROUTE)));
            awaitLookupsReceived(Math.min(concurrent, POOL_PER_ROUTE));
            futures.addAll(start(executor, provider, concurrent - POOL_PER_ROUTE));

            int verified = 0;
            int retryExhausted = 0;
            int blockedByCircuit = 0;
            long slowest = 0;
            for (Future<Timed> future : futures) {
                Timed timed = future.get(10, TimeUnit.SECONDS);
                slowest = Math.max(slowest, timed.millis());
                if (timed.result().kind() == PaymentObservation.Kind.OBSERVED) {
                    verified++;
                } else if (timed.result().unknownCause() == UnknownCause.CIRCUIT_OPEN) {
                    blockedByCircuit++;
                } else {
                    retryExhausted++;
                }
            }

            int recordedSuccess = count(events, CircuitBreakerEvent.Type.SUCCESS);
            int recordedFailure = count(events, CircuitBreakerEvent.Type.ERROR);
            int notPermitted = count(events, CircuitBreakerEvent.Type.NOT_PERMITTED);
            int clientAttempts = recordedSuccess + recordedFailure;
            int pgReceived = pg.lookupCalls();
            System.out.printf("pool-experiment,concurrent=%d,verified=%d,blockedByCircuit=%d,retryExhausted=%d,"
                    + "clientAttempts=%d,pgReceived=%d,neverReachedPg=%d,recordedSuccess=%d,recordedFailure=%d,"
                    + "notPermitted=%d,circuit=%s,slowestMs=%d%n",
                concurrent, verified, blockedByCircuit, retryExhausted, clientAttempts, pgReceived,
                clientAttempts - pgReceived, recordedSuccess, recordedFailure, notPermitted,
                provider.lookupCircuitBreaker().getState(), slowest);

            assertThat(verified).isEqualTo(expectedVerified);
            assertThat(blockedByCircuit + retryExhausted).isEqualTo(expectedUnverified);
            assertThat(provider.lookupCircuitBreaker().getState()).isEqualTo(expectedState);
            // PG는 한 번도 실패하지 않았다. 회로가 센 실패는 모두 PG에 닿지 못한 시도다.
            assertThat(pgReceived).isEqualTo(recordedSuccess);
            assertThat(recordedFailure).isEqualTo(clientAttempts - pgReceived);
        } finally {
            executor.shutdownNow();
        }
    }

    private List<Future<Timed>> start(ExecutorService executor, ResilientPaymentProvider provider, int count) {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Timed>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(() -> {
                go.await();
                long started = System.nanoTime();
                PaymentObservation result = provider.findByTransactionKey(transactionKey);
                return new Timed(result, Duration.ofNanos(System.nanoTime() - started).toMillis());
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

    private static int count(Map<CircuitBreakerEvent.Type, AtomicInteger> events, CircuitBreakerEvent.Type type) {
        AtomicInteger counter = events.get(type);
        return counter == null ? 0 : counter.get();
    }

    private record Timed(PaymentObservation result, long millis) {}
}
