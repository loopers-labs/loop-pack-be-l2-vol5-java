package com.loopers.testpg.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PaymentTest {
    private static final URI CALLBACK = URI.create("http://commerce-api:8080/api/v1/payments/callback");

    @DisplayName("거래는 접수 당시의 최종 결과와 콜백 횟수를 보관하고 종결 후에도 유지한다.")
    @Test
    void preservesAcceptedCompletionSettings() {
        Payment failed = Payment.create("LP-ORD-0000000042", 7_000L, CALLBACK, ScenarioSetting.of(PaymentScenario.FAIL));
        Payment skipped = Payment.create("LP-ORD-0000000043", 7_000L, CALLBACK, ScenarioSetting.of(PaymentScenario.SKIP_CALLBACK));

        assertThat(failed.getCompletionResult()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.fail().getCompletionResult()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.fail().getCallbackCount()).isEqualTo(1);
        assertThat(skipped.succeed().getCallbackCount()).isZero();
    }

    @DisplayName("거래는 PENDING으로 접수되고 같은 주문으로 다시 생성해도 새 거래 키를 갖는다.")
    @Test
    void createsIndependentPendingPayments() {
        Payment first = Payment.create("LP-ORD-0000000042", 7_000L, CALLBACK);
        Payment second = Payment.create("LP-ORD-0000000042", 7_000L, CALLBACK);

        assertThat(first.getTransactionKey()).isNotBlank().isNotEqualTo(second.getTransactionKey());
        assertThat(first.getOrderId()).isEqualTo("LP-ORD-0000000042");
        assertThat(first.getAmount()).isEqualTo(7_000L);
        assertThat(first.getCallbackUrl()).isEqualTo(CALLBACK);
        assertThat(first.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @DisplayName("성공 처리 후에도 거래 키·주문·금액은 유지되고 접수 시점의 값은 바뀌지 않는다.")
    @Test
    void completesWithoutChangingAcceptedSnapshot() {
        Payment accepted = Payment.create("LP-ORD-0000000042", 7_000L, CALLBACK);

        Payment completed = accepted.succeed();

        assertThat(accepted.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(completed.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(completed.getTransactionKey()).isEqualTo(accepted.getTransactionKey());
        assertThat(completed.getOrderId()).isEqualTo(accepted.getOrderId());
        assertThat(completed.getAmount()).isEqualTo(accepted.getAmount());
        assertThat(completed.succeed()).isSameAs(completed);
    }

    @DisplayName("확정 실패 처리는 PENDING 거래만 FAILED로 바꾸고 거래 키·주문·금액은 유지한다.")
    @Test
    void failsOnlyPendingPayment() {
        Payment accepted = Payment.create("LP-ORD-0000000042", 7_000L, CALLBACK);

        Payment failed = accepted.fail();

        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.getTransactionKey()).isEqualTo(accepted.getTransactionKey());
        assertThat(failed.getAmount()).isEqualTo(accepted.getAmount());
        assertThat(failed.fail()).isSameAs(failed);
        assertThat(failed.succeed()).isSameAs(failed);
        assertThat(accepted.succeed().fail().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @DisplayName("주문 ID는 영문 대소문자·숫자·-·_로 된 6자 이상 64자 이하 문자열만 받는다.")
    @ParameterizedTest
    @ValueSource(strings = {"abcdef", "A_b-12", "LP-ORD-0000000042-7f3a9c1e"})
    void acceptsOrderIdWithinRule(String orderId) {
        assertThat(Payment.create(orderId, 7_000L, CALLBACK).getOrderId()).isEqualTo(orderId);
        assertThat(Payment.create("a".repeat(64), 7_000L, CALLBACK).getOrderId()).hasSize(64);
    }

    @DisplayName("주문 ID가 없거나 6자 미만·64자 초과이거나 허용하지 않는 문자를 포함하면 거래를 만들지 않는다.")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "12345", "LP ORD 0001", "LP-ORD/0001", "LP-ORD=0001", "주문번호000001", " abcdef"})
    void rejectsInvalidOrderId(String orderId) {
        assertThatIllegalArgumentException().isThrownBy(() -> Payment.create(orderId, 7_000L, CALLBACK));
    }

    @DisplayName("64자를 넘는 주문 ID는 거래를 만들지 않는다.")
    @Test
    void rejectsTooLongOrderId() {
        assertThatIllegalArgumentException().isThrownBy(() -> Payment.create("a".repeat(65), 7_000L, CALLBACK));
    }

    @DisplayName("양수가 아닌 금액은 거래를 만들지 않는다.")
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void rejectsNonPositiveAmount(long amount) {
        assertThatIllegalArgumentException().isThrownBy(() -> Payment.create("LP-ORD-0000000042", amount, CALLBACK));
    }

    @DisplayName("callback 주소는 호스트가 있는 HTTP 또는 HTTPS 주소여야 한다.")
    @ParameterizedTest
    @ValueSource(strings = {"/callback", "file:///tmp/callback", "http:/callback", "http://user:pass@localhost/callback"})
    void rejectsInvalidCallbackUrl(String callbackUrl) {
        assertThatIllegalArgumentException().isThrownBy(
            () -> Payment.create("LP-ORD-0000000042", 7_000L, URI.create(callbackUrl))
        );
    }
}
