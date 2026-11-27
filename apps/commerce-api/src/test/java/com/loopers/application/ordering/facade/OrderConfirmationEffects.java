package com.loopers.application.ordering.facade;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.ordering.event.OrderConfirmedEvent;
import com.loopers.domain.mall.model.StockAdjustment;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.mall.repository.StockAdjustmentRepository;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderRecord;
import com.loopers.domain.ordering.model.OrderRecordStatus;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.model.PaymentMethod;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.infrastructure.persistence.ordering.entity.QOrderOutboxJpaEntity;
import com.loopers.support.test.OrderConfirmProbe;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

record OrderConfirmationEffects(
    long orderId, OrderStatus orderStatus,
    long paidRecordCount, long paidRecordId, OrderRecordStatus paymentStatus, PaymentMethod paymentMethod,
    long paidAmount, UUID confirmationEventId, Instant confirmedAt, List<UUID> outboxEventIds,
    int remainingStock, StockAdjustment.State stockState, long stockVersion, List<StockAdjustment.Item> stockItems
) {
    void assertConfirmed(long productId, int expectedStock, long expectedAmount) {
        assertThat(orderStatus).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(paidRecordCount).isOne();
        assertThat(paidRecordId).isPositive();
        assertThat(paymentStatus).isEqualTo(OrderRecordStatus.PAID);
        assertThat(paymentMethod).isEqualTo(PaymentMethod.PG);
        assertThat(paidAmount).isEqualTo(expectedAmount);
        assertThat(confirmedAt).isNotNull();
        assertThat(confirmationEventId).isNotNull();
        assertThat(outboxEventIds).containsExactly(confirmationEventId);
        assertThat(remainingStock).isEqualTo(expectedStock);
        assertThat(stockState).isEqualTo(StockAdjustment.State.CONSUMED);
        assertThat(stockItems).containsExactly(new StockAdjustment.Item(productId, 1));
    }

    @TestComponent
    static class Reader {
        @Autowired private OrderRepository orders;
        @Autowired private OrderConfirmProbe probe;
        @Autowired private StockAdjustmentRepository adjustments;
        @Autowired private ProductRepository products;
        @Autowired private JPAQueryFactory queryFactory;

        @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
        public OrderConfirmationEffects read(long orderId, long productId) {
            Order order = orders.findById(orderId).orElseThrow();
            OrderRecord paid = order.getRecord().orElseThrow();
            StockAdjustment adjustment = adjustments.findByOrderId(orderId).orElseThrow();
            int stock = products.findById(productId).orElseThrow().getStock();
            QOrderOutboxJpaEntity outbox = QOrderOutboxJpaEntity.orderOutboxJpaEntity;
            List<UUID> eventIds = queryFactory.select(outbox.eventId).from(outbox)
                .where(outbox.orderId.eq(orderId), outbox.eventType.eq(OrderConfirmedEvent.TYPE))
                .orderBy(outbox.eventId.asc()).fetch().stream().map(UUID::fromString).toList();
            return new OrderConfirmationEffects(order.getId(), order.getStatus(),
                probe.countPaidOrderRecords(orderId), paid.getId(), paid.getStatus(), paid.getPaymentMethod(),
                paid.getAmount(), paid.getConfirmationEventId(), paid.getCreatedAt(), eventIds,
                stock, adjustment.getState(), adjustment.getVersion(), adjustment.getItems());
        }
    }
}
