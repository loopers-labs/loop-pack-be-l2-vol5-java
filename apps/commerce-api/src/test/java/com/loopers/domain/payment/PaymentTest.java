package com.loopers.domain.payment;

import com.loopers.domain.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final Long ORDER = 42L;
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private static final TransactionKey KEY = new TransactionKey("TEST-TX-0001");
    private static final TransactionKey OTHER_KEY = new TransactionKey("TEST-TX-0002");

    private static Payment requested() {
        return Payment.request(ORDER, PaymentAttemptKey.of("attempt-1"), Money.of(7000), CardType.TEST, NOW);
    }

    @Nested
    @DisplayName("PAY-010, PG-004: 결제 시도는 REQUESTED 로 시작한다")
    class Request {

        @DisplayName("주문 id 로 PG 주문 번호를 만들고, 거래 키 없이 REQUESTED 로 시작한다")
        @Test
        void startsRequested() {
            Payment payment = requested();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
            assertThat(payment.getOrderId()).isEqualTo(ORDER);
            assertThat(payment.getProviderOrderId()).isEqualTo(ProviderOrderId.of(ORDER));
            assertThat(payment.getTransactionKey()).isNull();
            assertThat(payment.getAmount()).isEqualTo(Money.of(7000));
            assertThat(payment.getCardType()).isEqualTo(CardType.TEST);
            assertThat(payment.getRequestedAt()).isEqualTo(NOW);
        }

        @DisplayName("시도 키는 1~64자의 영문, 숫자, - 만 받는다")
        @Test
        void attemptKeyFormat() {
            assertThat(PaymentAttemptKey.of("a".repeat(64)).value()).hasSize(64);
            assertThatThrownBy(() -> PaymentAttemptKey.of("")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PaymentAttemptKey.of("a".repeat(65))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PaymentAttemptKey.of("key with space")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("PAY-002, Q-2: 같은 키의 요청은 주문, 금액, 카드 종류가 같아야 반복이다")
    class SameRequest {

        @DisplayName("주문, 금액, 카드 종류가 모두 같으면 같은 요청이다")
        @Test
        void same() {
            assertThat(requested().isSameRequest(ORDER, Money.of(7000), CardType.TEST)).isTrue();
        }

        @DisplayName("주문이 다르면 같은 요청이 아니다")
        @Test
        void otherOrder() {
            assertThat(requested().isSameRequest(43L, Money.of(7000), CardType.TEST)).isFalse();
        }

        @DisplayName("금액이 다르면 같은 요청이 아니다")
        @Test
        void otherAmount() {
            assertThat(requested().isSameRequest(ORDER, Money.of(7001), CardType.TEST)).isFalse();
        }
    }

    @Nested
    @DisplayName("PG-005, PG-010, PG-011, PG-015: 생성 결과로 REQUESTED 가 바뀐다")
    class CreateResult {

        @DisplayName("Accepted 는 키를 붙이고 PENDING 이 된다. 주문과 재고는 그대로다")
        @Test
        void accepted() {
            Payment payment = requested();

            PaymentOutcome outcome = payment.observe(new CreateObservation.Accepted(KEY));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getTransactionKey()).isEqualTo(KEY);
            assertThat(outcome.effect()).isEqualTo(PaymentOutcome.Effect.NONE);
        }

        @DisplayName("효과 0 건이 확실하면 FAILED 가 되고 주문 실패와 재고 반환을 요구한다. 키는 꾸미지 않는다")
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.loopers.domain.payment.PaymentTest#noEffect")
        void failedWhenNoEffect(CreateObservation observation) {
            Payment payment = requested();

            PaymentOutcome outcome = payment.observe(observation);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getTransactionKey()).isNull();
            assertThat(outcome.effect()).isEqualTo(PaymentOutcome.Effect.FAIL_ORDER);
        }

        @DisplayName("결과 미확인은 UNKNOWN 이다. 주문과 재고는 그대로다")
        @Test
        void unknown() {
            Payment payment = requested();

            PaymentOutcome outcome = payment.observe(new CreateObservation.CreateUnknown());

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(outcome.effect()).isEqualTo(PaymentOutcome.Effect.NONE);
        }
    }

    @Nested
    @DisplayName("SETTLE-006: 키가 붙은 뒤 늦게 온 생성 결과는 아무것도 바꾸지 않는다")
    class LateCreateResult {

        @DisplayName("같은 키의 늦은 Accepted 나 늦은 결과 미확인은 상태와 키를 그대로 둔다")
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.loopers.domain.payment.PaymentTest#lateCreateResults")
        void ignored(CreateObservation late) {
            Payment payment = requested();
            payment.observe(new CreateObservation.Accepted(KEY));

            PaymentOutcome outcome = payment.observe(late);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getTransactionKey()).isEqualTo(KEY);
            assertThat(outcome.effect()).isEqualTo(PaymentOutcome.Effect.NONE);
        }
    }

    @Nested
    @DisplayName("SETTLE-002: 조회한 거래가 우리 기록과 맞으면 그 상태를 반영한다")
    class Verified {

        @DisplayName("PENDING 이면 키가 없을 때 붙이고 PENDING 이 된다")
        @Test
        void pending() {
            Payment payment = requested();
            payment.observe(new CreateObservation.CreateUnknown());

            PaymentOutcome outcome = payment.observe(found(TransactionStatus.PENDING));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getTransactionKey()).isEqualTo(KEY);
            assertThat(outcome.effect()).isEqualTo(PaymentOutcome.Effect.NONE);
        }

        @DisplayName("SUCCESS 면 SUCCESS 가 되고 주문 확정을 요구한다")
        @Test
        void success() {
            Payment payment = accepted();

            PaymentOutcome outcome = payment.observe(found(TransactionStatus.SUCCESS));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(outcome.effect()).isEqualTo(PaymentOutcome.Effect.CONFIRM_ORDER);
        }

        @DisplayName("FAILED 면 FAILED 가 되고 주문 실패와 재고 반환을 요구한다")
        @Test
        void failed() {
            Payment payment = accepted();

            PaymentOutcome outcome = payment.observe(found(TransactionStatus.FAILED));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(outcome.effect()).isEqualTo(PaymentOutcome.Effect.FAIL_ORDER);
        }

        @DisplayName("키가 없는 REQUESTED 에서도 결과가 확인되면 키를 붙이고 반영한다")
        @Test
        void attachesKeyFromRequested() {
            Payment payment = requested();

            PaymentOutcome outcome = payment.observe(found(TransactionStatus.SUCCESS));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(payment.getTransactionKey()).isEqualTo(KEY);
            assertThat(outcome.effect()).isEqualTo(PaymentOutcome.Effect.CONFIRM_ORDER);
        }
    }

    @Nested
    @DisplayName("PAY-017: 조회한 거래가 우리 기록과 어긋나면 CONFLICT 가 되고 불일치를 남긴다")
    class Mismatch {

        @DisplayName("금액이 다르면 AMOUNT_MISMATCH 이고 PG 가 말한 금액을 남긴다. 주문과 재고는 건드리지 않는다")
        @Test
        void amountMismatch() {
            Payment payment = accepted();

            PaymentOutcome outcome = payment.observe(found(transaction(KEY, ORDER, 9000, TransactionStatus.SUCCESS)));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFLICT);
            assertThat(outcome.effect()).isEqualTo(PaymentOutcome.Effect.NONE);
            assertThat(outcome.discrepancy()).isEqualTo(new PaymentDiscrepancy(
                PaymentDiscrepancy.Reason.AMOUNT_MISMATCH, KEY, TransactionStatus.SUCCESS, Money.of(9000)));
        }

        @DisplayName("주문 번호가 다르면 ORDER_MISMATCH")
        @Test
        void orderMismatch() {
            Payment payment = accepted();

            PaymentOutcome outcome = payment.observe(found(transaction(KEY, 99L, 7000, TransactionStatus.SUCCESS)));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFLICT);
            assertThat(outcome.discrepancy().reason()).isEqualTo(PaymentDiscrepancy.Reason.ORDER_MISMATCH);
        }

        @DisplayName("주문 조회에 우리 거래가 2 건이면 MULTIPLE_TRANSACTIONS. 어느 키도 붙이지 않는다")
        @Test
        void twoTransactions() {
            Payment payment = requested();

            PaymentOutcome outcome = payment.observe(found(
                transaction(KEY, ORDER, 7000, TransactionStatus.SUCCESS),
                transaction(OTHER_KEY, ORDER, 7000, TransactionStatus.SUCCESS)
            ));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFLICT);
            assertThat(payment.getTransactionKey()).isNull();
            assertThat(outcome.effect()).isEqualTo(PaymentOutcome.Effect.NONE);
            assertThat(outcome.discrepancy().reason()).isEqualTo(PaymentDiscrepancy.Reason.MULTIPLE_TRANSACTIONS);
        }

        @DisplayName("붙은 키와 다른 키의 맞는 거래가 보이면 MULTIPLE_TRANSACTIONS. 두 번째 거래의 결과는 반영하지 않는다")
        @Test
        void otherKeyFromLookup() {
            Payment payment = accepted();

            PaymentOutcome outcome = payment.observe(found(transaction(OTHER_KEY, ORDER, 7000, TransactionStatus.SUCCESS)));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFLICT);
            assertThat(payment.getTransactionKey()).isEqualTo(KEY);
            assertThat(outcome.effect()).isEqualTo(PaymentOutcome.Effect.NONE);
            assertThat(outcome.discrepancy()).isEqualTo(new PaymentDiscrepancy(
                PaymentDiscrepancy.Reason.MULTIPLE_TRANSACTIONS, OTHER_KEY, TransactionStatus.SUCCESS, Money.of(7000)));
        }

        @DisplayName("붙은 키와 다른 키의 늦은 202 도 MULTIPLE_TRANSACTIONS 다. 202 에는 금액이 없으므로 관찰 금액은 비운다")
        @Test
        void otherKeyFromLateCreate() {
            Payment payment = accepted();

            PaymentOutcome outcome = payment.observe(new CreateObservation.Accepted(OTHER_KEY));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFLICT);
            assertThat(payment.getTransactionKey()).isEqualTo(KEY);
            assertThat(outcome.discrepancy()).isEqualTo(new PaymentDiscrepancy(
                PaymentDiscrepancy.Reason.MULTIPLE_TRANSACTIONS, OTHER_KEY, TransactionStatus.PENDING, null));
        }

        @DisplayName("맞는 거래면 불일치가 없다")
        @Test
        void noDiscrepancyWhenVerified() {
            Payment payment = accepted();

            PaymentOutcome outcome = payment.observe(found(TransactionStatus.SUCCESS));

            assertThat(outcome.discrepancy()).isNull();
        }
    }

    @Nested
    @DisplayName("PAY-007: 종결은 바뀌지 않는다. 같은 결과는 효과가 없고, 다른 결과는 덮어쓰지 않고 불일치로 남긴다")
    class Settled {

        @DisplayName("SUCCESS 뒤 같은 SUCCESS 는 효과가 없다. 중복 callback, 늦은 조회")
        @Test
        void sameResultHasNoEffect() {
            Payment payment = settled(TransactionStatus.SUCCESS);

            PaymentOutcome outcome = payment.observe(found(TransactionStatus.SUCCESS));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(outcome.effect()).isEqualTo(PaymentOutcome.Effect.NONE);
            assertThat(outcome.discrepancy()).isNull();
        }

        @DisplayName("종결 뒤 PG 가 다른 결과를 말하면 상태는 그대로, RESULT_AFTER_SETTLED 를 남긴다")
        @ParameterizedTest(name = "{0} 뒤 {1}")
        @MethodSource("com.loopers.domain.payment.PaymentTest#resultsAfterSettled")
        void differentResultIsRecorded(TransactionStatus settledAs, TransactionStatus observed) {
            Payment payment = settled(settledAs);

            PaymentOutcome outcome = payment.observe(found(observed));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.valueOf(settledAs.name()));
            assertThat(outcome.effect()).isEqualTo(PaymentOutcome.Effect.NONE);
            assertThat(outcome.discrepancy()).isEqualTo(new PaymentDiscrepancy(
                PaymentDiscrepancy.Reason.RESULT_AFTER_SETTLED, KEY, observed, Money.of(7000)));
        }

        @DisplayName("종결 뒤 다른 키의 맞는 거래가 보이면 상태는 그대로, MULTIPLE_TRANSACTIONS 를 남긴다")
        @Test
        void otherKeyAfterSettled() {
            Payment payment = settled(TransactionStatus.SUCCESS);

            PaymentOutcome outcome = payment.observe(found(transaction(OTHER_KEY, ORDER, 7000, TransactionStatus.SUCCESS)));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(outcome.effect()).isEqualTo(PaymentOutcome.Effect.NONE);
            assertThat(outcome.discrepancy().reason()).isEqualTo(PaymentDiscrepancy.Reason.MULTIPLE_TRANSACTIONS);
        }

        @DisplayName("종결 뒤 다른 키의 늦은 202 도 상태를 바꾸지 않는다")
        @Test
        void otherKeyFromLateCreateAfterSettled() {
            Payment payment = settled(TransactionStatus.FAILED);

            PaymentOutcome outcome = payment.observe(new CreateObservation.Accepted(OTHER_KEY));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(outcome.effect()).isEqualTo(PaymentOutcome.Effect.NONE);
            assertThat(outcome.discrepancy().reason()).isEqualTo(PaymentDiscrepancy.Reason.MULTIPLE_TRANSACTIONS);
        }

        @DisplayName("CONFLICT 는 맞는 거래가 보여도 그대로다. 해결은 사람의 판단이다")
        @Test
        void conflictStays() {
            Payment payment = accepted();
            payment.observe(found(transaction(KEY, ORDER, 9000, TransactionStatus.PENDING)));

            PaymentOutcome outcome = payment.observe(found(TransactionStatus.SUCCESS));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFLICT);
            assertThat(outcome.effect()).isEqualTo(PaymentOutcome.Effect.NONE);
        }

        @DisplayName("CONFLICT 에 새 모순이 오면 상태는 그대로, 불일치만 남긴다")
        @Test
        void conflictRecordsNewContradiction() {
            Payment payment = accepted();
            payment.observe(found(transaction(KEY, ORDER, 9000, TransactionStatus.PENDING)));

            PaymentOutcome outcome = payment.observe(found(transaction(KEY, 99L, 7000, TransactionStatus.SUCCESS)));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFLICT);
            assertThat(outcome.discrepancy().reason()).isEqualTo(PaymentDiscrepancy.Reason.ORDER_MISMATCH);
        }
    }

    @Nested
    @DisplayName("PAY-008: 조회 0 건은 없음의 증거가 아니고, 조회 실패는 관찰이 아니다")
    class NotObserved {

        @DisplayName("NotFound 는 상태, 키, 주문, 재고를 그대로 둔다")
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.loopers.domain.payment.PaymentTest#unresolved")
        void notFoundChangesNothing(String scene, Payment payment) {
            PaymentStatus before = payment.getStatus();
            TransactionKey keyBefore = payment.getTransactionKey();

            PaymentOutcome outcome = payment.observe(new LookupResult.NotFound());

            assertThat(payment.getStatus()).isEqualTo(before);
            assertThat(payment.getTransactionKey()).isEqualTo(keyBefore);
            assertThat(outcome.effect()).isEqualTo(PaymentOutcome.Effect.NONE);
            assertThat(outcome.discrepancy()).isNull();
        }

        @DisplayName("Unavailable 을 받는 observe 는 없다. 조회 실패가 상태를 바꾸는 길이 타입에서 막혀 있다")
        @Test
        void unavailableIsNotAccepted() {
            boolean accepts = Arrays.stream(Payment.class.getMethods())
                .filter(method -> method.getName().equals("observe"))
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .anyMatch(type -> type.isAssignableFrom(LookupResult.Unavailable.class));

            assertThat(accepts).isFalse();
        }
    }

    static Stream<Arguments> unresolved() {
        Payment unknown = requested();
        unknown.observe(new CreateObservation.CreateUnknown());
        return Stream.of(
            Arguments.of("REQUESTED", requested()),
            Arguments.of("UNKNOWN", unknown),
            Arguments.of("PENDING", accepted())
        );
    }

    static Stream<Arguments> resultsAfterSettled() {
        return Stream.of(
            Arguments.of(TransactionStatus.SUCCESS, TransactionStatus.FAILED),
            Arguments.of(TransactionStatus.SUCCESS, TransactionStatus.PENDING),
            Arguments.of(TransactionStatus.FAILED, TransactionStatus.SUCCESS),
            Arguments.of(TransactionStatus.FAILED, TransactionStatus.PENDING)
        );
    }

    private static Payment settled(TransactionStatus result) {
        Payment payment = accepted();
        payment.observe(found(result));
        return payment;
    }

    private static Payment accepted() {
        Payment payment = requested();
        payment.observe(new CreateObservation.Accepted(KEY));
        return payment;
    }

    private static LookupResult.Found found(TransactionStatus status) {
        return found(transaction(KEY, ORDER, 7000, status));
    }

    private static LookupResult.Found found(PgTransaction... transactions) {
        return new LookupResult.Found(List.of(transactions));
    }

    private static PgTransaction transaction(TransactionKey key, Long orderId, long amount, TransactionStatus status) {
        return new PgTransaction(key, ProviderOrderId.of(orderId), Money.of(amount), status);
    }

    static Stream<CreateObservation> lateCreateResults() {
        return Stream.of(new CreateObservation.Accepted(KEY), new CreateObservation.CreateUnknown());
    }

    static Stream<CreateObservation> noEffect() {
        return Stream.of(new CreateObservation.RejectedNoEffect(), new CreateObservation.NotSent());
    }
}
