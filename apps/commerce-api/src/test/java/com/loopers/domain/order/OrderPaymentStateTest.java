package com.loopers.domain.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderPaymentStateTest {
    private static final ZonedDateTime FIRST_TIME = ZonedDateTime.parse("2026-10-23T12:34:56.123456789+09:00");

    @Test
    void newDraftHasNoPaymentMethodOrReservation() {
        Order order = order();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.getPaymentMethod()).isNull();
        assertThat(order.getPaidAmount()).isNull();
        assertThat(order.getConfirmedAt()).isNull();
        assertThat(order.getItems()).extracting(OrderItem::getReservationStatus)
            .containsOnly(OrderItemReservationStatus.NOT_RESERVED);
    }

    @Test
    void preparesAllItemsWithoutChangingMoneySnapshotsOrProductStock() {
        Product product = new Product(new Brand("브랜드"), "상품", 300L, 5);
        Order order = Order.create(new User(1L), List.of(new OrderItem(product, 2)));
        List<ItemSnapshot> originalItems = itemSnapshots(order);

        order.preparePgPayment();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getPaymentMethod()).isEqualTo(OrderPaymentMethod.PG);
        assertThat(order.getTotalAmount()).isEqualTo(600L);
        assertThat(order.getPaidAmount()).isNull();
        assertThat(order.getConfirmedAt()).isNull();
        assertThat(order.getItems()).extracting(OrderItem::getReservationStatus)
            .containsOnly(OrderItemReservationStatus.HELD);
        assertThat(itemSnapshots(order).getFirst().withReservation(OrderItemReservationStatus.NOT_RESERVED))
            .isEqualTo(originalItems.getFirst());
        assertThat(product.getStockQuantity()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void createdTwoItemOrderMovesThroughPreparationToItsFinalPgResult(boolean success) {
        Order order = order();
        List<ItemSnapshot> originalItems = itemSnapshots(order);

        order.preparePgPayment();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getPaymentMethod()).isEqualTo(OrderPaymentMethod.PG);
        assertThat(order.getPaidAmount()).isNull();
        assertThat(order.getConfirmedAt()).isNull();
        assertThat(order.getItems()).hasSize(2).extracting(OrderItem::getReservationStatus)
            .containsExactly(OrderItemReservationStatus.HELD, OrderItemReservationStatus.HELD);

        if (success) {
            assertThat(order.confirmPgPayment(FIRST_TIME)).isTrue();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getPaidAmount()).isEqualTo(800L);
            assertThat(order.getConfirmedAt()).isEqualTo(FIRST_TIME.truncatedTo(ChronoUnit.MICROS));
        } else {
            assertThat(order.failPgPayment()).isTrue();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(order.getPaidAmount()).isNull();
            assertThat(order.getConfirmedAt()).isNull();
        }

        OrderItemReservationStatus finalReservation = success
            ? OrderItemReservationStatus.CONSUMED : OrderItemReservationStatus.RELEASED;
        assertThat(order.getItems()).extracting(OrderItem::getReservationStatus)
            .containsExactly(finalReservation, finalReservation);
        assertThat(order.getPaymentMethod()).isEqualTo(OrderPaymentMethod.PG);
        assertThat(order.getTotalAmount()).isEqualTo(800L);
        assertThat(itemSnapshots(order).stream()
            .map(item -> item.withReservation(OrderItemReservationStatus.NOT_RESERVED)).toList())
            .containsExactlyElementsOf(originalItems);
    }

    @Test
    void confirmsPgOnceAndKeepsFirstTimestampAndAmountOnDuplicate() {
        Order order = storedOrder(OrderStatus.PAYMENT_PENDING, OrderPaymentMethod.PG);

        assertThat(order.confirmPgPayment(FIRST_TIME)).isTrue();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaymentMethod()).isEqualTo(OrderPaymentMethod.PG);
        assertThat(order.getPaidAmount()).isEqualTo(800L);
        assertThat(order.getConfirmedAt()).isEqualTo(FIRST_TIME.truncatedTo(ChronoUnit.MICROS));
        assertThat(order.getItems()).extracting(OrderItem::getReservationStatus)
            .containsOnly(OrderItemReservationStatus.CONSUMED);
        Snapshot first = snapshot(order);
        assertThat(order.confirmPgPayment(FIRST_TIME.plusDays(1))).isFalse();
        assertThat(snapshot(order)).isEqualTo(first);
    }

    @Test
    void failsPgOnceWithoutInventingSuccessAmountOrTimestamp() {
        Order order = storedOrder(OrderStatus.PAYMENT_PENDING, OrderPaymentMethod.PG);

        assertThat(order.failPgPayment()).isTrue();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(order.getPaymentMethod()).isEqualTo(OrderPaymentMethod.PG);
        assertThat(order.getPaidAmount()).isNull();
        assertThat(order.getConfirmedAt()).isNull();
        assertThat(order.getItems()).extracting(OrderItem::getReservationStatus)
            .containsOnly(OrderItemReservationStatus.RELEASED);
        Snapshot first = snapshot(order);
        assertThat(order.failPgPayment()).isFalse();
        assertThat(snapshot(order)).isEqualTo(first);
    }

    @Test
    void pointConfirmationRecordsPointWithoutInventingPgReservationsAndKeepsLegacyConflict() {
        Order order = order();

        order.confirm(FIRST_TIME);

        assertThat(order.getPaymentMethod()).isEqualTo(OrderPaymentMethod.POINT);
        assertThat(order.getConfirmedAt()).isEqualTo(FIRST_TIME.truncatedTo(ChronoUnit.MICROS));
        assertThat(order.getItems()).extracting(OrderItem::getReservationStatus)
            .containsOnly(OrderItemReservationStatus.NOT_RESERVED);
        Snapshot first = snapshot(order);
        assertThatThrownBy(() -> order.confirm(FIRST_TIME.plusDays(1)))
            .isInstanceOfSatisfying(OrderException.class,
                error -> assertThat(error.getReason()).isEqualTo(OrderException.Reason.ORDER_ALREADY_CONFIRMED));
        assertThat(snapshot(order)).isEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, Long.MAX_VALUE})
    void preservesNonNegativeStoredAmountBoundaries(long amount) {
        OrderItem item = new OrderItem(new Product(new Brand("브랜드"), "상품", Math.max(1L, amount), 1), 1);
        // 기존 스키마의 비음수 snapshot 표현을 검증한다. 신규 상품 가격의 양수 규칙은 바꾸지 않는다.
        setField(item, "unitPrice", amount);
        Order order = Order.create(new User(1L), List.of(item));

        order.preparePgPayment();
        assertThat(order.confirmPgPayment(FIRST_TIME)).isTrue();

        assertThat(order.getTotalAmount()).isEqualTo(amount);
        assertThat(order.getPaidAmount()).isEqualTo(amount);
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void requireDraftAcceptsOnlyDraftAndPreservesEveryState(OrderStatus status) {
        Order order = storedOrder(status, status == OrderStatus.DRAFT ? null : OrderPaymentMethod.PG);
        Snapshot before = snapshot(order);

        if (status == OrderStatus.DRAFT) {
            order.requireDraft();
        } else if (status == OrderStatus.CONFIRMED) {
            assertThatThrownBy(order::requireDraft).isInstanceOfSatisfying(OrderException.class,
                error -> assertThat(error.getReason()).isEqualTo(OrderException.Reason.ORDER_ALREADY_CONFIRMED));
        } else {
            assertThatThrownBy(order::requireDraft).isInstanceOf(IllegalStateException.class);
        }

        assertThat(snapshot(order)).isEqualTo(before);
    }

    @ParameterizedTest
    @MethodSource("forbiddenTransitions")
    void rejectsForbiddenTransitionsWithoutPartialChanges(OrderStatus status, OrderPaymentMethod method,
                                                         Action action) {
        Order order = storedOrder(status, method);
        Snapshot before = snapshot(order);

        if (status == OrderStatus.CONFIRMED && (action == Action.PREPARE || action == Action.POINT)) {
            assertThatThrownBy(() -> action.apply(order)).isInstanceOfSatisfying(OrderException.class,
                error -> assertThat(error.getReason()).isEqualTo(OrderException.Reason.ORDER_ALREADY_CONFIRMED));
        } else {
            assertThatThrownBy(() -> action.apply(order)).isInstanceOf(IllegalStateException.class);
        }

        assertThat(snapshot(order)).isEqualTo(before);
    }

    @ParameterizedTest
    @MethodSource("invalidLastReservations")
    void validatesEveryReservationBeforeChangingTheFirstItem(Action action, OrderItemReservationStatus invalid) {
        Order order = orderFor(action);
        setField(order.getItems().getLast(), "reservationStatus", invalid);
        Snapshot before = snapshot(order);

        assertThatThrownBy(() -> action.apply(order)).isInstanceOf(IllegalStateException.class);

        assertThat(snapshot(order)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nullConfirmationTimeDoesNotPartiallyConsumeReservations(boolean pg) {
        Order order = pg ? storedOrder(OrderStatus.PAYMENT_PENDING, OrderPaymentMethod.PG) : order();
        Snapshot before = snapshot(order);

        assertThatThrownBy(() -> {
            if (pg) {
                order.confirmPgPayment(null);
            } else {
                order.confirm(null);
            }
        }).isInstanceOf(NullPointerException.class);

        assertThat(snapshot(order)).isEqualTo(before);
    }

    @ParameterizedTest
    @MethodSource("corruptStates")
    void rejectsCorruptStoredStateBeforeAnyMutation(Action action, Corruption corruption) {
        Order order = orderFor(action);
        corruption.apply(order);
        Snapshot before = snapshot(order);

        assertThatThrownBy(() -> action.apply(order)).isInstanceOf(IllegalStateException.class);

        assertThat(snapshot(order)).isEqualTo(before);
    }

    @ParameterizedTest
    @MethodSource("corruptTerminalStates")
    void duplicatePgResultStillChecksStoredMethodAndCompleteReservationState(OrderStatus status,
                                                                            Corruption corruption) {
        Order order = storedOrder(status, OrderPaymentMethod.PG);
        corruption.apply(order);
        Snapshot before = snapshot(order);

        assertThatThrownBy(() -> {
            if (status == OrderStatus.CONFIRMED) {
                order.confirmPgPayment(FIRST_TIME.plusDays(1));
            } else {
                order.failPgPayment();
            }
        }).isInstanceOf(IllegalStateException.class);

        assertThat(snapshot(order)).isEqualTo(before);
    }

    @ParameterizedTest
    @MethodSource("invalidTerminalPaymentFields")
    void duplicatePgResultRejectsMissingOrContradictoryPaymentFactsWithoutMutation(OrderStatus status,
                                                                                 String field, Object value) {
        Order order = storedOrder(status, OrderPaymentMethod.PG);
        setField(order, field, value);
        Snapshot before = snapshot(order);

        assertThatThrownBy(() -> {
            if (status == OrderStatus.CONFIRMED) {
                order.confirmPgPayment(FIRST_TIME.plusDays(1));
            } else {
                order.failPgPayment();
            }
        }).isInstanceOf(IllegalStateException.class);

        assertThat(snapshot(order)).isEqualTo(before);
    }

    private static Stream<Arguments> invalidTerminalPaymentFields() {
        return Stream.of(
            Arguments.of(OrderStatus.CONFIRMED, "paidAmount", null),
            Arguments.of(OrderStatus.CONFIRMED, "paidAmount", 799L),
            Arguments.of(OrderStatus.CONFIRMED, "confirmedAt", null),
            Arguments.of(OrderStatus.PAYMENT_FAILED, "paidAmount", 0L),
            Arguments.of(OrderStatus.PAYMENT_FAILED, "confirmedAt", FIRST_TIME)
        );
    }

    private static Stream<Arguments> forbiddenTransitions() {
        return Stream.of(
            Arguments.of(OrderStatus.DRAFT, null, Action.SUCCESS),
            Arguments.of(OrderStatus.DRAFT, null, Action.FAILURE),
            Arguments.of(OrderStatus.PAYMENT_PENDING, OrderPaymentMethod.PG, Action.PREPARE),
            Arguments.of(OrderStatus.PAYMENT_PENDING, OrderPaymentMethod.PG, Action.POINT),
            Arguments.of(OrderStatus.CONFIRMED, OrderPaymentMethod.PG, Action.PREPARE),
            Arguments.of(OrderStatus.CONFIRMED, OrderPaymentMethod.PG, Action.POINT),
            Arguments.of(OrderStatus.CONFIRMED, OrderPaymentMethod.PG, Action.FAILURE),
            Arguments.of(OrderStatus.PAYMENT_FAILED, OrderPaymentMethod.PG, Action.PREPARE),
            Arguments.of(OrderStatus.PAYMENT_FAILED, OrderPaymentMethod.PG, Action.POINT),
            Arguments.of(OrderStatus.PAYMENT_FAILED, OrderPaymentMethod.PG, Action.SUCCESS),
            Arguments.of(OrderStatus.CONFIRMED, OrderPaymentMethod.POINT, Action.PREPARE),
            Arguments.of(OrderStatus.CONFIRMED, OrderPaymentMethod.POINT, Action.POINT),
            Arguments.of(OrderStatus.CONFIRMED, OrderPaymentMethod.POINT, Action.SUCCESS),
            Arguments.of(OrderStatus.CONFIRMED, OrderPaymentMethod.POINT, Action.FAILURE)
        );
    }

    private static Stream<Arguments> invalidLastReservations() {
        return Stream.of(Action.values()).flatMap(action -> Stream.of(OrderItemReservationStatus.values())
            .filter(state -> state != action.sourceReservation())
            .map(state -> Arguments.of(action, state)));
    }

    private static Stream<Arguments> corruptStates() {
        return Stream.of(Action.values()).flatMap(action -> Stream.of(Corruption.values())
            .map(corruption -> Arguments.of(action, corruption)));
    }

    private static Stream<Arguments> corruptTerminalStates() {
        return Stream.of(OrderStatus.CONFIRMED, OrderStatus.PAYMENT_FAILED).flatMap(status -> Stream.of(
            Corruption.MISSING_METHOD, Corruption.WRONG_METHOD, Corruption.NULL_RESERVATION,
            Corruption.TOTAL_MISMATCH).map(corruption -> Arguments.of(status, corruption)));
    }

    private static Order orderFor(Action action) {
        return action == Action.PREPARE || action == Action.POINT ? order()
            : storedOrder(OrderStatus.PAYMENT_PENDING, OrderPaymentMethod.PG);
    }

    // 저장된 정상 조합을 직접 구성하여 Red 단계의 전이 구현과 fixture 준비를 분리한다.
    private static Order storedOrder(OrderStatus status, OrderPaymentMethod method) {
        Order order = order();
        setField(order, "status", status);
        setField(order, "paymentMethod", method);
        OrderItemReservationStatus reservation = switch (status) {
            case DRAFT -> OrderItemReservationStatus.NOT_RESERVED;
            case PAYMENT_PENDING -> OrderItemReservationStatus.HELD;
            case PAYMENT_FAILED -> OrderItemReservationStatus.RELEASED;
            case CONFIRMED -> method == OrderPaymentMethod.POINT
                ? OrderItemReservationStatus.NOT_RESERVED : OrderItemReservationStatus.CONSUMED;
        };
        order.getItems().forEach(item -> setField(item, "reservationStatus", reservation));
        if (status == OrderStatus.CONFIRMED) {
            setField(order, "paidAmount", order.getTotalAmount());
            setField(order, "confirmedAt", FIRST_TIME.truncatedTo(ChronoUnit.MICROS));
        }
        return order;
    }

    private static Order order() {
        Brand brand = new Brand("브랜드");
        return Order.create(new User(1L), List.of(
            new OrderItem(new Product(brand, "첫 상품", 100L, 5), 2),
            new OrderItem(new Product(brand, "마지막 상품", 200L, 7), 3)));
    }

    private static Snapshot snapshot(Order order) {
        return new Snapshot(order.getStatus(), order.getPaymentMethod(), order.getTotalAmount(),
            order.getPaidAmount(), order.getConfirmedAt(), itemSnapshots(order));
    }

    private static List<ItemSnapshot> itemSnapshots(Order order) {
        return order.getItems().stream().map(item -> new ItemSnapshot(item.getProductId(), item.getProductName(),
            item.getUnitPrice(), item.getQuantity(), item.getReservationStatus())).toList();
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create stored-state fixture", exception);
        }
    }

    private enum Action {
        PREPARE,
        POINT,
        SUCCESS,
        FAILURE;

        void apply(Order order) {
            switch (this) {
                case PREPARE -> order.preparePgPayment();
                case POINT -> order.confirm(FIRST_TIME);
                case SUCCESS -> order.confirmPgPayment(FIRST_TIME);
                case FAILURE -> order.failPgPayment();
            }
        }

        OrderItemReservationStatus sourceReservation() {
            return this == PREPARE || this == POINT
                ? OrderItemReservationStatus.NOT_RESERVED : OrderItemReservationStatus.HELD;
        }
    }

    private enum Corruption {
        TOTAL_MISMATCH,
        NEGATIVE_TOTAL,
        EMPTY_ITEMS,
        ZERO_QUANTITY,
        NEGATIVE_QUANTITY,
        NEGATIVE_PRICE,
        SUBTOTAL_OVERFLOW,
        TOTAL_OVERFLOW,
        NULL_RESERVATION,
        UNEXPECTED_PAYMENT,
        UNEXPECTED_CONFIRMED_AT,
        MISSING_METHOD,
        WRONG_METHOD;

        void apply(Order order) {
            OrderItem last = order.getItems().getLast();
            switch (this) {
                case TOTAL_MISMATCH -> setField(order, "totalAmount", 801L);
                case NEGATIVE_TOTAL -> setField(order, "totalAmount", -1L);
                case EMPTY_ITEMS -> setField(order, "items", new ArrayList<>());
                case ZERO_QUANTITY -> setField(last, "quantity", 0);
                case NEGATIVE_QUANTITY -> setField(last, "quantity", -1);
                case NEGATIVE_PRICE -> setField(last, "unitPrice", -1L);
                case SUBTOTAL_OVERFLOW -> setField(last, "unitPrice", Long.MAX_VALUE);
                case TOTAL_OVERFLOW -> {
                    setField(last, "unitPrice", Long.MAX_VALUE);
                    setField(last, "quantity", 1);
                }
                case NULL_RESERVATION -> setField(last, "reservationStatus", null);
                case UNEXPECTED_PAYMENT -> setField(order, "paidAmount", 0L);
                case UNEXPECTED_CONFIRMED_AT -> setField(order, "confirmedAt", FIRST_TIME);
                case MISSING_METHOD -> setField(order, "paymentMethod", order.getStatus() == OrderStatus.DRAFT
                    ? OrderPaymentMethod.PG : null);
                case WRONG_METHOD -> setField(order, "paymentMethod", OrderPaymentMethod.POINT);
            }
        }
    }

    private record Snapshot(OrderStatus status, OrderPaymentMethod method, long total, Long paid,
                            ZonedDateTime confirmedAt, List<ItemSnapshot> items) {
    }

    private record ItemSnapshot(Long productId, String name, long unitPrice, int quantity,
                                OrderItemReservationStatus reservation) {
        ItemSnapshot withReservation(OrderItemReservationStatus status) {
            return new ItemSnapshot(productId, name, unitPrice, quantity, status);
        }
    }
}
