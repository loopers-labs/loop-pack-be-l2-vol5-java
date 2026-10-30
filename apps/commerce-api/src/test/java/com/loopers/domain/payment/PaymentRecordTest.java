package com.loopers.domain.payment;

import com.loopers.domain.common.Money;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentRecordTest {
    @ParameterizedTest(name = "저장 키={0}, 주문={1}, 금액={2}, 조회 키={3}, 일치={4}")
    @CsvSource(value = {
        "null, LP-ORD-0000000042, 7000, TR-1, true",
        "TR-1, LP-ORD-0000000042, 7000, TR-1, true",
        "TR-1, OTHER, 7000, TR-1, false",
        "TR-1, LP-ORD-0000000042, 6999, TR-1, false",
        "TR-1, LP-ORD-0000000042, 7000, TR-2, false"
    }, nullValues = "null")
    @DisplayName("결제 기록이 조회 거래와 자신의 주문·금액·저장 키를 비교하며 상태는 변경하지 않는다")
    void comparesTransactionWithoutChangingPayment(String storedKey, String orderId, long amount, String key, boolean matches) {
        PaymentRecord payment = PaymentRecord.pgStarted(42, new Money(7000), ATTEMPT_KEY, "test-pg", STARTED_AT);
        payment.applyProviderReport(PaymentRecord.ProviderStatus.PENDING, storedKey);

        PaymentRecord.TransactionMatch match = payment.compareTransaction(orderId, new Money(amount), key);

        assertThat(match.matches()).isEqualTo(matches);
        assertThat(match.orderMatches()).isEqualTo(orderId.equals(payment.getProviderOrderId()));
        assertThat(match.amountMatches()).isEqualTo(amount == 7000);
        assertThat(match.keyMatches()).isEqualTo(storedKey == null || storedKey.equals(key));
        assertThat(payment.getProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.PENDING);
        assertThat(payment.getTransactionKey()).isEqualTo(storedKey);
    }

    @Test
    @DisplayName("주문 7000원을 포인트로 결제하면 수단 POINT·결과 SUCCESS·결제액 7000원인 결제 기록이 된다")
    void recordsPointPayment() {
        PaymentRecord payment = PaymentRecord.point(42, new Money(7000));

        assertThat(payment.getId()).isNull();
        assertThat(payment.getOrderId()).isEqualTo(42);
        assertThat(payment.getMethod()).isEqualTo(PaymentRecord.Method.POINT);
        assertThat(payment.getResult()).isEqualTo(PaymentRecord.Result.SUCCESS);
        assertThat(payment.getAmount()).isEqualTo(new Money(7000));
    }

    @Test
    @DisplayName("결제액이 없거나 주문 ID가 양수가 아니면 결제 기록을 만들지 않는다")
    void rejectsMissingAmountAndInvalidOrderId() {
        assertThatThrownBy(() -> PaymentRecord.point(42, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentRecord.point(0, new Money(7000))).isInstanceOf(IllegalArgumentException.class);
    }

    private static final String ATTEMPT_KEY = "6f1c2a7e-0b9d-4c55-9a51-3f0e8b2d7c11";
    private static final Instant STARTED_AT = Instant.parse("2026-10-21T00:00:00Z");
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    @Test
    @DisplayName("주문 42의 PG 결제를 시작하면 결과 PENDING·PG 상태 발송 시작·providerOrderId LP-ORD-0000000042인 결제 기록이 된다")
    void startsPgPayment() {
        PaymentRecord payment = PaymentRecord.pgStarted(42, new Money(7000), ATTEMPT_KEY, "test-pg", STARTED_AT);

        assertThat(payment.getMethod()).isEqualTo(PaymentRecord.Method.PG);
        assertThat(payment.getResult()).isEqualTo(PaymentRecord.Result.PENDING);
        assertThat(payment.getProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.SEND_STARTED);
        assertThat(payment.getAmount()).isEqualTo(new Money(7000));
        assertThat(payment.getAttemptKey()).isEqualTo(ATTEMPT_KEY);
        assertThat(payment.getProviderId()).isEqualTo("test-pg");
        assertThat(payment.getProviderOrderId()).isEqualTo("LP-ORD-0000000042");
        assertThat(payment.getTransactionKey()).isNull();
        assertThat(payment.getStartedAt()).isEqualTo(STARTED_AT);
    }

    @ParameterizedTest(name = "시작 후 경과={0}초 → 만료 대상={1}")
    @CsvSource({"0, false", "119, false", "120, true", "121, true"})
    @DisplayName("결과 대기 중인 PG 결제는 시작 후 2분이 지난 때부터 만료 대상이다")
    void becomesExpirableAfterTimeout(long elapsedSeconds, boolean expirable) {
        PaymentRecord payment = PaymentRecord.pgStarted(42, new Money(7000), ATTEMPT_KEY, "test-pg", STARTED_AT);

        assertThat(payment.isExpiredAt(STARTED_AT.plusSeconds(elapsedSeconds), TIMEOUT)).isEqualTo(expirable);
    }

    @Test
    @DisplayName("확인 필요 PG 결제도 2분이 지나면 만료 대상이고 결과가 정해진 결제와 포인트 결제는 만료 대상이 아니다")
    void onlyPendingPgPaymentIsExpirable() {
        PaymentRecord review = PaymentRecord.pgStarted(42, new Money(7000), ATTEMPT_KEY, "test-pg", STARTED_AT);
        review.requireReview();
        PaymentRecord succeeded = PaymentRecord.pgStarted(43, new Money(7000), "other", "test-pg", STARTED_AT);
        succeeded.applyProviderReport(PaymentRecord.ProviderStatus.SUCCESS, "TR-1");
        PaymentRecord point = PaymentRecord.point(44, new Money(7000));
        Instant later = STARTED_AT.plus(TIMEOUT);

        assertThat(review.isExpiredAt(later, TIMEOUT)).isTrue();
        assertThat(succeeded.isExpiredAt(later, TIMEOUT)).isFalse();
        assertThat(point.isExpiredAt(later, TIMEOUT)).isFalse();
    }

    @Test
    @DisplayName("결과 대기 중인 PG 결제를 만료하면 결과와 PG 상태가 만료가 되고 거래 키는 남는다")
    void expiresPendingPayment() {
        PaymentRecord payment = PaymentRecord.pgStarted(42, new Money(7000), ATTEMPT_KEY, "test-pg", STARTED_AT);
        payment.applyProviderReport(PaymentRecord.ProviderStatus.PENDING, "TR-1");

        payment.expire();

        assertThat(payment.getResult()).isEqualTo(PaymentRecord.Result.EXPIRED);
        assertThat(payment.getProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.EXPIRED);
        assertThat(payment.getTransactionKey()).isEqualTo("TR-1");
    }

    @Test
    @DisplayName("결과가 정해진 결제와 포인트 결제는 만료하지 않고 기존 결과를 유지한다")
    void rejectsExpiringClosedPayment() {
        PaymentRecord succeeded = PaymentRecord.pgStarted(42, new Money(7000), ATTEMPT_KEY, "test-pg", STARTED_AT);
        succeeded.applyProviderReport(PaymentRecord.ProviderStatus.SUCCESS, "TR-1");
        PaymentRecord point = PaymentRecord.point(43, new Money(7000));

        assertThatThrownBy(succeeded::expire).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(point::expire).isInstanceOf(IllegalStateException.class);
        assertThat(succeeded.getResult()).isEqualTo(PaymentRecord.Result.SUCCESS);
        assertThat(succeeded.getProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.SUCCESS);
        assertThat(point.getResult()).isEqualTo(PaymentRecord.Result.SUCCESS);
    }

    @Test
    @DisplayName("시도 키·providerId·시작 시각이 비어 있으면 PG 결제 기록을 만들지 않는다")
    void rejectsPgPaymentWithoutKeyOrProvider() {
        assertThatThrownBy(() -> PaymentRecord.pgStarted(42, new Money(7000), " ", "test-pg", STARTED_AT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentRecord.pgStarted(42, new Money(7000), ATTEMPT_KEY, null, STARTED_AT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentRecord.pgStarted(42, new Money(7000), ATTEMPT_KEY, "test-pg", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "PG 보고 {0}·거래 키 {1} → 결과 {2}")
    @CsvSource(value = {
        "PENDING, TR-1, PENDING",
        "SUCCESS, TR-1, SUCCESS",
        "FAILED, TR-1, FAILED",
        "REJECTED_NO_EFFECT, null, FAILED",
        "UNKNOWN, null, PENDING",
        "NOT_SENT, null, PENDING"
    }, nullValues = "null")
    @DisplayName("결과 대기 중인 PG 결제 기록에 PG 보고를 반영하면 PG 상태·거래 키·결과가 보고대로 바뀐다")
    void appliesProviderReport(PaymentRecord.ProviderStatus status, String transactionKey, PaymentRecord.Result expected) {
        PaymentRecord payment = PaymentRecord.pgStarted(42, new Money(7000), ATTEMPT_KEY, "test-pg", STARTED_AT);

        payment.applyProviderReport(status, transactionKey);

        assertThat(payment.getProviderStatus()).isEqualTo(status);
        assertThat(payment.getTransactionKey()).isEqualTo(transactionKey);
        assertThat(payment.getResult()).isEqualTo(expected);
    }

    @Test
    @DisplayName("거래 키를 받은 뒤 결과 모름 보고가 와도 거래 키는 남는다")
    void keepsTransactionKeyOnLaterUnknown() {
        PaymentRecord payment = PaymentRecord.pgStarted(42, new Money(7000), ATTEMPT_KEY, "test-pg", STARTED_AT);
        payment.applyProviderReport(PaymentRecord.ProviderStatus.PENDING, "TR-1");

        payment.applyProviderReport(PaymentRecord.ProviderStatus.UNKNOWN, null);

        assertThat(payment.getTransactionKey()).isEqualTo("TR-1");
        assertThat(payment.getProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.UNKNOWN);
    }

    @Test
    @DisplayName("이미 성공한 결제 기록과 포인트 결제 기록에는 PG 보고를 덮어쓰지 않는다")
    void rejectsReportAfterClosed() {
        PaymentRecord pg = PaymentRecord.pgStarted(42, new Money(7000), ATTEMPT_KEY, "test-pg", STARTED_AT);
        pg.applyProviderReport(PaymentRecord.ProviderStatus.SUCCESS, "TR-1");
        PaymentRecord point = PaymentRecord.point(43, new Money(7000));

        assertThatThrownBy(() -> pg.applyProviderReport(PaymentRecord.ProviderStatus.FAILED, "TR-1"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> point.applyProviderReport(PaymentRecord.ProviderStatus.FAILED, null))
            .isInstanceOf(IllegalStateException.class);
        assertThat(pg.getResult()).isEqualTo(PaymentRecord.Result.SUCCESS);
        assertThat(point.getResult()).isEqualTo(PaymentRecord.Result.SUCCESS);
    }
}
