package com.loopers.application.order;

import com.loopers.application.payment.port.PaymentAnomalyRepository.Kind;
import com.loopers.application.payment.port.PaymentTransaction;
import com.loopers.application.payment.port.PaymentTransactionStatus;
import com.loopers.application.payment.port.PaymentTransactionsLookup;
import com.loopers.domain.common.Money;
import com.loopers.domain.payment.PaymentRecord;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentLookupAssessmentTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-21T00:00:00Z");

    @ParameterizedTest(name = "일치 단일 거래 상태={0}")
    @EnumSource(PaymentTransactionStatus.class)
    @DisplayName("일치하는 단일 거래는 보고와 callback 비교 키를 제공하고 결제 기록은 변경하지 않는다")
    void verifiesSingleTransaction(PaymentTransactionStatus status) {
        PaymentRecord payment = payment(null);
        PaymentLookupAssessment assessment = PaymentLookupAssessment.assess(payment, found(status));

        assertThat(assessment).isEqualTo(new PaymentLookupAssessment.Verified(
            new PaymentReport(PaymentRecord.ProviderStatus.valueOf(status.name()), "TR-1"), "TR-1"));
        assertThat(payment.getTransactionKey()).isNull();
        assertThat(payment.getProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.PENDING);
    }

    @ParameterizedTest(name = "미확정 조회에서 저장 키={0}")
    @NullSource
    @ValueSource(strings = "TR-1")
    @DisplayName("실패와 0건은 미확정으로 해석하고 저장 키가 있을 때만 callback 비교 근거로 제공한다")
    void preservesStoredKeyForUncertainLookup(String storedKey) {
        PaymentRecord payment = payment(storedKey);
        assertThat(PaymentLookupAssessment.assess(payment, new PaymentTransactionsLookup.Failed("failed")))
            .isEqualTo(new PaymentLookupAssessment.Uncertain(Kind.LOOKUP_FAILED, "lookupFailed=true", storedKey));
        assertThat(PaymentLookupAssessment.assess(payment,
            new PaymentTransactionsLookup.Found(List.of(), PaymentTransactionsLookup.Coverage.ALL)))
            .isEqualTo(new PaymentLookupAssessment.Uncertain(Kind.NO_TRANSACTION,
                "count=0, storedKeyPresent=" + (storedKey != null), storedKey));
    }

    @ParameterizedTest(name = "보증={0}, 이상={1}")
    @CsvSource({"ALL, DUPLICATE_TRANSACTIONS", "SINGLE, COVERAGE_CONTRACT_VIOLATION"})
    @DisplayName("복수 거래는 보증별 확인 필요로 해석하며 저장 키 없는 callback에는 비교 근거를 만들지 않는다")
    void distinguishesMultipleTransactionCoverage(PaymentTransactionsLookup.Coverage coverage, Kind kind) {
        PaymentTransactionsLookup lookup = new PaymentTransactionsLookup.Found(List.of(
            transaction("LP-ORD-0000000042", 7000, "TR-1"), transaction("LP-ORD-0000000042", 7000, "TR-2")), coverage);
        assertThat(PaymentLookupAssessment.assess(payment(null), lookup))
            .isEqualTo(new PaymentLookupAssessment.Review(kind, "count=2", null));
        assertThat(PaymentLookupAssessment.assess(payment("TR-1"), lookup).callbackComparisonKey()).isEqualTo("TR-1");
    }

    @ParameterizedTest(name = "불일치 주문={0}, 금액={1}, 조회 키={2}, 요약={3}")
    @CsvSource(delimiter = '|', value = {
        "OTHER|7000|TR-1|orderMatches=false, amountMatches=true, keyMatches=true",
        "LP-ORD-0000000042|6999|TR-1|orderMatches=true, amountMatches=false, keyMatches=true",
        "LP-ORD-0000000042|7000|TR-2|orderMatches=true, amountMatches=true, keyMatches=false"
    })
    @DisplayName("단일 거래 불일치는 저장 키를 비교 근거로 유지하고 일치 여부만 이상 요약에 담는다")
    void reviewsMismatchedTransaction(String orderId, long amount, String key, String summary) {
        PaymentTransactionsLookup lookup = new PaymentTransactionsLookup.Found(
            List.of(transaction(orderId, amount, key)), PaymentTransactionsLookup.Coverage.ALL);
        assertThat(PaymentLookupAssessment.assess(payment("TR-1"), lookup))
            .isEqualTo(new PaymentLookupAssessment.Review(Kind.TRANSACTION_MISMATCH, summary, "TR-1"));
        assertThat(PaymentLookupAssessment.assess(payment(null), new PaymentTransactionsLookup.Found(
            List.of(transaction("OTHER", 6999, "TR-2")), PaymentTransactionsLookup.Coverage.ALL)).callbackComparisonKey()).isNull();
    }

    @Test
    @DisplayName("미호출은 저장 키가 있어도 이상이나 callback 키 비교 근거를 만들지 않는다")
    void doesNotInterpretNotCalledAsObservation() {
        PaymentLookupAssessment assessment = PaymentLookupAssessment.assess(payment("TR-1"), new PaymentTransactionsLookup.NotCalled());
        assertThat(assessment).isInstanceOf(PaymentLookupAssessment.NotCalled.class);
        assertThat(assessment.callbackComparisonKey()).isNull();
    }

    private PaymentRecord payment(String key) {
        PaymentRecord payment = PaymentRecord.pgStarted(42, new Money(7000), "attempt", "test-pg", STARTED_AT);
        payment.applyProviderReport(PaymentRecord.ProviderStatus.PENDING, key);
        return payment;
    }

    private PaymentTransactionsLookup found(PaymentTransactionStatus status) {
        return new PaymentTransactionsLookup.Found(List.of(new PaymentTransaction(
            "TR-1", "LP-ORD-0000000042", new Money(7000), status)), PaymentTransactionsLookup.Coverage.ALL);
    }

    private PaymentTransaction transaction(String orderId, long amount, String key) {
        return new PaymentTransaction(key, orderId, new Money(amount), PaymentTransactionStatus.SUCCESS);
    }
}
