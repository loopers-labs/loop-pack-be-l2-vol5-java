package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.support.TestClock;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.support.payment.LookupSettingsFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * timeout 값만 바꾸어 "얼마나 늦으면 실패로 볼까"의 선이 retry·회로·연결 사용에 주는 영향을 본다.
 * A. 조회 응답 대기 300·500·800ms를 PG 응답 400ms(조금 느린 정상)·2초(많이 느림)에서 고객 6명이 차례로 확인한다.
 * B. 연결 빌리기 대기 300·500ms를 연결 풀 장면(PG 400ms, 동시 조회 20개, 연결 5개)에서 비교한다.
 * 실제 adapter와 ResilientPaymentProvider에 값만 바꿔 넣는다. retry 예산 판단에 쓰는 조회 한 번의 추정 시간도
 * 실제 설정처럼 "연결 대기 × 2 + 조회 응답 대기"로 함께 맞춘다. 그 밖의 retry·회로 설정은 학습값과 같다.
 */
@Tag("experiment")
class PaymentLookupTimeoutExperimentTest {

    private static final LookupResilienceSettings BASE = LookupSettingsFixture.LEARNING;
    private static final int POOL_PER_ROUTE = 5;

    private FakePaymentServer pg;
    private TestPgPaymentAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        pg = new FakePaymentServer();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (adapter != null) {
                adapter.close();
            }
        } finally {
            pg.close();
        }
    }

    @ParameterizedTest(name = "pg={0}ms, lookupTimeout={1}ms: verified={2}, openedAtCustomer={3}")
    @CsvSource({
        "400, 300, 0, 2",
        "400, 500, 6, 0",
        "400, 800, 6, 0",
        "2000, 300, 0, 2",
        "2000, 500, 0, 2",
        "2000, 800, 0, 4"
    })
    void lookupTimeoutDecidesWhatCountsAsFailure(long pgMillis, long lookupTimeoutMillis,
                                                 int expectedVerified, int expectedOpenedAtCustomer) throws Exception {
        String key = prepare(Duration.ofMillis(300), Duration.ofMillis(lookupTimeoutMillis));
        ResilientPaymentProvider provider = provider(Duration.ofMillis(300), Duration.ofMillis(lookupTimeoutMillis));
        pg.delayEveryResponse(Duration.ofMillis(pgMillis));

        int verified = 0;
        int openedAt = 0;
        List<String> perCustomer = new ArrayList<>();
        for (int customer = 1; customer <= 6; customer++) {
            int before = pg.lookupCalls();
            long started = System.nanoTime();
            PaymentObservation result = provider.findByTransactionKey(key);
            long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            if (result.kind() == PaymentObservation.Kind.OBSERVED) {
                verified++;
            }
            if (openedAt == 0 && provider.lookupCircuitBreaker().getState() == State.OPEN) {
                openedAt = customer;
            }
            perCustomer.add("c%d:%dGET/%dms/%s".formatted(customer, pg.lookupCalls() - before, millis,
                result.kind() == PaymentObservation.Kind.OBSERVED ? "OK" : result.unknownCause()));
        }
        System.out.printf("timeout-lookup,pg=%dms,lookupTimeout=%dms,verified=%d,openedAtCustomer=%d,pgLookups=%d,%s%n",
            pgMillis, lookupTimeoutMillis, verified, openedAt, pg.lookupCalls(), String.join(" ", perCustomer));

        assertThat(verified).isEqualTo(expectedVerified);
        assertThat(openedAt).isEqualTo(expectedOpenedAtCustomer);
    }

    @ParameterizedTest(name = "connectionWait={0}ms: verified={1}, circuit={2}")
    @CsvSource({"300, 5, OPEN", "500, 10, OPEN"})
    void connectionWaitDecidesWhetherWaitingLookupsGetAFreedConnection(
        long connectionWaitMillis, int expectedVerified, State expectedState
    ) throws Exception {
        String key = prepare(Duration.ofMillis(connectionWaitMillis), Duration.ofMillis(500));
        ResilientPaymentProvider provider = provider(Duration.ofMillis(connectionWaitMillis), Duration.ofMillis(500));
        pg.delayEveryResponse(Duration.ofMillis(400));

        ExecutorService executor = Executors.newFixedThreadPool(20);
        List<Future<PaymentObservation>> futures = new ArrayList<>();
        try {
            futures.addAll(start(executor, provider, key, POOL_PER_ROUTE));
            awaitLookupsReceived(POOL_PER_ROUTE);
            futures.addAll(start(executor, provider, key, 20 - POOL_PER_ROUTE));
            int verified = 0;
            for (Future<PaymentObservation> future : futures) {
                if (future.get(10, TimeUnit.SECONDS).kind() == PaymentObservation.Kind.OBSERVED) {
                    verified++;
                }
            }
            System.out.printf("timeout-connection-wait,wait=%dms,verified=%d,pgLookups=%d,circuit=%s%n",
                connectionWaitMillis, verified, pg.lookupCalls(), provider.lookupCircuitBreaker().getState());

            assertThat(verified).isEqualTo(expectedVerified);
            assertThat(provider.lookupCircuitBreaker().getState()).isEqualTo(expectedState);
        } finally {
            executor.shutdownNow();
        }
    }

    /** adapter를 timeout 값으로 만들고 조회 대상 거래 하나를 만든다. 지연은 그 뒤에 건다. */
    private String prepare(Duration connectionWait, Duration lookupTimeout) {
        adapter = new PaymentProviderConfig().paymentProvider(pg.baseUrl(), "http://127.0.0.1:8080/api/v1/payments/callback",
            connectionWait, Duration.ofSeconds(1), lookupTimeout);
        return adapter.create(new PaymentRequest("LP-TIMEOUT-0", "TEST", "0000-0000-0000-0000", 7000))
            .transactions().getFirst().transactionKey();
    }

    /** 실제 설정처럼 조회 한 번의 추정 시간을 연결 대기 × 2 + 조회 응답 대기로 맞춘다. */
    private ResilientPaymentProvider provider(Duration connectionWait, Duration lookupTimeout) {
        LookupResilienceSettings settings = new LookupResilienceSettings(BASE.budget(),
            connectionWait.multipliedBy(2).plus(lookupTimeout), BASE.maxAttempts(), BASE.firstWait(), BASE.waitMultiplier(),
            BASE.windowSize(), BASE.minimumCalls(), BASE.failureRateThreshold(), BASE.openWait(), BASE.halfOpenCalls());
        return new ResilientPaymentProvider(adapter, settings, new TestClock());
    }

    private List<Future<PaymentObservation>> start(ExecutorService executor, ResilientPaymentProvider provider,
                                                   String key, int count) {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PaymentObservation>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(() -> {
                go.await();
                return provider.findByTransactionKey(key);
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
}
