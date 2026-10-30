package com.loopers.domain.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmedEventTest {
    private static final ZonedDateTime FIRST_TIME = ZonedDateTime.parse("2026-10-29T12:34:56.123456789+09:00");
    private static final Instant STORED_TIME = Instant.parse("2026-10-29T03:34:56.123456Z");

    @Test
    void capturesFirstPgConfirmationAndKeepsSameIdentityOnRepeatedSnapshot() {
        Order order = confirmedOrder();

        OrderConfirmedEvent event = OrderConfirmedEvent.from(order);

        assertThat(event.eventType()).isEqualTo("OrderConfirmed");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.eventId()).isEqualTo("order-confirmed:v1:42");
        assertThat(event.orderId()).isEqualTo(42);
        assertThat(event.key()).isEqualTo("42");
        assertThat(event.confirmedAt()).isEqualTo(STORED_TIME);
        assertThat(event.items()).containsExactly(new OrderConfirmedEvent.Item(7, 2),
            new OrderConfirmedEvent.Item(8, 1));

        assertThat(order.confirmPgPayment(FIRST_TIME.plusDays(1))).isFalse();
        assertThat(OrderConfirmedEvent.from(order)).isEqualTo(event);
        assertThat(order.getItems()).extracting(OrderItem::getReservationStatus)
            .containsOnly(OrderItemReservationStatus.CONSUMED);
    }

    @Test
    void treatsReorderedAndSplitItemsAsTheSameFact() {
        OrderConfirmedEvent split = event(List.of(new OrderConfirmedEvent.Item(8, 1),
            new OrderConfirmedEvent.Item(7, 1), new OrderConfirmedEvent.Item(7, 1)));

        assertThat(split).isEqualTo(OrderConfirmedEvent.from(confirmedOrder()));
        assertThat(split.items()).containsExactly(new OrderConfirmedEvent.Item(7, 2),
            new OrderConfirmedEvent.Item(8, 1));
    }

    @Test
    void doesNotRetainMutableItemCollections() {
        List<OrderConfirmedEvent.Item> source = new ArrayList<>(List.of(new OrderConfirmedEvent.Item(7, 2)));
        OrderConfirmedEvent event = event(source);

        source.clear();

        assertThat(event.items()).containsExactly(new OrderConfirmedEvent.Item(7, 2));
        assertThatThrownBy(() -> event.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void usesOrderSnapshotsEvenAfterProductChangesAndDeletion() {
        Product product = product(7, "첫 상품", 100);
        Order order = order(List.of(new OrderItem(product, 2)));
        order.preparePgPayment();
        order.confirmPgPayment(FIRST_TIME);
        OrderConfirmedEvent event = OrderConfirmedEvent.from(order);

        product.update("바뀐 상품", 999);
        product.delete(FIRST_TIME.plusDays(1));

        assertThat(OrderConfirmedEvent.from(order)).isEqualTo(event);
        assertThat(event.items()).containsExactly(new OrderConfirmedEvent.Item(7, 2));
        assertThat(order.getTotalAmount()).isEqualTo(200);
        assertThat(product.getStockQuantity()).isEqualTo(5);
        assertThat(product.isDeleted()).isTrue();
    }

    @Test
    void serializesOnlyContractFieldsAndNoEntityOrPaymentDetails() throws Exception {
        var mapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(OrderConfirmedEvent.from(confirmedOrder())));

        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("eventType", "schemaVersion", "eventId", "orderId",
            "confirmedAt", "items");
        assertThat(json.get("eventType").asText()).isEqualTo("OrderConfirmed");
        assertThat(json.get("schemaVersion").isIntegralNumber()).isTrue();
        assertThat(json.get("schemaVersion").intValue()).isEqualTo(1);
        assertThat(json.get("orderId").isIntegralNumber()).isTrue();
        assertThat(json.get("orderId").longValue()).isEqualTo(42L);
        assertThat(json.get("eventId").asText()).isEqualTo("order-confirmed:v1:42");
        assertThat(json.get("confirmedAt").asText()).isEqualTo(STORED_TIME.toString());
        assertThat(json.get("items")).hasSize(2);
        for (JsonNode item : json.get("items")) {
            List<String> itemFields = new ArrayList<>();
            item.fieldNames().forEachRemaining(itemFields::add);
            assertThat(itemFields).containsExactlyInAnyOrder("productId", "quantity");
            assertThat(item.get("productId").isIntegralNumber()).isTrue();
            assertThat(item.get("quantity").isIntegralNumber()).isTrue();
        }
        assertThat(json.get("items").get(0).get("productId").longValue()).isEqualTo(7L);
        assertThat(json.get("items").get(0).get("quantity").intValue()).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("invalidMetadata")
    void rejectsUnsupportedMetadataAndNonCanonicalEventIdentity(String type, int version, String eventId) {
        assertThatThrownBy(() -> new OrderConfirmedEvent(type, version, eventId, 42, STORED_TIME,
            List.of(new OrderConfirmedEvent.Item(7, 2)))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonPositiveOrderId(long orderId) {
        assertThatThrownBy(() -> new OrderConfirmedEvent("OrderConfirmed", 1, "order-confirmed:v1:" + orderId,
            orderId, STORED_TIME, List.of(new OrderConfirmedEvent.Item(7, 2))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesMaximumIdsAndExactlyMaximumCombinedQuantity() {
        var event = new OrderConfirmedEvent("OrderConfirmed", 1, "order-confirmed:v1:" + Long.MAX_VALUE,
            Long.MAX_VALUE, STORED_TIME, List.of(new OrderConfirmedEvent.Item(Long.MAX_VALUE, Integer.MAX_VALUE - 1),
                new OrderConfirmedEvent.Item(Long.MAX_VALUE, 1)));

        assertThat(event.key()).isEqualTo("9223372036854775807");
        assertThat(event.eventId()).isEqualTo("order-confirmed:v1:9223372036854775807");
        assertThat(event.items()).containsExactly(new OrderConfirmedEvent.Item(Long.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void rejectsMissingConfirmationTimeInsteadOfInventingCurrentTime() {
        assertThatThrownBy(() -> new OrderConfirmedEvent("OrderConfirmed", 1, "order-confirmed:v1:42", 42, null,
            List.of(new OrderConfirmedEvent.Item(7, 2)))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidItems")
    void rejectsMissingOrEmptyItems(List<OrderConfirmedEvent.Item> items) {
        assertThatThrownBy(() -> event(items)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidItemValues")
    void rejectsNonPositiveItemValuesBeforeTheyCanBeHiddenBySummation(long productId, int quantity) {
        assertThatThrownBy(() -> event(List.of(new OrderConfirmedEvent.Item(7, 2),
            new OrderConfirmedEvent.Item(productId, quantity))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsQuantitySumOverflow() {
        assertThatThrownBy(() -> event(List.of(new OrderConfirmedEvent.Item(7, Integer.MAX_VALUE),
            new OrderConfirmedEvent.Item(8, 1), new OrderConfirmedEvent.Item(7, 1))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void rejectsDraftPendingFailedAndHistoricalPointOrdersWithoutChangingThem(OrderStatus status) {
        Order order = order(List.of(new OrderItem(product(7, "상품", 100), 2)));
        switch (status) {
            case DRAFT -> { }
            case PAYMENT_PENDING -> order.preparePgPayment();
            case PAYMENT_FAILED -> {
                order.preparePgPayment();
                order.failPgPayment();
            }
            case CONFIRMED -> order.confirm(FIRST_TIME);
        }
        OrderPaymentMethod methodBefore = order.getPaymentMethod();
        ZonedDateTime timeBefore = order.getConfirmedAt();
        List<OrderItemReservationStatus> reservationsBefore = order.getItems().stream()
            .map(OrderItem::getReservationStatus).toList();

        assertThatThrownBy(() -> OrderConfirmedEvent.from(order)).isInstanceOf(IllegalStateException.class);

        assertThat(order.getStatus()).isEqualTo(status);
        assertThat(order.getPaymentMethod()).isEqualTo(methodBefore);
        assertThat(order.getConfirmedAt()).isEqualTo(timeBefore);
        assertThat(order.getItems()).extracting(OrderItem::getReservationStatus)
            .containsExactlyElementsOf(reservationsBefore);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidStoredStates")
    void rejectsInconsistentConfirmedPgState(String description, Consumer<Order> corrupt) {
        Order order = confirmedOrder();
        corrupt.accept(order);

        assertThatThrownBy(() -> OrderConfirmedEvent.from(order)).isInstanceOf(IllegalStateException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void rejectsAnItemWithoutAStoredProductIdentity() {
        Order order = order(List.of(new OrderItem(new Product(new Brand("브랜드"), "상품", 100, 5), 2)));
        order.preparePgPayment();
        order.confirmPgPayment(FIRST_TIME);

        assertThatThrownBy(() -> OrderConfirmedEvent.from(order)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingSourceOrder() {
        assertThatThrownBy(() -> OrderConfirmedEvent.from(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotTreatChangedQuantityOrConfirmationTimeAsTheSameFact() {
        OrderConfirmedEvent original = OrderConfirmedEvent.from(confirmedOrder());
        OrderConfirmedEvent changedQuantity = event(List.of(new OrderConfirmedEvent.Item(7, 3),
            new OrderConfirmedEvent.Item(8, 1)));
        OrderConfirmedEvent changedTime = new OrderConfirmedEvent("OrderConfirmed", 1, "order-confirmed:v1:42",
            42, STORED_TIME.plusSeconds(1), original.items());

        assertThat(original.eventId()).isEqualTo(changedQuantity.eventId()).isEqualTo(changedTime.eventId());
        assertThat(original).isNotEqualTo(changedQuantity).isNotEqualTo(changedTime);
    }

    private static Stream<Arguments> invalidMetadata() {
        return Stream.of(
            Arguments.of(null, 1, "order-confirmed:v1:42"),
            Arguments.of("OrderCancelled", 1, "order-confirmed:v1:42"),
            Arguments.of("OrderConfirmed", 0, "order-confirmed:v1:42"),
            Arguments.of("OrderConfirmed", 2, "order-confirmed:v1:42"),
            Arguments.of("OrderConfirmed", 1, null),
            Arguments.of("OrderConfirmed", 1, ""),
            Arguments.of("OrderConfirmed", 1, "order-confirmed:v1:43"),
            Arguments.of("OrderConfirmed", 1, "order-confirmed:v1:042")
        );
    }

    private static Stream<Arguments> invalidItems() {
        return Stream.of(Arguments.of((Object) null), Arguments.of(List.of()),
            Arguments.of(Arrays.asList(new OrderConfirmedEvent.Item(7, 2), null)));
    }

    private static Stream<Arguments> invalidItemValues() {
        return Stream.of(Arguments.of(0L, 1), Arguments.of(-1L, 1), Arguments.of(Long.MIN_VALUE, 1),
            Arguments.of(7L, 0), Arguments.of(7L, -1), Arguments.of(7L, Integer.MIN_VALUE));
    }

    private static Stream<Arguments> invalidStoredStates() {
        return Stream.of(
            Arguments.of("missing first confirmation time", (Consumer<Order>) order ->
                ReflectionTestUtils.setField(order, "confirmedAt", null)),
            Arguments.of("inconsistent paid amount", (Consumer<Order>) order ->
                ReflectionTestUtils.setField(order, "paidAmount", 1L)),
            Arguments.of("reservation not consumed", (Consumer<Order>) order ->
                ReflectionTestUtils.setField(order.getItems().getFirst(), "reservationStatus", OrderItemReservationStatus.HELD)),
            Arguments.of("invalid stored quantity", (Consumer<Order>) order ->
                ReflectionTestUtils.setField(order.getItems().getFirst(), "quantity", 0)),
            Arguments.of("missing order identity", (Consumer<Order>) order ->
                ReflectionTestUtils.setField(order, "id", null))
        );
    }

    private static OrderConfirmedEvent event(List<OrderConfirmedEvent.Item> items) {
        return new OrderConfirmedEvent("OrderConfirmed", 1, "order-confirmed:v1:42", 42, STORED_TIME, items);
    }

    private static Order confirmedOrder() {
        Order order = order(List.of(new OrderItem(product(8, "둘째 상품", 200), 1),
            new OrderItem(product(7, "첫 상품", 100), 2)));
        order.preparePgPayment();
        order.confirmPgPayment(FIRST_TIME);
        return order;
    }

    private static Order order(List<OrderItem> items) {
        Order order = Order.create(new User(1), items);
        ReflectionTestUtils.setField(order, "id", 42L);
        return order;
    }

    private static Product product(long id, String name, long price) {
        Product product = new Product(new Brand("브랜드"), name, price, 5);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }
}
