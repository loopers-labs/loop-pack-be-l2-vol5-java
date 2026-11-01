package com.loopers.domain.order;

import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("Order 는 PG 결제 시작·확정·실패 전이와 결제 방식·금액 규칙을 책임진다.")
class OrderPgPaymentTest {

    /** 서비스가 UTC 정규화·마이크로초 절삭해 넘기는 형태의 고정 확정 시각. */
    private static final ZonedDateTime CONFIRMED_AT = ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 123_456_000, ZoneOffset.UTC);

    private OrderModel draft() {
        return OrderModel.draft(1L, List.of(
            OrderItemModel.of(1L, 2L, Money.of(2_000L)), OrderItemModel.of(2L, 1L, Money.of(3_000L))));
    }

    private OrderModel pending() {
        OrderModel order = draft();
        order.startPgPayment();
        return order;
    }

    @DisplayName("DRAFT 의 결제 방식·사용액·결제액은 비어 있다.")
    @Test
    void draftHasNoPayment() {
        OrderModel order = draft();

        assertAll(
            () -> assertThat(order.getPaymentMethod()).isNull(),
            () -> assertThat(order.getPointAmount()).isNull(),
            () -> assertThat(order.getPaidAmount()).isNull(),
            () -> assertThat(order.getTotalAmount()).isEqualTo(Money.of(7_000L))
        );
    }

    @DisplayName("PG 결제를 시작하면 PG·포인트 사용액 0·PAYMENT_PENDING 이고 결제액은 비어 있다.")
    @Test
    void startsPgPayment() {
        OrderModel order = pending();

        assertAll(
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING),
            () -> assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG),
            () -> assertThat(order.getPointAmount()).isZero(),
            () -> assertThat(order.getPaidAmount()).isNull()
        );
    }

    @DisplayName("DRAFT 가 아니면 PG 결제를 시작할 수 없다.")
    @Test
    void rejectsStartUnlessDraft() {
        OrderModel order = pending();

        assertThatThrownBy(order::startPgPayment)
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.ORDER_NOT_CONFIRMABLE);
    }

    @DisplayName("주문 총액과 같은 성공 금액이면 결제액을 기록하고 CONFIRMED 다.")
    @Test
    void confirmsWithSameAmount() {
        OrderModel order = pending();

        order.confirmPg(Money.of(7_000L), CONFIRMED_AT);

        assertAll(
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(order.getPaidAmount()).isEqualTo(Money.of(7_000L)),
            () -> assertThat(order.getPointAmount()).isZero(),
            () -> assertThat(order.getConfirmedAt()).isEqualTo(CONFIRMED_AT)
        );
    }

    @DisplayName("성공 금액이 총액과 다르면 내부 오류이고 상태를 바꾸지 않는다.")
    @Test
    void rejectsDifferentAmount() {
        OrderModel order = pending();

        assertThatThrownBy(() -> order.confirmPg(Money.of(6_999L), CONFIRMED_AT))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getConfirmedAt()).isNull();
    }

    @DisplayName("실패 확정이면 결제액 없이 PAYMENT_FAILED 다.")
    @Test
    void failsPg() {
        OrderModel order = pending();

        order.failPg();

        assertAll(
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED),
            () -> assertThat(order.getPaidAmount()).isNull(),
            () -> assertThat(order.getConfirmedAt()).isNull()
        );
    }

    @DisplayName("PG 결제 대기가 아니면 확정·실패 전이는 내부 오류다.")
    @Test
    void rejectsTransitionsOutsidePending() {
        OrderModel draft = draft();
        OrderModel confirmed = pending();
        confirmed.confirmPg(Money.of(7_000L), CONFIRMED_AT);

        assertAll(
            () -> assertThatThrownBy(() -> draft.confirmPg(Money.of(7_000L), CONFIRMED_AT))
                .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThatThrownBy(draft::failPg).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThatThrownBy(confirmed::failPg).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR)
        );
    }

    @DisplayName("기존 포인트 확정은 결제 방식을 POINT 로 남긴다.")
    @Test
    void pointConfirmationKeepsPointMethod() {
        OrderModel order = draft();

        order.confirmWithPoints(7_000L);

        assertAll(
            () -> assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.POINT),
            () -> assertThat(order.getPointAmount()).isEqualTo(7_000L),
            () -> assertThat(order.getPaidAmount()).isEqualTo(Money.of(7_000L)),
            () -> assertThat(order.getConfirmedAt()).as("POINT 확정에는 PG 확정 시각을 추정하지 않는다").isNull()
        );
    }

    @DisplayName("DRAFT·PG 대기·실패는 확정 시각이 없다.")
    @Test
    void hasNoConfirmedAtBeforeConfirmation() {
        OrderModel failed = pending();
        failed.failPg();

        assertAll(
            () -> assertThat(draft().getConfirmedAt()).isNull(),
            () -> assertThat(pending().getConfirmedAt()).isNull(),
            () -> assertThat(failed.getConfirmedAt()).isNull()
        );
    }

    static Stream<ZonedDateTime> invalidConfirmedAt() {
        return Stream.of(
            null,
            CONFIRMED_AT.withZoneSameInstant(ZoneId.of("Asia/Seoul")),
            CONFIRMED_AT.withZoneSameInstant(ZoneOffset.ofHours(9)),
            ZonedDateTime.of(2026, 1, 15, 7, 0, 0, 0, ZoneId.of("Europe/London")),
            CONFIRMED_AT.withNano(123_456_789),
            CONFIRMED_AT.withNano(123_456_001)
        );
    }

    @DisplayName("확정 시각이 없거나, 고정 UTC 가 아니거나(offset 0 인 지역 zone 포함), 마이크로초보다 정밀하면 거절하고 아무것도 바꾸지 않는다.")
    @ParameterizedTest
    @MethodSource("invalidConfirmedAt")
    void rejectsInvalidConfirmedAtWithoutPartialChange(ZonedDateTime confirmedAt) {
        OrderModel order = pending();

        assertThatThrownBy(() -> order.confirmPg(Money.of(7_000L), confirmedAt))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR);
        assertAll(
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING),
            () -> assertThat(order.getPaidAmount()).isNull(),
            () -> assertThat(order.getConfirmedAt()).isNull()
        );
    }

    @DisplayName("정규화하면 고정 UTC 가 되는 UTC·Etc/UTC·GMT zone 은 받아들인다.")
    @Test
    void acceptsZonesNormalizedToUtc() {
        for (String zone : List.of("UTC", "Etc/UTC", "GMT")) {
            OrderModel order = pending();
            ZonedDateTime confirmedAt = CONFIRMED_AT.withZoneSameInstant(ZoneId.of(zone));

            order.confirmPg(Money.of(7_000L), confirmedAt);

            assertThat(order.getConfirmedAt()).as(zone).isEqualTo(confirmedAt);
        }
    }

    @DisplayName("이미 확정한 주문은 다시 확정할 수 없고 최초 금액·상태·시각이 그대로다.")
    @Test
    void rejectsReconfirmationKeepingFirstValues() {
        OrderModel order = pending();
        order.confirmPg(Money.of(7_000L), CONFIRMED_AT);

        assertThatThrownBy(() -> order.confirmPg(Money.of(7_000L), CONFIRMED_AT.plusSeconds(1)))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR);
        assertAll(
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(order.getPaidAmount()).isEqualTo(Money.of(7_000L)),
            () -> assertThat(order.getConfirmedAt()).isEqualTo(CONFIRMED_AT)
        );
    }

    @DisplayName("확정 시각이 남은 손상된 DRAFT·PG 대기 주문은 결제 시작·확정·실패를 거절하고 아무것도 바꾸지 않는다.")
    @Test
    void rejectsTransitionsWhenConfirmedAtAlreadyStored() {
        OrderModel corruptDraft = draft();
        ReflectionTestUtils.setField(corruptDraft, "confirmedAt", CONFIRMED_AT);
        OrderModel corruptPending = pending();
        ReflectionTestUtils.setField(corruptPending, "confirmedAt", CONFIRMED_AT);

        assertAll(
            () -> assertThatThrownBy(corruptDraft::startPgPayment).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThat(corruptDraft.getStatus()).isEqualTo(OrderStatus.DRAFT),
            () -> assertThat(corruptDraft.getPaymentMethod()).isNull(),
            () -> assertThatThrownBy(() -> corruptPending.confirmPg(Money.of(7_000L), CONFIRMED_AT.plusSeconds(1)))
                .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThatThrownBy(corruptPending::failPg).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThat(corruptPending.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING),
            () -> assertThat(corruptPending.getPaidAmount()).isNull(),
            () -> assertThat(corruptPending.getConfirmedAt()).isEqualTo(CONFIRMED_AT)
        );
    }
}
