package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentObservation.UnknownCause;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.support.payment.LookupSettingsFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 조회 GET만 최대 3회(최초 포함) 다시 묻고, 대답을 들은 결과와 생성 POST는 반복하지 않는지 실제 HTTP 횟수로 확인한다. */
class PaymentLookupRetryTest {

    private static final String ORDER = "LP-ORD-0000000042";
    private FakePaymentServer pg;
    private TestPgPaymentAdapter adapter;
    private ResilientPaymentProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        pg = new FakePaymentServer();
        adapter = new PaymentProviderConfig().paymentProvider(pg.baseUrl(), "http://127.0.0.1:8080/api/v1/payments/callback",
            Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(500));
        provider = new ResilientPaymentProvider(adapter, LookupSettingsFixture.LEARNING, Clock.systemUTC());
        provider.create(new PaymentRequest(ORDER, "TEST", "0000-0000-0000-0000", 7000));
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            adapter.close();
        } finally {
            pg.close();
        }
    }

    @Test
    void normalAnswerIsReturnedWithoutRetry() {
        PaymentObservation result = provider.findByTransactionKey("TEST-TX-1");

        assertThat(result.transactions()).extracting(PaymentTransaction::status)
            .containsExactly(PaymentTransaction.Status.PENDING);
        assertThat(pg.lookupCalls()).isEqualTo(1);
    }

    @Test
    void oneServerErrorIsRecoveredByTheSecondLookup() {
        pg.changeStatus("TEST-TX-1", "SUCCESS");
        pg.replyNext(503, "");

        PaymentObservation result = provider.findByTransactionKey("TEST-TX-1");

        assertThat(result.transactions()).extracting(PaymentTransaction::status)
            .containsExactly(PaymentTransaction.Status.SUCCESS);
        assertThat(pg.lookupCalls()).isEqualTo(2);
    }

    @Test
    void twoServerErrorsAreRecoveredByTheThirdLookup() {
        pg.changeStatus("TEST-TX-1", "SUCCESS");
        pg.replyNext(503, "");
        pg.replyNext(503, "");

        PaymentObservation result = provider.findByOrderId(ORDER);

        assertThat(result.transactions()).extracting(PaymentTransaction::status)
            .containsExactly(PaymentTransaction.Status.SUCCESS);
        assertThat(pg.lookupCalls()).isEqualTo(3);
    }

    @Test
    void lookupTimeoutIsRetriedLikeServerError() {
        pg.delayNextResponse(Duration.ofSeconds(1));

        PaymentObservation result = provider.findByTransactionKey("TEST-TX-1");

        assertThat(result.kind()).isEqualTo(PaymentObservation.Kind.OBSERVED);
        assertThat(pg.lookupCalls()).isEqualTo(2);
    }

    @Test
    void continuousServerErrorStopsAfterThreeLookupsAndStaysUnknown() {
        for (int i = 0; i < 4; i++) {
            pg.replyNext(503, "");
        }

        PaymentObservation result = provider.findByTransactionKey("TEST-TX-1");

        assertThat(result).isEqualTo(PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE));
        assertThat(pg.lookupCalls()).isEqualTo(3);
    }

    @Test
    void slowLookupsStopWhenTheRemainingBudgetCannotFitAnotherAttempt() {
        for (int i = 0; i < 3; i++) {
            pg.delayNextResponse(Duration.ofSeconds(1));
        }

        long started = System.nanoTime();
        PaymentObservation result = provider.findByTransactionKey("TEST-TX-1");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result).isEqualTo(PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE));
        assertThat(pg.lookupCalls()).isEqualTo(2);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void answeredResultsAreNotRetried() {
        pg.changeStatus("TEST-TX-1", "FAILED");
        assertThat(provider.findByTransactionKey("TEST-TX-1").transactions()).hasSize(1);
        assertThat(provider.findByTransactionKey("TEST-TX-404").transactions()).isEmpty();
        assertThat(provider.findByOrderId("LP-ORD-0000000099").transactions()).isEmpty();

        assertThat(pg.lookupCalls()).isEqualTo(3);
    }

    @Test
    void unexpectedResponsesAreNotRetried() {
        pg.replyNext(200, "not-json");
        assertThat(provider.findByTransactionKey("TEST-TX-1"))
            .isEqualTo(PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE));
        pg.replyNext(401, "");
        assertThat(provider.findByOrderId(ORDER))
            .isEqualTo(PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE));

        assertThat(pg.lookupCalls()).isEqualTo(2);
    }

    @Test
    void createIsNeverRetriedEvenOnTemporaryFailure() {
        pg.replyNext(503, "");

        PaymentObservation result = provider.create(new PaymentRequest(ORDER, "TEST", "0000-0000-0000-0000", 7000));

        assertThat(result).isEqualTo(PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE));
        assertThat(pg.createCalls()).isEqualTo(2);
    }
}
