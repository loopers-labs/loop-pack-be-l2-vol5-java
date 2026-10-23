package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentRequest;
import com.loopers.support.TestClock;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.support.payment.LookupSettingsFixture;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 연결을 빌리지 못한 실패를 회로가 셀 때(현재 정책)와 세지 않을 때(후보 정책)를 같은 장면에서 비교한다.
 * 실제 adapter는 두 실패를 같은 TEMPORARY_FAILURE로 돌려줘 밖에서 구분할 수 없으므로, 이 테스트 안에서만
 * adapter와 같은 HTTP 설정(같은 주소당 연결 5개, 빌리기 대기 300ms, 응답 대기 500ms, 자동 재시도 끔)으로 조회하고
 * 연결 빌리기 timeout만 따로 표시한다. retry·회로 설정과 감싸는 순서(retry → 회로)는 학습값과 같다. 제품 코드는 바꾸지 않는다.
 */
@Tag("experiment")
class PaymentLookupPoolFailureCountingExperimentTest {

    private static final int CONCURRENT = 20;
    private static final int POOL_PER_ROUTE = 5;
    private static final LookupResilienceSettings SETTINGS = LookupSettingsFixture.LEARNING;

    private FakePaymentServer pg;
    private CloseableHttpClient httpClient;
    private RestClient lookupClient;
    private String transactionKey;

