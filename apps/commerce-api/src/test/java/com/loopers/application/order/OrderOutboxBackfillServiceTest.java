package com.loopers.application.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.outbox.OutboxMessage;
import com.loopers.domain.outbox.OutboxMessageMatcher;
import com.loopers.domain.outbox.OutboxMessageSerializer;
import com.loopers.domain.outbox.OutboxRepository;
import com.loopers.domain.outbox.OutboxStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Pure orchestration boundaries; database atomicity and actual W6 preparation belong to separate integration tests. */
class OrderOutboxBackfillServiceTest {
    private static final long ORDER_ID = 42;
    private static final String EVENT_ID = "order-confirmed:v1:42";
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-29T03:04:05.123456Z");
    private static final Instant BACKFILLED_AT = CONFIRMED_AT.plusSeconds(86_400);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
    private final OutboxMessageSerializer serializer = mock(OutboxMessageSerializer.class);
    private final OutboxMessageMatcher matcher = mock(OutboxMessageMatcher.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final OrderOutboxBackfillService service = new OrderOutboxBackfillService(orders, attempts, serializer, matcher, outbox);

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveIdentityWithoutAccessingStorage(long orderId) {
        assertThatThrownBy(() -> service.backfill(orderId)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(orders, attempts, serializer, matcher, outbox);
    }

    @Test
    void missingOrderCannotBeReconstructedFromAnEventIdentityAlone() {
        when(orders.lockById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.backfill(ORDER_ID)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(attempts, serializer, matcher, outbox);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "POINT"})
    void refusesUnconfirmedAndHistoricalPointOrdersWithoutChangingTheirFacts(String source) {
        Order order = draft();
        if (source.equals("POINT")) {
            order.confirm(at(CONFIRMED_AT));
        }
        OrderInfo before = OrderInfo.from(order);
        when(orders.lockById(ORDER_ID)).thenReturn(Optional.of(order));
        when(attempts.lockByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.backfill(ORDER_ID)).isInstanceOf(IllegalStateException.class);

        assertThat(OrderInfo.from(order)).isEqualTo(before);
        verifyNoInteractions(serializer, matcher, outbox);
        verify(orders, never()).save(any());
        verify(attempts, never()).save(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompletePaymentFacts")
    void refusesIncompleteOrConflictingPaymentEvidence(String description, Consumer<PaymentAttempt> corrupt) {
        Order order = confirmed();
        PaymentAttempt attempt = successfulAttempt();
        if (corrupt != null) {
            corrupt.accept(attempt);
        }
        when(orders.lockById(ORDER_ID)).thenReturn(Optional.of(order));
        when(attempts.lockByOrderId(ORDER_ID)).thenReturn(corrupt == null ? Optional.empty() : Optional.of(attempt));
        OrderInfo before = OrderInfo.from(order);

        assertThatThrownBy(() -> service.backfill(ORDER_ID)).isInstanceOf(IllegalStateException.class);

        assertThat(OrderInfo.from(order)).isEqualTo(before);
        verifyNoInteractions(serializer, matcher, outbox);
        verify(orders, never()).save(any());
        verify(attempts, never()).save(any());
    }

    @Test
    void createsOnlyTheMissingDeliveryFactFromTheOriginalConfirmationWithoutNeedingCardOrFingerprint() {
        Order order = confirmed();
        PaymentAttempt attempt = successfulAttempt();
        OrderConfirmedEvent event = OrderConfirmedEvent.from(order);
        OutboxMessage candidate = message(BACKFILLED_AT);
        ReflectionTestUtils.setField(candidate, "id", 77L);
        ready(order, attempt, candidate);
        when(outbox.findByEventId(EVENT_ID)).thenReturn(Optional.empty());
        when(outbox.save(candidate)).thenReturn(candidate);
        OrderPaymentInfo before = OrderPaymentInfo.from(order, attempt);

        var result = service.backfill(ORDER_ID);

        assertThat(result).isEqualTo(new OrderOutboxBackfillService.Result(EVENT_ID, 77,
            OrderOutboxBackfillService.Outcome.CREATED));
        var sequence = inOrder(orders, attempts);
        sequence.verify(orders).lockById(ORDER_ID);
        sequence.verify(attempts).lockByOrderId(ORDER_ID);
        verify(serializer).serialize(event);
        verify(outbox).save(candidate);
        verifyNoInteractions(matcher);
        verify(orders, never()).save(any());
        verify(attempts, never()).save(any());
        assertThat(attempt.getRequestFingerprint()).isEmpty();
        assertThat(OrderPaymentInfo.from(order, attempt)).isEqualTo(before);
        assertThat(event.confirmedAt()).isEqualTo(CONFIRMED_AT);
        assertThat(candidate.getCreatedAt()).isEqualTo(BACKFILLED_AT);
        assertThat(candidate.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @ParameterizedTest
    @EnumSource(OutboxStatus.class)
    void existingMatchingMessagePreservesAllDeliveryColumnsAndNeverSavesAgain(OutboxStatus status) {
        OutboxMessage candidate = message(BACKFILLED_AT);
        OutboxMessage existing = message(CONFIRMED_AT);
        ReflectionTestUtils.setField(existing, "id", 78L);
        existing.beginAttempt(CONFIRMED_AT, CONFIRMED_AT.plusSeconds(17));
        if (status == OutboxStatus.PUBLISHED) {
            existing.markPublished(1, CONFIRMED_AT.plusSeconds(1));
        } else {
            existing.markFailed(1, CONFIRMED_AT.plusSeconds(5), "TIMEOUT");
        }
        Object[] before = allFields(existing);
        ready(confirmed(), successfulAttempt(), candidate);
        when(outbox.findByEventId(EVENT_ID)).thenReturn(Optional.of(existing));
        when(matcher.matches(candidate, existing)).thenReturn(true);

        var result = service.backfill(ORDER_ID);

        assertThat(result).isEqualTo(new OrderOutboxBackfillService.Result(EVENT_ID, 78,
            OrderOutboxBackfillService.Outcome.ALREADY_PRESENT));
        assertThat(allFields(existing)).containsExactly(before);
        verify(outbox, never()).save(any());
        verify(orders, never()).save(any());
        verify(attempts, never()).save(any());
    }

    @Test
    void conflictingStoredMessageIsReportedWithoutOverwritingEitherFact() {
        OutboxMessage candidate = message(BACKFILLED_AT);
        OutboxMessage existing = message(CONFIRMED_AT);
        ReflectionTestUtils.setField(existing, "id", 79L);
        Object[] before = allFields(existing);
        ready(confirmed(), successfulAttempt(), candidate);
        when(outbox.findByEventId(EVENT_ID)).thenReturn(Optional.of(existing));
        when(matcher.matches(candidate, existing)).thenReturn(false);

        assertThatThrownBy(() -> service.backfill(ORDER_ID)).isInstanceOf(IllegalStateException.class);

        assertThat(allFields(existing)).containsExactly(before);
        verify(outbox, never()).save(any());
        verify(orders, never()).save(any());
        verify(attempts, never()).save(any());
    }

    @Test
    void serializationFailureDoesNotInventAFallbackPayloadOrWriteBusinessState() {
        Order order = confirmed();
        PaymentAttempt attempt = successfulAttempt();
        when(orders.lockById(ORDER_ID)).thenReturn(Optional.of(order));
        when(attempts.lockByOrderId(ORDER_ID)).thenReturn(Optional.of(attempt));
        var failure = new IllegalStateException("test serialization failure");
        when(serializer.serialize(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.backfill(ORDER_ID)).isSameAs(failure);

        verify(outbox, never()).save(any());
        verifyNoInteractions(matcher);
        verify(orders, never()).save(any());
        verify(attempts, never()).save(any());
    }

    private void ready(Order order, PaymentAttempt attempt, OutboxMessage candidate) {
        when(orders.lockById(ORDER_ID)).thenReturn(Optional.of(order));
        when(attempts.lockByOrderId(ORDER_ID)).thenReturn(Optional.of(attempt));
        when(serializer.serialize(OrderConfirmedEvent.from(order))).thenReturn(candidate);
    }

    private static Stream<Arguments> incompletePaymentFacts() {
        return Stream.of(
            Arguments.of("missing payment attempt", (Consumer<PaymentAttempt>) null),
            Arguments.of("payment not successful", (Consumer<PaymentAttempt>) attempt ->
                ReflectionTestUtils.setField(attempt, "status", PaymentAttemptStatus.PENDING)),
            Arguments.of("payment amount differs", (Consumer<PaymentAttempt>) attempt ->
                ReflectionTestUtils.setField(attempt, "amount", 401L)),
            Arguments.of("missing provider transaction", (Consumer<PaymentAttempt>) attempt ->
                ReflectionTestUtils.setField(attempt, "transactionKey", null)),
            Arguments.of("blank provider transaction", (Consumer<PaymentAttempt>) attempt ->
                ReflectionTestUtils.setField(attempt, "transactionKey", " "))
        );
    }

    private static Order confirmed() {
        Order order = draft();
        order.preparePgPayment();
        order.confirmPgPayment(at(CONFIRMED_AT));
        return order;
    }

    private static Order draft() {
        Product product = new Product(new Brand("브랜드"), "상품", 200, 10);
        ReflectionTestUtils.setField(product, "id", 7L);
        Order order = Order.create(new User(1), List.of(new OrderItem(product, 2)));
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        return order;
    }

    private static PaymentAttempt successfulAttempt() {
        PaymentAttempt attempt = new PaymentAttempt(ORDER_ID, UUID.randomUUID(), "135135", "LP-ORD-0000000042", 400);
        attempt.startDispatch(at(CONFIRMED_AT.minusSeconds(1)));
        attempt.observe(new PaymentTransaction("135135", "LP-ORD-0000000042", "TEST-TX-42", 400, PaymentTransaction.Status.SUCCESS));
        return attempt;
    }

    private static OutboxMessage message(Instant createdAt) {
        return OutboxMessage.pending(EVENT_ID, "Order", "42", "OrderConfirmed", 1, "order-events", "42",
            "{\"eventId\":\"order-confirmed:v1:42\"}", createdAt);
    }

    private static Object[] allFields(OutboxMessage message) {
        return new Object[] {message.getId(), message.getEventId(), message.getAggregateType(), message.getAggregateId(),
            message.getEventType(), message.getSchemaVersion(), message.getTopic(), message.getMessageKey(), message.getPayload(),
            message.getStatus(), message.getCreatedAt(), message.getPublishedAt(), message.getAttemptCount(),
            message.getNextAttemptAt(), message.getLastError()};
    }

    private static ZonedDateTime at(Instant instant) {
        return instant.atZone(ZoneOffset.UTC);
    }
}
