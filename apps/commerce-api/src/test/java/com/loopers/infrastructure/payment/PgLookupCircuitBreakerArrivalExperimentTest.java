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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

// 외부 조회 경계에 같은 도착률을 주는 비교다. 전체 주문 부하·운영 SLO 측정과 구분한다.
class PgLookupCircuitBreakerArrivalExperimentTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int RATE = 10;
    private static final int CALLS = 40;
    private static final int WORKERS = 10;

    @AfterEach
    void restore() {
        TestPgControl.lookupDelay(0);
    }

    @DisplayName("초당 10건의 같은 조회 유입에서 회로 적용 전후의 실제 PG 호출 수와 대기를 비교한다.")
    @ParameterizedTest(name = "조회 10건/초, 주문 단위={0}, 회로={1}")
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void comparesTheSameArrivalsWithAndWithoutCircuit(boolean byOrder, boolean enabled) throws Exception {
        TestPgControl.lookupDelay(0);
        String orderId = "LP-ORD-ARRIVAL-" + UUID.randomUUID();
        TestPgPaymentGateway http = new TestPgPaymentGateway(new PgProperties(TestPgContainer.baseUrl(),
            "http://127.0.0.1:9/api/v1/payments/callback", TestPgContainer.PROVIDER_ACCOUNT_ID,
            Duration.ofSeconds(1), Duration.ofMillis(500)), JSON);
        CircuitBreaker breaker = new PgLookupCircuitBreakerConfiguration().pgLookupCircuitBreaker(
            new PgLookupCircuitBreakerProperties(5, 5, 50, Duration.ofSeconds(2), 2));
        List<String> transitions = new CopyOnWriteArrayList<>();
        breaker.getEventPublisher().onStateTransition(event -> transitions.add(event.getStateTransition().name()));
        PaymentGateway gateway = enabled ? new CircuitBreakingPaymentGateway(http, breaker) : http;
        String key = ((PaymentCreateObservation.Accepted) gateway.create(new PaymentCreateCommand(
            orderId, 7000, "TEST", "0000-0000-0000-0000"))).transactionKey();
        assertThat(http.lookup(key)).isInstanceOf(PaymentLookupObservation.Found.class);
        int before = TestPgControl.stats(orderId).lookupRequests();
        TestPgControl.lookupDelay(1500);

        List<Map<String, Object>> calls = new ArrayList<>();
        long started = System.nanoTime();
        try (var executor = Executors.newFixedThreadPool(WORKERS)) {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (int index = 0; index < CALLS; index++) {
                int sequence = index;
                futures.add(executor.submit(() -> observe(sequence, started, gateway, breaker, byOrder, key, orderId)));
            }
            for (var future : futures) {
                calls.add(future.get(15, TimeUnit.SECONDS));
            }
        }

        long blocked = calls.stream().filter(call -> "CIRCUIT_OPEN".equals(call.get("reason"))).count();
        TestPgControl.Stats after = TestPgControl.stats(orderId);
        int actualLookups = after.lookupRequests() - before;
        assertThat(calls).hasSize(CALLS);
        assertThat(calls).allSatisfy(call -> assertThat(call.get("observation")).isEqualTo("Unavailable"));
        assertThat(actualLookups).isEqualTo(CALLS - (int) blocked);
        assertThat(after.createRequests()).isEqualTo(1);
        assertThat(after.transactions()).isEqualTo(1);
        if (enabled) {
            assertThat(blocked).isPositive();
            assertThat(actualLookups).isLessThan(CALLS);
            assertThat(transitions).contains("CLOSED_TO_OPEN");
        } else {
            assertThat(blocked).isZero();
            assertThat(actualLookups).isEqualTo(CALLS);
            assertThat(transitions).isEmpty();
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("recordedAt", Instant.now().toString());
        report.put("lookupMode", byOrder ? "ORDER" : "TRANSACTION");
        report.put("breakerEnabled", enabled);
        report.put("targetArrivalsPerSecond", RATE);
        report.put("arrivalWindowMillis", CALLS * 1000 / RATE);
        report.put("workerLimit", WORKERS);
        report.put("httpReadTimeoutMillis", 500);
        report.put("pgLookupDelayMillis", 1500);
        report.put("warmupLookups", 1);
        report.put("calls", calls);
        report.put("blockedLocally", blocked);
        report.put("actualPgLookupRequests", actualLookups);
        report.put("totalPgCreateRequests", after.createRequests());
        report.put("totalPgTransactions", after.transactions());
        report.put("transitions", transitions);
        System.out.println("PG_LOOKUP_ARRIVAL " + JSON.writeValueAsString(report));
    }

    private static Map<String, Object> observe(int sequence, long batchStarted, PaymentGateway gateway,
                                               CircuitBreaker breaker, boolean byOrder, String key, String orderId) {
        long scheduled = batchStarted + sequence * (1_000_000_000L / RATE);
        long remaining;
        while ((remaining = scheduled - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
        }
        long started = System.nanoTime();
        String before = breaker.getState().name();
        PaymentLookupObservation observation = byOrder ? gateway.lookupByOrder(orderId) : gateway.lookup(key);
        return Map.of("sequence", sequence, "scheduledMillis", sequence * 1000 / RATE,
            "startedMillis", millis(started - batchStarted), "startLatenessMillis", millis(started - scheduled),
            "elapsedMillis", millis(System.nanoTime() - started), "stateBefore", before,
            "stateAfter", breaker.getState().name(), "observation", observation.getClass().getSimpleName(),
            "reason", observation instanceof PaymentLookupObservation.Unavailable unavailable ? unavailable.reason() : "");
    }

    private static double millis(long nanos) {
        return Math.round(nanos / 1000.0) / 1000.0;
    }
}
