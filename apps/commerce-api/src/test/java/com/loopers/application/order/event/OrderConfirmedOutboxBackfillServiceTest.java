package com.loopers.application.order.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.time.ApplicationTime;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.order.PaymentResult;
import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderConfirmedOutboxBackfillServiceTest {

    @DisplayName("원본 품목이 없으면 Outbox를 조회·생성하기 전에 복구를 거절한다.")
    @Test
    void rejectsMissingSourceItemsBeforeRecordingOutbox() {
        OrderRepository orders = mock(OrderRepository.class);
        OrderConfirmedOutboxStore store = mock(OrderConfirmedOutboxStore.class);
        Order incomplete = Order.reconstitute(42L, 1L, OrderStatus.CONFIRMED, 400L, 400L,
            PaymentResult.SUCCESS, PaymentMethod.PG, ZonedDateTime.parse("2026-11-05T00:00:00Z"), List.of());
        when(orders.findById(42L)).thenReturn(Optional.of(incomplete));
        OrderConfirmedOutboxWriter writer = new OrderConfirmedOutboxWriter(
            new ObjectMapper(), store, new ApplicationTime(Clock.fixed(incomplete.getConfirmedAt().toInstant(), ZoneOffset.UTC)));
        OrderConfirmedOutboxBackfillService service = new OrderConfirmedOutboxBackfillService(orders, writer);

        assertThatThrownBy(() -> service.backfill(42L)).isInstanceOf(CoreException.class);

        verifyNoInteractions(store);
    }
}
