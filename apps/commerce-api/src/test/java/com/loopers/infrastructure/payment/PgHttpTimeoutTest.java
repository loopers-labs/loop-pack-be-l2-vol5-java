package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PaymentCreateRequest;
import com.loopers.domain.payment.PaymentGatewayResult;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class PgHttpTimeoutTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ORDER_ID = "LP-ORD-0000000042";
    private static final PaymentCreateRequest REQUEST =
        new PaymentCreateRequest(ORDER_ID, 7000, "TEST", "0000-0000-0000-0000");

    @Test
    void defaultsMatchTheSelectedIndividualHttpBudgets() {
        var timeouts = PgHttpTimeouts.defaults();

        assertThat(timeouts.connect()).isEqualTo(Duration.ofSeconds(3));
        assertThat(timeouts.pool()).isEqualTo(Duration.ofSeconds(1));
        assertThat(timeouts.createResponse()).isEqualTo(Duration.ofSeconds(60));
        assertThat(timeouts.lookupResponse()).isEqualTo(Duration.ofSeconds(5));
        assertThat(timeouts.createTotal()).isEqualTo(Duration.ofSeconds(65));
        assertThat(timeouts.lookupTotal()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void lookupResponseWaitIsActuallyBoundedAndDoesNotCreateAPayment() throws Exception {
        try (var pg = new LocalTestPg();
             var client = new HttpTestPgClient(pg.baseUri(), pg.callbackUri(), MAPPER,
                 new PgHttpTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(3),
                     Duration.ofMillis(150), Duration.ofSeconds(4), Duration.ofSeconds(3)))) {
            pg.replyNextHeld(200, "[]");
            long started = System.nanoTime();

            var result = client.findByProviderOrderId(ORDER_ID);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertTimeout(result);
            assertThat(pg.awaitRequest(Duration.ofSeconds(1))).isTrue();
            assertThat(pg.lookupCalls()).isEqualTo(1);
            assertThat(pg.createCalls()).isZero();
            pg.releaseResponses();
            assertThat(client.findByProviderOrderId(ORDER_ID).kind()).isEqualTo(PaymentGatewayResult.Kind.NOT_FOUND);
        }
    }

    @Test
    void createTotalDeadlineCancelsBeforeTheLongerResponseTimeoutAndReleasesItsOnlyConnection() throws Exception {
        try (var pg = new LocalTestPg();
             var client = new HttpTestPgClient(pg.baseUri(), pg.callbackUri(), MAPPER,
                 new PgHttpTimeouts(Duration.ofSeconds(1), Duration.ofMillis(200), Duration.ofSeconds(4),
                     Duration.ofSeconds(1), Duration.ofMillis(150), Duration.ofSeconds(2)), 1)) {
            pg.nextCreateMode(LocalTestPg.CreateMode.HOLD_RESPONSE_AFTER_CREATE);
            long started = System.nanoTime();

            var result = client.create(REQUEST);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertTimeout(result);
            assertThat(pg.awaitCreate(Duration.ofSeconds(1))).isTrue();
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(pg.transactionCount()).isEqualTo(1);
            // The fixture still holds the POST response; cancellation must free the only client connection.
            var recovered = client.findByProviderOrderId(ORDER_ID);
            assertThat(recovered.kind()).isEqualTo(PaymentGatewayResult.Kind.OBSERVED);
            assertThat(recovered.transactions()).hasSize(1);
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(pg.lookupCalls()).isEqualTo(1);
        }
    }

    @Test
    void lookupTotalDeadlineAlsoCancelsBeforeItsLongerResponseTimeout() throws Exception {
        try (var pg = new LocalTestPg();
             var client = new HttpTestPgClient(pg.baseUri(), pg.callbackUri(), MAPPER,
                 new PgHttpTimeouts(Duration.ofSeconds(1), Duration.ofMillis(200), Duration.ofSeconds(4),
                     Duration.ofSeconds(4), Duration.ofSeconds(5), Duration.ofMillis(150)), 1)) {
            pg.replyNextHeld(200, "[]");
            long started = System.nanoTime();

            var result = client.findByProviderOrderId(ORDER_ID);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertTimeout(result);
            assertThat(pg.awaitRequest(Duration.ofSeconds(1))).isTrue();
            assertThat(client.findByProviderOrderId(ORDER_ID).kind()).isEqualTo(PaymentGatewayResult.Kind.NOT_FOUND);
            assertThat(pg.createCalls()).isZero();
            assertThat(pg.lookupCalls()).isEqualTo(2);
        }
    }

    @Test
    void totalDeadlineCancelsWhileReadingTheBodyAndReturnsTheOnlyConnection() throws Exception {
        try (var pg = new LocalTestPg();
             var client = new HttpTestPgClient(pg.baseUri(), pg.callbackUri(), MAPPER,
                 new PgHttpTimeouts(Duration.ofSeconds(1), Duration.ofMillis(200), Duration.ofSeconds(4),
                     Duration.ofSeconds(4), Duration.ofSeconds(5), Duration.ofMillis(150)), 1)) {
            pg.replyNextHeldBody(200, "[]");
            long started = System.nanoTime();

            var result = client.findByProviderOrderId(ORDER_ID);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertTimeout(result);
            assertThat(pg.awaitBody(Duration.ofSeconds(1))).isTrue();
            assertThat(pg.lookupCalls()).isEqualTo(1);
            assertThat(pg.createCalls()).isZero();
            // Do not release the held body: request cancellation itself must return the sole connection.
            assertThat(client.findByProviderOrderId(ORDER_ID).kind()).isEqualTo(PaymentGatewayResult.Kind.NOT_FOUND);
            assertThat(pg.lookupCalls()).isEqualTo(2);
            assertThat(pg.createCalls()).isZero();
        }
    }

    @Test
    void poolWaitTimesOutWithoutCallingPgAndTheFirstCallStillCompletes() throws Exception {
        try (var pg = new LocalTestPg();
             var client = new HttpTestPgClient(pg.baseUri(), pg.callbackUri(), MAPPER,
                 new PgHttpTimeouts(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(4),
                     Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(3)), 1)) {
            var executor = Executors.newSingleThreadExecutor();
            pg.nextCreateMode(LocalTestPg.CreateMode.HOLD_RESPONSE_AFTER_CREATE);
            var first = executor.submit(() -> client.create(REQUEST));
            try {
                assertThat(pg.awaitCreate(Duration.ofSeconds(2))).isTrue();
                long started = System.nanoTime();

                var blockedLookup = client.findByProviderOrderId(ORDER_ID);

                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
                assertTimeout(blockedLookup);
                assertThat(pg.lookupCalls()).isZero();
                assertThat(pg.createCalls()).isEqualTo(1);
                assertThat(pg.transactionCount()).isEqualTo(1);
                pg.releaseResponses();
                assertThat(first.get(2, TimeUnit.SECONDS).kind()).isEqualTo(PaymentGatewayResult.Kind.OBSERVED);
                assertThat(client.findByProviderOrderId(ORDER_ID).kind()).isEqualTo(PaymentGatewayResult.Kind.OBSERVED);
            } finally {
                pg.releaseResponses();
                first.cancel(true);
                executor.shutdownNow();
                assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com:9000", "http://127.0.0.1:9000@other.example",
        "http://user:password@127.0.0.1:9000", "http://127.0.0.1:9000?override=true",
        "http://127.0.0.1:9000#fragment"})
    void refusesUnsafeBaseUrlsBeforeAnyNetworkCall(String base) {
        assertThatThrownBy(() -> new HttpTestPgClient(URI.create(base),
            URI.create("http://127.0.0.1:8080/api/v1/payments/callback"), MAPPER, PgHttpTimeouts.defaults()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/callback", "http://user:password@127.0.0.1:8080/callback",
        "http://127.0.0.1:8080/callback#fragment"})
    void refusesUnsafeCallbackUrlsBeforeAnyNetworkCall(String callback) {
        assertThatThrownBy(() -> new HttpTestPgClient(URI.create("http://127.0.0.1:9000"),
            URI.create(callback), MAPPER, PgHttpTimeouts.defaults())).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertTimeout(PaymentGatewayResult result) {
        assertThat(result.kind()).isEqualTo(PaymentGatewayResult.Kind.UNKNOWN);
        assertThat(result.failure()).isEqualTo(PaymentGatewayResult.Failure.TIMEOUT);
        assertThat(result.transactions()).isEmpty();
    }
}
