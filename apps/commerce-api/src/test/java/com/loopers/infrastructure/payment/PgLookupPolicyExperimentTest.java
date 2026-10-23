package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.PaymentCreateCommand;
import com.loopers.application.payment.PaymentCreateObservation;
import com.loopers.application.payment.PaymentGateway;
import com.loopers.application.payment.PaymentLookupObservation;
import com.loopers.support.testpg.TestPgContainer;
import com.loopers.support.testpg.TestPgControl;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 전체 주문 SLO와 구분한 실제 외부 PG 조회 경계의 통제 실험이다.
class PgLookupPolicyExperimentTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void restore() {
        TestPgControl.lookupDelay(0);
        workers.shutdownNow();
    }

    @DisplayName("일시적인 조회 실패에서 Retry 적용 전후의 결과와 실제 HTTP 호출 수를 비교한다.")
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void comparesTransientFailureWithAndWithoutRetry(boolean byOrder, boolean retry) throws Exception {
        Fixture fixture = prepare(retry, false, false);
        TestPgControl.lookupFailures(1);
        Map<String, Object> call = measure(() -> byOrder ? fixture.gateway().lookupByOrder(fixture.orderId())
            : fixture.gateway().lookup(fixture.key()));
        assertThat(call.get("result")).isEqualTo(retry ? "Found" : "Unavailable");
        assertThat(actual(fixture)).isEqualTo(retry ? 2 : 1);
        assertThat(fixture.breaker().getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
        report("RETRY_TRANSIENT", fixture, Map.of("retry", retry, "byOrder", byOrder, "calls", List.of(call)));
    }

    @DisplayName("HALF_OPEN의 논리적 탐색 호출과 각 탐색의 HTTP 재시도 횟수를 구분한다.")
    @Test
    void countsHalfOpenLogicalProbesSeparatelyFromTheirRetries() throws Exception {
        Fixture fixture = prepare(true, false, false);
        List<Map<String, Object>> calls = new ArrayList<>();
        TestPgControl.lookupFailures(100);
        for (int index = 0; index < 5; index++) { calls.add(measure(() -> fixture.gateway().lookup(fixture.key()))); }
        assertThat(actual(fixture)).isEqualTo(15);
        assertThat(fixture.breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        for (int index = 0; index < 3; index++) { calls.add(measure(() -> fixture.gateway().lookup(fixture.key()))); }
        assertThat(actual(fixture)).isEqualTo(15);
        long opened = System.nanoTime();
        await().atMost(Duration.ofSeconds(4)).pollInterval(Duration.ofMillis(25))
            .until(() -> System.nanoTime() - opened >= Duration.ofSeconds(2).toNanos());
        for (int index = 0; index < 2; index++) {
            TestPgControl.lookupFailures(1);
            calls.add(measure(() -> fixture.gateway().lookup(fixture.key())));
        }
        assertThat(fixture.breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(actual(fixture)).isEqualTo(19);
        report("RETRY_HALF_OPEN", fixture, Map.of("logicalFaultCalls", 5, "blockedCalls", 3,
            "logicalProbeCalls", 2, "actualProbeHttpCalls", 4, "calls", calls));
    }

    @DisplayName("초당 50건의 조회에서 RateLimiter 적용 전후의 실제 호출 수와 로컬 차단을 비교한다.")
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void comparesFiftyLookupsPerSecondWithAndWithoutRateLimit(boolean enabled) throws Exception {
        Fixture fixture = prepare(true, enabled, false);
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        long started = System.nanoTime();
        for (int index = 0; index < 200; index++) {
            int sequence = index;
            futures.add(workers.submit(() -> {
                long scheduled = started + sequence * 20_000_000L;
                long remaining;
                while ((remaining = scheduled - System.nanoTime()) > 0) { LockSupport.parkNanos(remaining); }
                double lateness = millis(System.nanoTime() - scheduled);
                Map<String, Object> call = new LinkedHashMap<>(measure(() -> sequence % 2 == 0
                    ? fixture.gateway().lookup(fixture.key()) : fixture.gateway().lookupByOrder(fixture.orderId())));
                call.put("sequence", sequence);
                call.put("startLatenessMillis", lateness);
                return call;
            }));
        }
        List<Map<String, Object>> calls = new ArrayList<>();
        for (Future<Map<String, Object>> future : futures) { calls.add(future.get(10, TimeUnit.SECONDS)); }
        long rejected = calls.stream().filter(call -> "RATE_LIMITED".equals(call.get("kind"))).count();
        assertThat(actual(fixture)).isEqualTo(200 - (int) rejected);
        assertThat(fixture.breaker().getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(fixture.breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        if (enabled) {
            assertThat(actual(fixture)).isBetween(60, 100);
            assertThat(rejected).isPositive();
        } else {
            assertThat(actual(fixture)).isEqualTo(200);
            assertThat(rejected).isZero();
        }
        report("RATE_LIMIT", fixture, Map.of("rateLimiter", enabled, "arrivalsPerSecond", 50,
            "arrivalCount", 200, "arrivalWindowMillis", 4000, "locallyRejected", rejected,
            "maxStartLatenessMillis", calls.stream().mapToDouble(call -> (double) call.get("startLatenessMillis")).max().orElseThrow(),
            "calls", calls));
    }

    @DisplayName("재시도로 누적되는 조회 시간과 TimeLimiter가 제한한 전체 대기를 비교한다.")
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void comparesAccumulatedRetryTimeWithWholeLookupDeadline(boolean enabled) throws Exception {
        Fixture fixture = prepare(true, true, enabled);
        TestPgControl.lookupDelay(1500);
        List<Map<String, Object>> calls = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Map<String, Object> call = measure(() -> fixture.gateway().lookup(fixture.key()));
            calls.add(call);
            assertThat(call.get("kind")).isEqualTo(enabled ? "DEADLINE_EXCEEDED" : "TIMEOUT");
            assertThat((double) call.get("elapsedMillis")).isBetween(enabled ? 800.0 : 1600.0, enabled ? 1400.0 : 2600.0);
        }
        // 클라이언트 작업이 끝난 뒤 실제 호출 수를 확인한다.
        workers.shutdown();
        assertThat(workers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        assertThat(actual(fixture)).isEqualTo(enabled ? 6 : 9);
        assertThat(fixture.breaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(3);
        report("TIME_LIMIT", fixture, Map.of("timeLimiter", enabled, "deadlineMillis", 900,
            "pgLookupDelayMillis", 1500, "httpReadTimeoutMillis", 500,
            "workersTerminated", true, "calls", calls));
    }

    private Fixture prepare(boolean retry, boolean rate, boolean time) {
        TestPgControl.lookupDelay(0);
        TestPgPaymentGateway http = new TestPgPaymentGateway(new PgProperties(TestPgContainer.baseUrl(),
            "http://127.0.0.1:9/callback", TestPgContainer.PROVIDER_ACCOUNT_ID,
            Duration.ofSeconds(1), Duration.ofMillis(500)), JSON);
        String orderId = "LP-ORD-POLICY-" + UUID.randomUUID();
        String key = ((PaymentCreateObservation.Accepted) http.create(
            new PaymentCreateCommand(orderId, 7000, "TEST", "0000-0000-0000-0000"))).transactionKey();
        assertThat(http.lookup(key)).isInstanceOf(PaymentLookupObservation.Found.class);
        CircuitBreaker breaker = new PgLookupCircuitBreakerConfiguration().pgLookupCircuitBreaker(
            new PgLookupCircuitBreakerProperties(5, 5, 50, Duration.ofSeconds(2), 2));
        PgLookupPolicyProperties properties = new PgLookupPolicyProperties(
            new PgLookupPolicyProperties.RetrySettings(retry, 3, Duration.ofMillis(100), 2, 0.2),
            new PgLookupPolicyProperties.RateSettings(rate, 20, Duration.ofSeconds(1)),
            new PgLookupPolicyProperties.TimeSettings(time, Duration.ofMillis(900)));
        PaymentGateway gateway = new CircuitBreakingPaymentGateway(new ResilientPgLookupGateway(http, properties, workers), breaker);
        return new Fixture(gateway, breaker, orderId, key, 1);
    }

    private int actual(Fixture fixture) {
        return TestPgControl.stats(fixture.orderId()).lookupRequests() - fixture.before();
    }

    private Map<String, Object> measure(Supplier<PaymentLookupObservation> lookup) {
        long started = System.nanoTime();
        PaymentLookupObservation result = lookup.get();
        return Map.of("elapsedMillis", millis(System.nanoTime() - started), "result", result.getClass().getSimpleName(),
            "kind", result instanceof PaymentLookupObservation.Unavailable unavailable ? unavailable.kind().name() : "");
    }

    private void report(String stage, Fixture fixture, Map<String, Object> values) throws Exception {
        TestPgControl.Stats stats = TestPgControl.stats(fixture.orderId());
        assertThat(stats.createRequests()).isEqualTo(1);
        assertThat(stats.transactions()).isEqualTo(1);
        Map<String, Object> report = new LinkedHashMap<>(values);
        report.put("stage", stage);
        report.put("recordedAt", Instant.now().toString());
        report.put("actualPgLookups", actual(fixture));
        report.put("createRequests", stats.createRequests());
        report.put("transactions", stats.transactions());
        report.put("circuitState", fixture.breaker().getState().name());
        report.put("bufferedLogicalCalls", fixture.breaker().getMetrics().getNumberOfBufferedCalls());
        report.put("failedLogicalCalls", fixture.breaker().getMetrics().getNumberOfFailedCalls());
        System.out.println("PG_LOOKUP_POLICY " + JSON.writeValueAsString(report));
    }

    private static double millis(long nanos) { return Math.round(nanos / 1000.0) / 1000.0; }
    private record Fixture(PaymentGateway gateway, CircuitBreaker breaker, String orderId, String key, int before) {}
}
