package com.loopers.domain.order;

import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZonedDateTime;
import java.util.List;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AG-06 주문. INV-06, INV-07, INV-08, INV-09, INV-12, ST-03 (DRAFT → AWAITING_PAYMENT → CONFIRMED | FAILED). */
class OrderModelTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-09-07T10:00:00.123456789+09:00[Asia/Seoul]");

    private static OrderModel.Line line(long productId, int quantity, long unitPrice) {
        return new OrderModel.Line(productId, quantity, unitPrice);
    }

    private static OrderModel draft() {
        return OrderModel.create(1L, List.of(line(10L, 2, 1_000L)));
    }

    /** 결제 시도 키 "key-1" 로 결제를 시작한 주문. */
    private static OrderModel awaitingPayment() {
        OrderModel order = draft();
        order.startPayment("key-1", "SAMSUNG", "1234-5678-9814-1451");
        return order;
    }

    @DisplayName("[INV-06][INV-12] 생성 직후 DRAFT, 합계 = Σ(단가 × 수량), 결제액·결제 결과 없음.")
    @Test
    void create_computesTotal_andStartsAsDraft() {
        OrderModel order = OrderModel.create(1L, List.of(line(10L, 2, 1_000L), line(20L, 1, 500L)));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.getTotalAmount()).isEqualTo(2_500L);
        assertThat(order.getPaidAmount()).isNull();
        assertThat(order.getConfirmedAt()).isNull();
        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getItems().get(0).lineAmount()).isEqualTo(2_000L);
    }

    @DisplayName("[INV-08][ASM-11] 같은 상품 품목은 수량을 합쳐 한 품목으로 저장한다.")
    @Test
    void create_mergesSameProduct() {
        OrderModel order = OrderModel.create(1L, List.of(line(10L, 2, 1_000L), line(20L, 1, 500L), line(10L, 3, 1_000L)));

        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getItems().get(0).getProductId()).isEqualTo(10L);
        assertThat(order.getItems().get(0).getQuantity()).isEqualTo(5);
        assertThat(order.getTotalAmount()).isEqualTo(5_500L);
    }

    @DisplayName("[INV-12][ER-12 EMPTY_ORDER_ITEMS] 품목이 없으면(null·빈 목록) 생성되지 않는다.")
    @Test
    void create_throwsEmptyOrderItems() {
        assertThrowsErrorType(() -> OrderModel.create(1L, null), ErrorType.EMPTY_ORDER_ITEMS);
        assertThrowsErrorType(() -> OrderModel.create(1L, List.of()), ErrorType.EMPTY_ORDER_ITEMS);
    }

    @DisplayName("[INV-07][ER-13 INVALID_QUANTITY] 어느 품목의 수량이 누락·0 이하면 생성되지 않는다.")
    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void create_throwsInvalidQuantity(Integer quantity) {
        assertThrowsErrorType(
            () -> OrderModel.create(1L, List.of(line(10L, 1, 100L), new OrderModel.Line(20L, quantity, 100L))),
            ErrorType.INVALID_QUANTITY);
    }

    @DisplayName("[INV-06][ER-14 AMOUNT_OUT_OF_RANGE] 항목 금액이 표현 범위를 넘으면 생성되지 않는다 (ASM-05).")
    @Test
    void create_throwsAmountOutOfRange_onLineOverflow() {
        assertThrowsErrorType(() -> OrderModel.create(1L, List.of(line(10L, 2, Long.MAX_VALUE))), ErrorType.AMOUNT_OUT_OF_RANGE);
    }

    @DisplayName("[INV-06][ER-14 AMOUNT_OUT_OF_RANGE] 합계가 표현 범위를 넘으면 생성되지 않는다.")
    @Test
    void create_throwsAmountOutOfRange_onTotalOverflow() {
        assertThrowsErrorType(
            () -> OrderModel.create(1L, List.of(line(10L, 1, Long.MAX_VALUE), line(20L, 1, 1L))),
            ErrorType.AMOUNT_OUT_OF_RANGE);
    }

    @DisplayName("[INV-06] 합계가 정확히 표현 범위 상한이면 허용된다. 0원 상품도 허용 (ASM-04).")
    @Test
    void create_allowsExactMax_andZeroPrice() {
        OrderModel order = OrderModel.create(1L, List.of(line(10L, 1, Long.MAX_VALUE), line(20L, 3, 0L)));

        assertThat(order.getTotalAmount()).isEqualTo(Long.MAX_VALUE);
    }

    @DisplayName("[ST-03] startPayment() 로 DRAFT → AWAITING_PAYMENT. 결제 시도 키·카드 종류를 기록하고 카드번호는 마지막 4자리만 남긴다.")
    @Test
    void startPayment_transitionsToAwaitingPayment() {
        OrderModel order = draft();

        order.startPayment("key-1", "SAMSUNG", "1234-5678-9814-1451");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(order.isSamePaymentAttempt("key-1")).isTrue();
        assertThat(order.isSamePaymentAttempt("key-2")).isFalse();
        assertThat(order).extracting("cardType", "maskedCardNo").containsExactly("SAMSUNG", "****-****-****-1451");
        assertThat(order.getPaidAmount()).isNull();
        assertThat(order.getTransactionKey()).isNull();
    }

    @DisplayName("[ST-03] 카드번호가 없으면 마스킹 번호도 없고, 4자리 미만이면 전부 가린다. 카드 정보는 검증하지 않는다.")
    @ParameterizedTest
    @CsvSource(value = {"NULL, NULL", "'', NULL", "123, ****", "1234, ****-****-****-1234"}, nullValues = "NULL")
    void startPayment_masksCardNo(String cardNo, String expected) {
        OrderModel order = draft();

        order.startPayment("key-1", null, cardNo);

        assertThat(order).extracting("maskedCardNo").isEqualTo(expected);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
    }

    @DisplayName("[ER INVALID_PAYMENT_ATTEMPT_KEY] 결제 시도 키가 없거나 비었거나 64자를 넘으면 거부되고 DRAFT 유지.")
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void startPayment_throwsInvalidPaymentAttemptKey(String key) {
        OrderModel order = draft();

        assertThrowsErrorType(() -> order.startPayment(key, "SAMSUNG", "1234-5678-9814-1451"), ErrorType.INVALID_PAYMENT_ATTEMPT_KEY);
        assertThrowsErrorType(() -> order.startPayment("k".repeat(65), "SAMSUNG", "1234-5678-9814-1451"), ErrorType.INVALID_PAYMENT_ATTEMPT_KEY);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
    }

    @DisplayName("[ST-03] 결제 시도 키는 정확히 64자까지 허용된다 (경계).")
    @Test
    void startPayment_allowsKeyOf64() {
        OrderModel order = draft();

        order.startPayment("k".repeat(64), "SAMSUNG", "1234-5678-9814-1451");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
    }

    @DisplayName("[ST-03][ER-15 ORDER_NOT_DRAFT] DRAFT 가 아니면 결제를 시작할 수 없다. 기존 결제 시도 키 유지.")
    @Test
    void startPayment_throwsOrderNotDraft_whenNotDraft() {
        OrderModel order = awaitingPayment();

        assertThrowsErrorType(() -> order.startPayment("key-2", "SAMSUNG", "1234-5678-9814-1451"), ErrorType.ORDER_NOT_DRAFT);
        assertThrowsErrorType(order::ensureDraft, ErrorType.ORDER_NOT_DRAFT);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(order.isSamePaymentAttempt("key-1")).isTrue();
    }

    @DisplayName("[ST-03][INV-09] confirm() 으로 AWAITING_PAYMENT → CONFIRMED. 결제액 = 합계, 확정 시각은 받은 시각을 마이크로초까지 잘라 기록.")
    @Test
    void confirm_transitionsToConfirmed() {
        OrderModel order = awaitingPayment();

        order.confirm(NOW);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaidAmount()).isEqualTo(order.getTotalAmount());
        assertThat(order.getConfirmedAt()).isEqualTo(ZonedDateTime.parse("2026-09-07T10:00:00.123456+09:00[Asia/Seoul]"));
        assertThat(order.isAwaitingPayment()).isFalse();
    }

    @DisplayName("[ST-03] fail() 로 AWAITING_PAYMENT → FAILED. 결제액·확정 시각은 비어 있다.")
    @Test
    void fail_transitionsToFailed() {
        OrderModel order = awaitingPayment();

        order.fail();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getPaidAmount()).isNull();
        assertThat(order.getConfirmedAt()).isNull();
        assertThat(order.isAwaitingPayment()).isFalse();
    }

    @DisplayName("[ST-03] AWAITING_PAYMENT 가 아니면 confirm()·fail() 은 금지된 전이 (결과 반영이 먼저 거르므로 코드 버그). 상태 유지.")
    @Test
    void confirmAndFail_throwIllegalState_whenNotAwaitingPayment() {
        OrderModel draft = draft();
        OrderModel confirmed = awaitingPayment();
        confirmed.confirm(NOW);
        var firstConfirmedAt = confirmed.getConfirmedAt();
        OrderModel failed = awaitingPayment();
        failed.fail();

        assertThatThrownBy(() -> draft.confirm(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(draft::fail).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> confirmed.confirm(NOW.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(confirmed::fail).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.confirm(NOW)).isInstanceOf(IllegalStateException.class);

        assertThat(draft.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedAt()).isEqualTo(firstConfirmedAt);
        assertThat(failed.getStatus()).isEqualTo(OrderStatus.FAILED);
    }

    @DisplayName("PG 거래 키는 처음 받은 것만 기록한다.")
    @Test
    void recordTransactionKey_keepsFirst() {
        OrderModel order = awaitingPayment();

        order.recordTransactionKey("tx-1");
        order.recordTransactionKey("tx-2");

        assertThat(order.getTransactionKey()).isEqualTo("tx-1");
    }

    @DisplayName("결제 시도 키가 없는 DRAFT 는 어떤 키와도 같은 시도가 아니다.")
    @Test
    void isSamePaymentAttempt_falseForDraft() {
        OrderModel order = draft();

        assertThat(order.isSamePaymentAttempt("key-1")).isFalse();
        assertThat(order.isSamePaymentAttempt(null)).isFalse();
    }

    @DisplayName("[ASM-09] 주문의 소유자 판정.")
    @Test
    void isOwnedBy() {
        OrderModel order = OrderModel.create(1L, List.of(line(10L, 1, 100L)));

        assertThat(order.isOwnedBy(1L)).isTrue();
        assertThat(order.isOwnedBy(2L)).isFalse();
    }
}
