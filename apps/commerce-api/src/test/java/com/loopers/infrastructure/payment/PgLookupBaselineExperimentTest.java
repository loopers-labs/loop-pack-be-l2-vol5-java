package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.PaymentCreateCommand;
import com.loopers.application.payment.PaymentCreateObservation;
import com.loopers.application.payment.PaymentLookupObservation;
import com.loopers.support.testpg.TestPgContainer;
import com.loopers.support.testpg.TestPgControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

// 회로 미적용 기준선이다. 주문 상태 반영과 구분해 실제 HTTP 호출 수와 대기를 측정한다.
class PgLookupBaselineExperimentTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration READ_TIMEOUT = Duration.ofMillis(500);
    private static final int CALLS = 8;
    private String providerOrderId;

    @BeforeEach
    void prepare() throws Exception {
        providerOrderId = "LP-ORD-BASELINE-" + UUID.randomUUID();
        delay(0);
    }

    @AfterEach
    void restore() throws Exception {
        delay(0);
    }

    @ParameterizedTest(name = "Circuit Breaker 미적용 정상 조회, 주문 단위={0}")
    @ValueSource(booleans = {false, true})
    void measuresNormalLookups(boolean byOrder) throws Exception {
        measure(byOrder, 0);
    }

    @ParameterizedTest(name = "Circuit Breaker 미적용 지연 조회, 주문 단위={0}")
    @ValueSource(booleans = {false, true})
    void measuresTimedOutLookups(boolean byOrder) throws Exception {
        measure(byOrder, 1500);
    }

    private void measure(boolean byOrder, long delayMillis) throws Exception {
        TestPgPaymentGateway gateway = new TestPgPaymentGateway(new PgProperties(
            TestPgContainer.baseUrl(), "http://127.0.0.1:9/api/v1/payments/callback",
            TestPgContainer.PROVIDER_ACCOUNT_ID, Duration.ofSeconds(1), READ_TIMEOUT), JSON);
        PaymentCreateObservation created = gateway.create(new PaymentCreateCommand(
            providerOrderId, 7000, "TEST", "0000-0000-0000-0000"));
        assertThat(created).isInstanceOf(PaymentCreateObservation.Accepted.class);
        String key = ((PaymentCreateObservation.Accepted) created).transactionKey();
        Supplier<PaymentLookupObservation> lookup = byOrder
            ? () -> gateway.lookupByOrder(providerOrderId) : () -> gateway.lookup(key);
        assertThat(lookup.get()).isInstanceOf(PaymentLookupObservation.Found.class);
        delay(delayMillis);
        TestPgControl.Stats before = TestPgControl.stats(providerOrderId);
        List<Double> elapsedMillis = new ArrayList<>();
        List<String> observations = new ArrayList<>();
        for (int index = 0; index < CALLS; index++) {
            long started = System.nanoTime();
            PaymentLookupObservation observation = lookup.get();
            elapsedMillis.add(Math.round((System.nanoTime() - started) / 1000.0) / 1000.0);
            observations.add(observation.getClass().getSimpleName());
            assertThat(observation).isInstanceOf(delayMillis == 0
                ? PaymentLookupObservation.Found.class : PaymentLookupObservation.Unavailable.class);
        }
        TestPgControl.Stats after = TestPgControl.stats(providerOrderId);
        assertThat(after.lookupRequests() - before.lookupRequests()).isEqualTo(CALLS);
        assertThat(after.createRequests()).isEqualTo(1);
        assertThat(after.transactions()).isEqualTo(1);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordedAt", Instant.now().toString());
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("breakerEnabled", false);
        result.put("lookupMode", byOrder ? "ORDER" : "TRANSACTION");
        result.put("httpReadTimeoutMillis", READ_TIMEOUT.toMillis());
        result.put("pgLookupDelayMillis", delayMillis);
        result.put("calls", CALLS);
        result.put("concurrency", 1);
        result.put("warmupLookups", 1);
        result.put("elapsedMillis", elapsedMillis);
        result.put("observations", observations);
        result.put("actualPgLookupRequests", after.lookupRequests() - before.lookupRequests());
        result.put("totalPgCreateRequests", after.createRequests());
        result.put("totalPgTransactions", after.transactions());
        System.out.println("PG_LOOKUP_BASELINE " + JSON.writeValueAsString(result));
    }

    private static void delay(long millis) throws Exception {
        TestPgControl.lookupDelay(millis);
    }
}
