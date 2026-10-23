package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.PaymentCreateCommand;
import com.loopers.application.payment.PaymentCreateObservation;
import com.loopers.application.payment.PaymentLookupObservation;
import com.loopers.support.testpg.TestPgContainer;
import com.loopers.support.testpg.TestPgControl;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 별도 PG 컨테이너의 지연을 제어하고 실제 HTTP와 회로의 자연 전이를 관측한다.
class PgLookupCircuitBreakerExperimentTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration OPEN_WAIT = Duration.ofSeconds(2);

    @AfterEach
    void restore() {
        TestPgControl.lookupDelay(0);
    }

    @ParameterizedTest(name = "실제 PG 지연·재차단·회복, 주문 단위 조회={0}")
    @ValueSource(booleans = {false, true})
    void observesHttpBlockingReopeningAndRecovery(boolean byOrder) throws Exception {
        TestPgControl.lookupDelay(0);
        String orderId = "LP-ORD-CIRCUIT-" + UUID.randomUUID();
        TestPgPaymentGateway http = new TestPgPaymentGateway(new PgProperties(TestPgContainer.baseUrl(),
            "http://127.0.0.1:9/api/v1/payments/callback", TestPgContainer.PROVIDER_ACCOUNT_ID,
            Duration.ofSeconds(1), Duration.ofMillis(500)), JSON);
        CircuitBreaker breaker = new PgLookupCircuitBreakerConfiguration().pgLookupCircuitBreaker(
            new PgLookupCircuitBreakerProperties(5, 5, 50, OPEN_WAIT, 2));
        CircuitBreakingPaymentGateway gateway = new CircuitBreakingPaymentGateway(http, breaker);
        List<String> transitions = new ArrayList<>();
        breaker.getEventPublisher().onStateTransition(event -> transitions.add(event.getStateTransition().name()));
        String key = ((PaymentCreateObservation.Accepted) gateway.create(
            new PaymentCreateCommand(orderId, 7000, "TEST", "0000-0000-0000-0000"))).transactionKey();
        Supplier<PaymentLookupObservation> lookup = byOrder ? () -> gateway.lookupByOrder(orderId) : () -> gateway.lookup(key);
        List<Map<String, Object>> calls = new ArrayList<>();

        TestPgControl.lookupDelay(1500);
        for (int index = 0; index < 5; index++) {
            assertThat(measure("FAULT", lookup, breaker, calls)).isInstanceOf(PaymentLookupObservation.Unavailable.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        for (int index = 0; index < 3; index++) {
            assertThat(measure("BLOCKED", lookup, breaker, calls))
                .isEqualTo(new PaymentLookupObservation.Unavailable("CIRCUIT_OPEN", PaymentLookupObservation.FailureKind.CIRCUIT_OPEN));
        }
        assertThat(TestPgControl.stats(orderId).lookupRequests()).isEqualTo(5);

        awaitOpenWait();
        measure("UNRECOVERED_PROBE", lookup, breaker, calls);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        measure("UNRECOVERED_PROBE", lookup, breaker, calls);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(TestPgControl.stats(orderId).lookupRequests()).isEqualTo(7);

        TestPgControl.lookupDelay(0);
        awaitOpenWait();
        assertThat(measure("RECOVERED_PROBE", lookup, breaker, calls)).isInstanceOf(PaymentLookupObservation.Found.class);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(measure("RECOVERED_PROBE", lookup, breaker, calls)).isInstanceOf(PaymentLookupObservation.Found.class);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(transitions).containsExactly("CLOSED_TO_OPEN", "OPEN_TO_HALF_OPEN", "HALF_OPEN_TO_OPEN",
            "OPEN_TO_HALF_OPEN", "HALF_OPEN_TO_CLOSED");
        TestPgControl.Stats stats = TestPgControl.stats(orderId);
        assertThat(stats.lookupRequests()).isEqualTo(9);
        assertThat(stats.createRequests()).isEqualTo(1);
        assertThat(stats.transactions()).isEqualTo(1);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("recordedAt", Instant.now().toString());
        report.put("lookupMode", byOrder ? "ORDER" : "TRANSACTION");
        report.put("httpReadTimeoutMillis", 500);
        report.put("faultDelayMillis", 1500);
        report.put("slidingWindowSize", 5);
        report.put("minimumNumberOfCalls", 5);
        report.put("failureRateThreshold", 50);
        report.put("openWaitMillis", OPEN_WAIT.toMillis());
        report.put("halfOpenProbeLimit", 2);
        report.put("transitions", transitions);
        report.put("calls", calls);
        report.put("actualPgLookupRequests", stats.lookupRequests());
        report.put("totalPgCreateRequests", stats.createRequests());
        report.put("totalPgTransactions", stats.transactions());
        System.out.println("PG_LOOKUP_CIRCUIT " + JSON.writeValueAsString(report));
    }

    private static PaymentLookupObservation measure(String phase, Supplier<PaymentLookupObservation> lookup,
                                                    CircuitBreaker breaker, List<Map<String, Object>> calls) {
        CircuitBreaker.State before = breaker.getState();
        long started = System.nanoTime();
        PaymentLookupObservation observation = lookup.get();
        double elapsedMillis = Math.round((System.nanoTime() - started) / 1000.0) / 1000.0;
        calls.add(Map.of("phase", phase, "stateBefore", before.name(), "stateAfter", breaker.getState().name(),
            "elapsedMillis", elapsedMillis, "observation", observation.getClass().getSimpleName(),
            "reason", observation instanceof PaymentLookupObservation.Unavailable unavailable ? unavailable.reason() : ""));
        return observation;
    }

    private static void awaitOpenWait() {
        long started = System.nanoTime();
        await().pollInterval(Duration.ofMillis(25)).atMost(OPEN_WAIT.plusSeconds(2))
            .until(() -> System.nanoTime() - started >= OPEN_WAIT.toNanos());
    }
}
