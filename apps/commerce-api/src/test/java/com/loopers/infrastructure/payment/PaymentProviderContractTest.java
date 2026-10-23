package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.support.payment.FakePaymentServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PaymentProviderContractTest {

    private static final String CALLBACK_URL = "http://127.0.0.1:8080/api/v1/payments/callback";
    private final ObjectMapper mapper = new ObjectMapper();
    private FakePaymentServer pg;
    private TestPgPaymentAdapter provider;

    @BeforeEach
    void setUp() throws Exception {
        pg = new FakePaymentServer();
        provider = new PaymentProviderConfig().paymentProvider(pg.baseUrl(), CALLBACK_URL,
            Duration.ofMillis(300), Duration.ofMillis(500), Duration.ofMillis(500));
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            provider.close();
        } finally {
            pg.close();
        }
    }

    @Test
    void createsPendingTransactionUsingTheExactHttpContractAndLooksUpSuccess() throws Exception {
        PaymentObservation created = provider.create(payment());

        assertThat(created.kind()).isEqualTo(PaymentObservation.Kind.OBSERVED);
        assertThat(created.transactions()).containsExactly(
            new PaymentTransaction("TEST-TX-1", "LP-ORD-0000000042", 7000, PaymentTransaction.Status.PENDING));
        var request = pg.requests().getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/api/v1/payments");
        assertThat(request.userId()).isEqualTo("135135");
        assertThat(request.contentType()).startsWith("application/json");
        JsonNode body = mapper.readTree(request.body());
        assertThat(body.size()).isEqualTo(5);
        assertThat(body.path("orderId").isTextual()).isTrue();
        assertThat(body.path("orderId").asText()).isEqualTo("LP-ORD-0000000042");
        assertThat(body.path("amount").isIntegralNumber()).isTrue();
        assertThat(body.path("amount").asLong()).isEqualTo(7000);
        assertThat(body.path("cardType").asText()).isEqualTo("TEST");
        assertThat(body.path("cardNo").asText()).isEqualTo("0000-0000-0000-0000");
        assertThat(body.path("callbackUrl").asText()).isEqualTo(CALLBACK_URL);
        pg.changeStatus("TEST-TX-1", "SUCCESS");

        PaymentObservation found = provider.findByTransactionKey("TEST-TX-1");

        assertThat(found.transactions()).containsExactly(
            new PaymentTransaction("TEST-TX-1", "LP-ORD-0000000042", 7000, PaymentTransaction.Status.SUCCESS));
        assertThat(pg.requests().getLast().path()).isEqualTo("/api/v1/payments/TEST-TX-1");
        assertThat(pg.requests().getLast().method()).isEqualTo("GET");
        assertThat(pg.requests().getLast().userId()).isEqualTo("135135");
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isEqualTo(1);
    }

    @Test
    void responseLossLeavesOneExternalTransactionAndNeverRetriesCreate() {
        pg.dropNextCreateResponse();

        PaymentObservation created = provider.create(payment());

        assertThat(created).isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.TEMPORARY_FAILURE));
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isEqualTo(1);
        pg.changeStatus("TEST-TX-1", "SUCCESS");

        PaymentObservation recovered = provider.findByOrderId("LP-ORD-0000000042");

        assertThat(recovered.transactions()).containsExactly(
            new PaymentTransaction("TEST-TX-1", "LP-ORD-0000000042", 7000, PaymentTransaction.Status.SUCCESS));
        assertThat(pg.requests().getLast().path()).isEqualTo("/api/v1/payments");
        assertThat(pg.requests().getLast().rawQuery()).isEqualTo("orderId=LP-ORD-0000000042");
        assertThat(pg.requests().getLast().userId()).isEqualTo("135135");
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isEqualTo(1);
    }

    @Test
    void repeatingCreateForTheSameOrderProducesTwoExternalTransactions() {
        provider.create(payment());
        provider.create(payment());

        PaymentObservation found = provider.findByOrderId("LP-ORD-0000000042");

        assertThat(found.transactions()).extracting(PaymentTransaction::transactionKey)
            .containsExactlyInAnyOrder("TEST-TX-1", "TEST-TX-2");
        assertThat(pg.createCalls()).isEqualTo(2);
        assertThat(pg.transactionCount()).isEqualTo(2);
    }

    @Test
    void responseTimeoutLimitsWaitingButDoesNotRemoveTheExternalTransaction() {
        pg.holdNextCreateResponse();

        PaymentObservation result = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> provider.create(payment()));

        assertThat(result).isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.TEMPORARY_FAILURE));
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isEqualTo(1);
        assertThat(provider.findByOrderId("LP-ORD-0000000042").transactions()).hasSize(1);
    }

    @Test
    void missingTransactionAndEmptyOrderLookupAreObservedAbsenceRatherThanFailure() {
        assertThat(provider.findByTransactionKey("missing-key")).isEqualTo(PaymentObservation.observed(List.of()));
        assertThat(provider.findByOrderId("LP-ORD-0000000042")).isEqualTo(PaymentObservation.observed(List.of()));
        assertThat(pg.createCalls()).isZero();
        assertThat(pg.lookupCalls()).isEqualTo(2);
        assertThat(pg.transactionCount()).isZero();
    }

    @Test
    void encodesOrderIdAsOneQueryValue() {
        String orderId = "ORDER +/?=&한글";

        assertThat(provider.findByOrderId(orderId).transactions()).isEmpty();

        String rawQuery = pg.requests().getFirst().rawQuery();
        assertThat(rawQuery).doesNotContain("+", "&", "?");
        assertThat(URLDecoder.decode(rawQuery, StandardCharsets.UTF_8)).isEqualTo("orderId=" + orderId);
        assertThat(pg.lookupCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not-json",
        "{}",
        "{\"transactionKey\":\"T-1\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":7000,\"status\":\"INVALID\"}",
        "{\"transactionKey\":\"T-1\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":\"7000\",\"status\":\"PENDING\"}",
        "{\"transactionKey\":\"T-1\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":7000,\"status\":\"SUCCESS\"}"
    })
    void invalidOrNonPendingCreateResponseIsUnknown(String body) {
        pg.replyNext(202, body);

        assertThat(provider.create(payment())).isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.UNEXPECTED_RESPONSE));
        assertThat(pg.createCalls()).isEqualTo(1);
    }

    @Test
    void mismatchedCreateResponseIsPassedOnAsObservedForResultCheck() {
        pg.replyNext(202, "{\"transactionKey\":\"T-1\",\"orderId\":\"OTHER-ORDER\",\"amount\":8000,\"status\":\"PENDING\"}");

        assertThat(provider.create(payment())).isEqualTo(PaymentObservation.observed(List.of(
            new PaymentTransaction("T-1", "OTHER-ORDER", 8000, PaymentTransaction.Status.PENDING))));
        assertThat(pg.createCalls()).isEqualTo(1);
    }

    @Test
    void provenRejectionBeforeCreateIsRejectedWithoutTransaction() {
        pg.replyNext(422, "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"LP-ORD-0000000042\","
            + "\"amount\":7000,\"transactionCreated\":false}");

        assertThat(provider.create(payment())).isEqualTo(PaymentObservation.rejected());
        assertThat(pg.transactionCount()).isZero();
        assertThat(pg.createCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"code\":\"OTHER\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":7000,\"transactionCreated\":false}",
        "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":7000,\"transactionCreated\":true}",
        "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":7000,\"transactionCreated\":\"false\"}",
        "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":7000}",
        "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"OTHER-ORDER\",\"amount\":7000,\"transactionCreated\":false}",
        "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":8000,\"transactionCreated\":false}",
        "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"LP-ORD-0000000042\",\"amount\":18446744073709558616,\"transactionCreated\":false}",
        "not-json"
    })
    void rejectionWithoutCompleteEvidenceIsUnknown(String body) {
        pg.replyNext(422, body);

        assertThat(provider.create(payment())).isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.UNEXPECTED_RESPONSE));
        assertThat(pg.createCalls()).isEqualTo(1);
    }

    @Test
    void serverErrorAndUnprovenRejectionAreUnknownWithoutRetries() {
        pg.replyNext(503, "");
        assertThat(provider.create(payment())).isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.TEMPORARY_FAILURE));
        pg.replyNext(422, "");
        assertThat(provider.create(payment())).isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.UNEXPECTED_RESPONSE));
        pg.replyNext(503, "");
        assertThat(provider.findByOrderId("LP-ORD-0000000042")).isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.TEMPORARY_FAILURE));
        assertThat(pg.createCalls()).isEqualTo(2);
        assertThat(pg.lookupCalls()).isEqualTo(1);
    }

    @Test
    void lookupTimeoutAndServerErrorAreTemporaryFailures() {
        provider.create(payment());
        pg.delayNextResponse(Duration.ofSeconds(1));

        PaymentObservation slow = assertTimeoutPreemptively(Duration.ofSeconds(2),
            () -> provider.findByTransactionKey("TEST-TX-1"));
        pg.replyNext(503, "");
        PaymentObservation unavailable = provider.findByOrderId("LP-ORD-0000000042");

        assertThat(slow).isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.TEMPORARY_FAILURE));
        assertThat(unavailable).isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.TEMPORARY_FAILURE));
        assertThat(pg.lookupCalls()).isEqualTo(2);
    }

    @Test
    void lookupWaitsLessThanCreate() throws Exception {
        try (TestPgPaymentAdapter split = new PaymentProviderConfig().paymentProvider(pg.baseUrl(), CALLBACK_URL,
            Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(300))) {
            pg.delayNextResponse(Duration.ofMillis(600));
            assertThat(split.create(payment()).kind()).isEqualTo(PaymentObservation.Kind.OBSERVED);

            pg.delayNextResponse(Duration.ofMillis(600));
            assertThat(split.findByTransactionKey("TEST-TX-1"))
                .isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.TEMPORARY_FAILURE));
        }
    }

    @Test
    void unreachablePgIsATemporaryFailure() throws Exception {
        FakePaymentServer stopped = new FakePaymentServer();
        String stoppedUrl = stopped.baseUrl();
        stopped.close();

        try (TestPgPaymentAdapter unreachable = new PaymentProviderConfig().paymentProvider(stoppedUrl, CALLBACK_URL,
            Duration.ofMillis(300), Duration.ofMillis(500), Duration.ofMillis(500))) {
            assertThat(unreachable.findByOrderId("LP-ORD-0000000042")).isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.TEMPORARY_FAILURE));
        }
    }

    @Test
    void createThatCannotConnectIsProvenNotSent() throws Exception {
        FakePaymentServer stopped = new FakePaymentServer();
        String stoppedUrl = stopped.baseUrl();
        stopped.close();

        try (TestPgPaymentAdapter unreachable = new PaymentProviderConfig().paymentProvider(stoppedUrl, CALLBACK_URL,
            Duration.ofMillis(300), Duration.ofMillis(500), Duration.ofMillis(500))) {
            assertThat(unreachable.create(payment())).isEqualTo(PaymentObservation.notSent());
        }
    }

    @Test
    void createThatCannotBorrowAConnectionIsProvenNotSent() throws Exception {
        String transactionKey = provider.create(payment()).transactions().getFirst().transactionKey();
        pg.delayEveryResponse(Duration.ofMillis(450));
        var executor = Executors.newFixedThreadPool(5);
        try {
            // 조회 5개가 연결 5개를 모두 잡은 동안, 생성 POST는 연결 빌리기 대기(300ms)에서 끝나 PG에 닿지 않는다.
            for (int i = 0; i < 5; i++) {
                executor.submit(() -> provider.findByTransactionKey(transactionKey));
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (pg.lookupCalls() < 5 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }

            assertThat(provider.create(payment())).isEqualTo(PaymentObservation.notSent());
            assertThat(pg.createCalls()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createWhoseResponseTimesOutIsNotProvenNotSent() {
        pg.delayNextResponse(Duration.ofMillis(800));

        assertThat(provider.create(payment()))
            .isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.TEMPORARY_FAILURE));
        assertThat(pg.createCalls()).isEqualTo(1);
    }

    @Test
    void unusableLookupResponseIsAnUnexpectedResponse() {
        pg.replyNext(401, "");
        assertThat(provider.findByTransactionKey("TEST-TX-1")).isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.UNEXPECTED_RESPONSE));
        pg.replyNext(200, "not-json");
        assertThat(provider.findByTransactionKey("TEST-TX-1")).isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.UNEXPECTED_RESPONSE));
        pg.replyNext(200, "{}");
        assertThat(provider.findByOrderId("LP-ORD-0000000042")).isEqualTo(PaymentObservation.unknown(PaymentObservation.UnknownCause.UNEXPECTED_RESPONSE));
        assertThat(pg.lookupCalls()).isEqualTo(3);
    }

    @Test
    void invalidInputDoesNotSendAPaymentAndCardNumberIsRedactedFromToString() {
        assertThatThrownBy(() -> new PaymentRequest("LP-ORD-0000000042", "TEST", "", 7000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentRequest("LP-ORD-0000000042", "TEST", "0000-0000-0000-0000", 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(payment().toString()).doesNotContain("0000-0000-0000-0000");
        assertThat(pg.createCalls()).isZero();
    }

    @Test
    void springConfigurationBindsDurationSettingsWithoutStartingADatabaseOrSendingRequests() {
        try (var context = new SpringApplicationBuilder(PaymentProviderConfig.class)
            .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
            .properties("spring.config.location=optional:classpath:/payment-contract-only.yml",
                "commerce.payment.pg.base-url=" + pg.baseUrl(),
                "commerce.payment.pg.callback-url=" + CALLBACK_URL,
                "commerce.payment.pg.connect-timeout=300ms", "commerce.payment.pg.read-timeout=1s",
                "commerce.payment.pg.lookup-read-timeout=500ms", "commerce.payment.pg.lookup-budget=2s",
                "commerce.payment.pg.lookup-retry.max-attempts=3", "commerce.payment.pg.lookup-retry.first-wait=100ms",
                "commerce.payment.pg.lookup-retry.wait-multiplier=2.0",
                "commerce.payment.pg.lookup-circuit.window-size=4", "commerce.payment.pg.lookup-circuit.minimum-calls=4",
                "commerce.payment.pg.lookup-circuit.failure-rate-threshold=50",
                "commerce.payment.pg.lookup-circuit.open-wait=10s", "commerce.payment.pg.lookup-circuit.half-open-calls=2")
            .run()) {
            assertThat(context.getBean(PaymentProvider.class)).isInstanceOf(ResilientPaymentProvider.class);
            assertThat(pg.createCalls()).isZero();
            assertThat(pg.lookupCalls()).isZero();
        }
    }

    private static PaymentRequest payment() {
        return new PaymentRequest("LP-ORD-0000000042", "TEST", "0000-0000-0000-0000", 7000);
    }
}
