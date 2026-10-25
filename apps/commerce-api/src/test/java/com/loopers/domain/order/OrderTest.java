package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @Test
    void createsDraftAndCalculatesTotal() {
        Order order = Order.create(1L, List.of(
            OrderItem.create(10L, "Air Max", 100L, 2)
        ));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.getTotalAmount()).isEqualTo(200L);
        assertThat(order.getItems()).hasSize(1);
    }

    @DisplayName("품목이 비어 있으면 Order 생성을 BAD_REQUEST로 거절한다.")
    @Test
    void rejectsEmptyItems() {
        assertThatThrownBy(() -> Order.create(1L, List.of()))
            .isInstanceOfSatisfying(CoreException.class, exception -> {
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                assertThat(exception.getMessage()).isEqualTo("주문 품목은 하나 이상이어야 합니다.");
            });
    }

    @DisplayName("품목 목록이 null이면 Order 생성을 BAD_REQUEST로 거절한다.")
    @Test
    void rejectsNullItems() {
        assertThatThrownBy(() -> Order.create(1L, null))
            .isInstanceOfSatisfying(CoreException.class, exception -> {
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                assertThat(exception.getMessage()).isEqualTo("주문 품목은 하나 이상이어야 합니다.");
            });
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> OrderItem.create(10L, "Air Max", 100L, 0))
            .hasMessageContaining("수량");
    }

    @Test
    void mergesDuplicateProductItems() {
        Order order = Order.create(1L, List.of(
            OrderItem.create(10L, "Air Max", 100L, 2),
            OrderItem.create(10L, "Air Max", 100L, 3)
        ));

        assertThat(order.getItems()).singleElement()
            .extracting(OrderItem::getQuantity).isEqualTo(5);
        assertThat(order.getTotalAmount()).isEqualTo(500L);
    }

    @Test
    void mergesDuplicateItemsIntoANewValueWithoutChangingInputItems() {
        OrderItem firstItem = OrderItem.create(10L, "Air Max", 100L, 2);
        OrderItem secondItem = OrderItem.create(10L, "Air Max", 100L, 3);

        Order order = Order.create(1L, List.of(firstItem, secondItem));

        assertThat(firstItem.getQuantity()).isEqualTo(2);
        assertThat(secondItem.getQuantity()).isEqualTo(3);
        assertThat(order.getItems()).singleElement().satisfies(merged -> {
            assertThat(merged).isNotSameAs(firstItem).isNotSameAs(secondItem);
            assertThat(merged.getProductId()).isEqualTo(10L);
            assertThat(merged.getProductName()).isEqualTo("Air Max");
            assertThat(merged.getUnitPrice()).isEqualTo(100L);
            assertThat(merged.getQuantity()).isEqualTo(5);
            assertThat(merged.getAmount()).isEqualTo(500L);
        });
        assertThat(order.getTotalAmount()).isEqualTo(500L);
    }

    @Test
    void preservesAnEarlierOrderWhenItsItemIsReusedForAnotherOrder() {
        OrderItem sharedItem = OrderItem.create(10L, "Air Max", 100L, 2);
        Order earlierOrder = Order.create(1L, List.of(sharedItem));

        Order laterOrder = Order.create(2L, List.of(
            sharedItem, OrderItem.create(10L, "Air Max", 100L, 3)
        ));

        assertThat(earlierOrder.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.getAmount()).isEqualTo(200L);
        });
        assertThat(earlierOrder.getTotalAmount()).isEqualTo(200L);
        assertThat(laterOrder.getItems()).singleElement()
            .extracting(OrderItem::getQuantity).isEqualTo(5);
        assertThat(laterOrder.getTotalAmount()).isEqualTo(500L);
    }

    @Test
    void acceptsMergedQuantityAtTheIntegerLimit() {
        Order order = Order.create(1L, List.of(
            OrderItem.create(10L, "Air Max", 100L, Integer.MAX_VALUE - 1),
            OrderItem.create(10L, "Air Max", 100L, 1)
        ));

        assertThat(order.getItems()).singleElement()
            .extracting(OrderItem::getQuantity).isEqualTo(Integer.MAX_VALUE);
        assertThat(order.getTotalAmount()).isEqualTo(100L * Integer.MAX_VALUE);
    }

    @Test
    void rejectsMergedQuantityBeyondTheIntegerLimit() {
        OrderItem firstItem = OrderItem.create(10L, "Air Max", 100L, Integer.MAX_VALUE);
        OrderItem secondItem = OrderItem.create(10L, "Air Max", 100L, 1);

        assertThatThrownBy(() -> Order.create(1L, List.of(firstItem, secondItem)))
            .isInstanceOfSatisfying(CoreException.class, exception -> {
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                assertThat(exception.getCustomMessage()).contains("합산 주문 수량");
            });
        assertThat(firstItem.getQuantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(secondItem.getQuantity()).isEqualTo(1);
    }

    @Test
    void rejectsOverflowEvenWhenFurtherAdditionWouldWrapBackToPositive() {
        assertThatThrownBy(() -> Order.create(1L, List.of(
            OrderItem.create(10L, "Air Max", 100L, Integer.MAX_VALUE),
            OrderItem.create(10L, "Air Max", 100L, Integer.MAX_VALUE),
            OrderItem.create(10L, "Air Max", 100L, 3)
        ))).isInstanceOfSatisfying(CoreException.class, exception ->
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST)
        );
    }

    @Test
    void comparesOrderItemsByTheirSnapshotValues() {
        OrderItem item = OrderItem.create(10L, "Air Max", 100L, 2);
        OrderItem sameValue = OrderItem.create(10L, "Air Max", 100L, 2);

        assertThat(item).isEqualTo(sameValue).hasSameHashCodeAs(sameValue);
        assertThat(item).isNotEqualTo(OrderItem.create(11L, "Air Max", 100L, 2));
        assertThat(item).isNotEqualTo(OrderItem.create(10L, "Pegasus", 100L, 2));
        assertThat(item).isNotEqualTo(OrderItem.create(10L, "Air Max", 101L, 2));
        assertThat(item).isNotEqualTo(OrderItem.create(10L, "Air Max", 100L, 3));
    }

    @Test
    void acceptsOrderTotalAtTheLongLimit() {
        Order order = Order.create(1L, itemsAtTheLongTotalLimit());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.getTotalAmount()).isEqualTo(Long.MAX_VALUE);
        assertThat(order.getItems()).allSatisfy(item -> {
            assertThat(item.getUnitPrice()).isBetween(1L, 100_000_000L);
            assertThat(item.getQuantity()).isPositive();
        });
    }

    @Test
    void rejectsOrderTotalOneUnitBeyondTheLongLimit() {
        List<OrderItem> items = itemsAtTheLongTotalLimit();
        items.add(OrderItem.create((long) items.size() + 1L, "Extra product", 1L, 1));

        assertThatThrownBy(() -> Order.create(1L, items))
            .isInstanceOfSatisfying(CoreException.class, exception -> {
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                assertThat(exception.getCustomMessage()).contains("주문 총액");
            });
    }

    @Test
    void rejectsTotalOverflowAcrossProductsWithValidPriceAndQuantity() {
        List<OrderItem> items = IntStream.rangeClosed(1, 43)
            .mapToObj(productId -> OrderItem.create(
                (long) productId, "Product " + productId, 100_000_000L, Integer.MAX_VALUE
            ))
            .toList();

        assertThatThrownBy(() -> Order.create(1L, items))
            .isInstanceOfSatisfying(CoreException.class, exception -> {
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                assertThat(exception.getCustomMessage()).contains("주문 총액");
            });
    }

    @DisplayName("DRAFT 주문의 PG 결제 요청은 PAYMENT_PENDING으로 바꾸고 결제 정보는 비워 둔다.")
    @Test
    void requestsPgPaymentFromDraft() {
        Order order = draftOrder();

        order.requestPgPayment();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
        assertThat(order.getPaymentAmount()).isNull();
        assertThat(order.getPaymentResult()).isNull();
    }

    @DisplayName("DRAFT가 아닌 주문의 PG 결제 요청은 CONFLICT로 거절하고 상태를 유지한다.")
    @Test
    void rejectsPgPaymentRequestUnlessDraft() {
        Order pending = draftOrder();
        pending.requestPgPayment();
        Order pointConfirmed = Order.reconstitute(2L, 1L, OrderStatus.CONFIRMED, 200L, 200L, PaymentResult.SUCCESS,
            PaymentMethod.POINT, List.of(OrderItem.create(10L, "Air Max", 100L, 2)));

        assertConflict(pending::requestPgPayment);
        assertConflict(pointConfirmed::requestPgPayment);
        assertThat(pending.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(pointConfirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(pointConfirmed.getPaymentMethod()).isEqualTo(PaymentMethod.POINT);
    }

    @DisplayName("PG 결제 성공은 PAYMENT_PENDING 주문을 CONFIRMED로 바꾸고 총액과 SUCCESS를 저장한다.")
    @Test
    void completesPgPaymentFromPending() {
        Order order = draftOrder();
        order.requestPgPayment();

        order.completePgPayment();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
        assertThat(order.getPaymentAmount()).isEqualTo(200L);
        assertThat(order.getPaymentResult()).isEqualTo(PaymentResult.SUCCESS);
    }

    @DisplayName("PG 결제 실패는 PAYMENT_PENDING 주문을 PAYMENT_FAILED로 바꾸고 결제액은 비워 둔다.")
    @Test
    void failsPgPaymentFromPending() {
        Order order = draftOrder();
        order.requestPgPayment();

        order.failPgPayment();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
        assertThat(order.getPaymentAmount()).isNull();
        assertThat(order.getPaymentResult()).isEqualTo(PaymentResult.FAILED);
    }

    @DisplayName("PG 결제 요청과 실패 이후에도 재고 보상 기준인 합산 품목 스냅샷을 보존한다.")
    @Test
    void preservesItemSnapshotsForStockCompensation() {
        Order order = Order.create(1L, List.of(
            OrderItem.create(10L, "Air Max", 100L, 2),
            OrderItem.create(10L, "Air Max", 100L, 3),
            OrderItem.create(20L, "Pegasus", 200L, 3)
        ));
        List<OrderItem> snapshots = List.of(
            OrderItem.create(10L, "Air Max", 100L, 5),
            OrderItem.create(20L, "Pegasus", 200L, 3)
        );

        order.requestPgPayment();

        assertThat(order.getItems()).containsExactlyElementsOf(snapshots);

        order.failPgPayment();

        assertThat(order.getItems()).containsExactlyElementsOf(snapshots);
        assertThat(order.getTotalAmount()).isEqualTo(1_100L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
    }

    @DisplayName("PAYMENT_PENDING이 아닌 주문의 PG 결제 성공·실패 반영은 정합성 오류(INTERNAL_ERROR)로 거절하고 상태를 유지한다.")
    @Test
    void rejectsPgPaymentResultUnlessPending() {
        Order draft = draftOrder();
        Order failed = draftOrder();
        failed.requestPgPayment();
        failed.failPgPayment();
        Order completed = draftOrder();
        completed.requestPgPayment();
        completed.completePgPayment();

        assertIntegrityError(draft::completePgPayment);
        assertIntegrityError(draft::failPgPayment);
        assertIntegrityError(failed::completePgPayment);
        assertIntegrityError(completed::failPgPayment);
        assertThat(draft.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(failed.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(completed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(completed.getPaymentResult()).isEqualTo(PaymentResult.SUCCESS);
    }

    @DisplayName("복원은 저장된 결제수단을 그대로 쓰고 주문 상태로 결제수단을 추론하지 않는다.")
    @Test
    void restoresStoredPaymentMethodWithoutInferringFromStatus() {
        List<OrderItem> items = List.of(OrderItem.create(10L, "Air Max", 100L, 2));

        Order pointConfirmed = Order.reconstitute(
            1L, 1L, OrderStatus.CONFIRMED, 200L, 200L, PaymentResult.SUCCESS, PaymentMethod.POINT, items
        );
        Order pgConfirmed = Order.reconstitute(
            2L, 1L, OrderStatus.CONFIRMED, 200L, 200L, PaymentResult.SUCCESS, PaymentMethod.PG, items
        );
        Order draft = Order.reconstitute(3L, 1L, OrderStatus.DRAFT, 200L, null, null, null, items);

        assertThat(pointConfirmed.getPaymentMethod()).isEqualTo(PaymentMethod.POINT);
        assertThat(pgConfirmed.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
        assertThat(draft.getPaymentMethod()).isNull();
    }

    @DisplayName("DRAFT가 아닌 주문을 결제수단 없이 복원하면 정합성 오류(INTERNAL_ERROR)로 거절한다.")
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAYMENT_PENDING", "CONFIRMED", "PAYMENT_FAILED"})
    void rejectsRestoringPaidOrderWithoutPaymentMethod(OrderStatus status) {
        List<OrderItem> items = List.of(OrderItem.create(10L, "Air Max", 100L, 2));

        assertIntegrityError(() -> Order.reconstitute(1L, 1L, status, 200L, 200L, PaymentResult.SUCCESS, null, items));
    }

    private Order draftOrder() {
        return Order.create(1L, List.of(OrderItem.create(10L, "Air Max", 100L, 2)));
    }

    private void assertIntegrityError(Runnable transition) {
        assertThatThrownBy(transition::run)
            .isInstanceOfSatisfying(CoreException.class, exception ->
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR)
            );
    }

    private void assertConflict(Runnable transition) {
        assertThatThrownBy(transition::run)
            .isInstanceOfSatisfying(CoreException.class, exception ->
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT)
            );
    }

    private List<OrderItem> itemsAtTheLongTotalLimit() {
        long unitPrice = 100_000_000L;
        long fullItemAmount = unitPrice * Integer.MAX_VALUE;
        int fullItemCount = Math.toIntExact(Long.MAX_VALUE / fullItemAmount);
        List<OrderItem> items = new ArrayList<>();
        for (long productId = 1L; productId <= fullItemCount; productId++) {
            items.add(OrderItem.create(productId, "Product " + productId, unitPrice, Integer.MAX_VALUE));
        }
        long remainingAmount = Long.MAX_VALUE % fullItemAmount;
        items.add(OrderItem.create(
            (long) fullItemCount + 1L, "Remaining quantity", unitPrice,
            Math.toIntExact(remainingAmount / unitPrice)
        ));
        items.add(OrderItem.create(
            (long) fullItemCount + 2L, "Remaining amount", remainingAmount % unitPrice, 1
        ));
        return items;
    }
}