    @BeforeEach
    void setUp() throws Exception {
        pg = new FakePaymentServer();
        try (TestPgPaymentAdapter adapter = new PaymentProviderConfig().paymentProvider(pg.baseUrl(),
            "http://127.0.0.1:8080/api/v1/payments/callback", Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(500))) {
            transactionKey = adapter.create(new PaymentRequest("LP-POOL-COUNT-0", "TEST", "0000-0000-0000-0000", 7000))
                .transactions().getFirst().transactionKey();
        }
        httpClient = HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().build();
        var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(Duration.ofMillis(300));
        factory.setConnectionRequestTimeout(Duration.ofMillis(300));
        factory.setReadTimeout(Duration.ofMillis(500));
        lookupClient = RestClient.builder().baseUrl(pg.baseUrl()).requestFactory(factory)
            .defaultHeader("X-USER-ID", "135135").build();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            httpClient.close();
        } finally {
            pg.close();
        }
    }

    @ParameterizedTest(name = "pg={0}ms, countPoolFailure={1}")
    @CsvSource({"400, true", "400, false", "2000, true", "2000, false"})
    void comparesCountingConnectionShortageAsPgFailure(long pgResponseMillis, boolean countPoolFailure) throws Exception {
        pg.delayEveryResponse(Duration.ofMillis(pgResponseMillis));
        CircuitBreaker circuit = circuitBreaker(countPoolFailure);
        AtomicLong openedAtNanos = new AtomicLong();
        circuit.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition().getToState() == State.OPEN) {
                openedAtNanos.compareAndSet(0, System.nanoTime());
            }
        });
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger poolFailures = new AtomicInteger();
        AtomicInteger pgFailures = new AtomicInteger();

        long started = System.nanoTime();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT);
        List<Future<Attempt>> futures = new ArrayList<>();
        try {
            futures.addAll(start(executor, POOL_PER_ROUTE, circuit, attempts, poolFailures, pgFailures));
            awaitLookupsReceived(POOL_PER_ROUTE);
            futures.addAll(start(executor, CONCURRENT - POOL_PER_ROUTE, circuit, attempts, poolFailures, pgFailures));

            int verified = 0;
            int blocked = 0;
            int exhausted = 0;
            for (Future<Attempt> future : futures) {
                Attempt result = future.get(10, TimeUnit.SECONDS);
                switch (result) {
                    case OBSERVED -> verified++;
                    case BLOCKED -> blocked++;
                    default -> exhausted++;
                }
            }
            long openedAtMillis = openedAtNanos.get() == 0 ? -1 : Duration.ofNanos(openedAtNanos.get() - started).toMillis();
            System.out.printf("pool-count-experiment,pg=%dms,countPoolFailure=%s,verified=%d,blockedByCircuit=%d,"
                    + "retryExhausted=%d,attempts=%d,poolFailures=%d,pgFailures=%d,pgReceived=%d,circuit=%s,openedAtMs=%d%n",
                pgResponseMillis, countPoolFailure, verified, blocked, exhausted, attempts.get(), poolFailures.get(),
                pgFailures.get(), pg.lookupCalls(), circuit.getState(), openedAtMillis);

            if (pgResponseMillis < 500) {
                // PG가 정상이면, 연결 부족을 세는 정책만 회로를 연다.
                assertThat(circuit.getState()).isEqualTo(countPoolFailure ? State.OPEN : State.CLOSED);
                assertThat(pgFailures.get()).isZero();
                assertThat(verified).isGreaterThanOrEqualTo(POOL_PER_ROUTE);
            } else {
                // PG가 느리면, 연결 부족을 세지 않아도 PG까지 간 요청의 timeout으로 회로가 열린다.
                assertThat(circuit.getState()).isEqualTo(State.OPEN);
                assertThat(verified).isZero();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private List<Future<Attempt>> start(ExecutorService executor, int count, CircuitBreaker circuit,
                                        AtomicInteger attempts, AtomicInteger poolFailures, AtomicInteger pgFailures) {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Attempt>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(() -> {
                go.await();
                return lookupWithPolicy(circuit, attempts, poolFailures, pgFailures);
            }));
        }
        go.countDown();
        return futures;
    }

    /** ResilientPaymentProvider와 같은 retry 조건(일시적 실패, 남은 예산)과 순서(retry → 회로 → GET)로 감싼다. */
    private Attempt lookupWithPolicy(CircuitBreaker circuit, AtomicInteger attempts,
                                     AtomicInteger poolFailures, AtomicInteger pgFailures) {
        long deadline = System.nanoTime() + SETTINGS.budget().toNanos();
        Retry retry = Retry.of("poolCountExperiment", RetryConfig.<Attempt>custom()
            .maxAttempts(SETTINGS.maxAttempts())
            .intervalFunction(IntervalFunction.ofExponentialBackoff(SETTINGS.firstWait(), SETTINGS.waitMultiplier()))
            .retryOnResult(result -> result.isTemporary() && hasTimeForAnotherAttempt(deadline))
            .ignoreExceptions(CallNotPermittedException.class)
            .build());
        try {
            return retry.executeSupplier(() -> circuit.executeSupplier(() -> {
                attempts.incrementAndGet();
                Attempt result = getOnce();
                if (result == Attempt.POOL_EXHAUSTED) {
                    poolFailures.incrementAndGet();
                } else if (result == Attempt.PG_FAILURE) {
                    pgFailures.incrementAndGet();
                }
                return result;
            }));
        } catch (CallNotPermittedException exception) {
            return Attempt.BLOCKED;
        }
    }

    private Attempt getOnce() {
        try {
            lookupClient.get().uri("/api/v1/payments/{transactionKey}", transactionKey).retrieve().toBodilessEntity();
            return Attempt.OBSERVED;
        } catch (ResourceAccessException exception) {
            return exception.getCause() instanceof ConnectionRequestTimeoutException ? Attempt.POOL_EXHAUSTED : Attempt.PG_FAILURE;
        } catch (HttpServerErrorException exception) {
            return Attempt.PG_FAILURE;
        }
    }

    private static boolean hasTimeForAnotherAttempt(long deadline) {
        Duration longestWait = Duration.ofMillis(200);
        return deadline - System.nanoTime() >= longestWait.plus(SETTINGS.attemptEstimate()).toNanos();
    }

    private static CircuitBreaker circuitBreaker(boolean countPoolFailure) {
        return CircuitBreaker.of("poolCountExperiment", CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(SETTINGS.windowSize())
            .minimumNumberOfCalls(SETTINGS.minimumCalls())
            .failureRateThreshold(SETTINGS.failureRateThreshold())
            .waitDurationInOpenState(SETTINGS.openWait())
            .permittedNumberOfCallsInHalfOpenState(SETTINGS.halfOpenCalls())
            .automaticTransitionFromOpenToHalfOpenEnabled(false)
            .recordResult(result -> result == Attempt.PG_FAILURE || (countPoolFailure && result == Attempt.POOL_EXHAUSTED))
            .clock(new TestClock())
            .build());
    }

    private void awaitLookupsReceived(int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (pg.lookupCalls() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(pg.lookupCalls()).isEqualTo(count);
    }

    private enum Attempt {
        OBSERVED, POOL_EXHAUSTED, PG_FAILURE, BLOCKED;

        boolean isTemporary() {
            return this == POOL_EXHAUSTED || this == PG_FAILURE;
        }
    }
}
