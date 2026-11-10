package com.loopers.domains.order.app;

import com.loopers.domains.order.app.port.OrderConfirmedOutbox;
import com.loopers.domains.order.app.port.OrderConfirmedPayload;
import com.loopers.domains.order.app.port.OrderRepository;
import com.loopers.domains.order.domain.Order;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 6주차 누락 복구(D121). 확정은 됐는데 Outbox 행이 없는 주문에, 최초 확정과 같은 팩토리로 만든 같은 eventId 행을 PENDING으로 채운다.
// 이미 있으면 확인만 하고, 다르면 덮어쓰지 않고 드러낸다. 확정 사실이 부족하면 추측해 채우지 않는다.
// PG를 부르지 않고 주문·예약·결제 기록을 바꾸지 않는다. 보내는 일은 relay의 다음 회차가 한다. 주문마다 짧은 트랜잭션 하나다.
@Service
public class OrderOutboxBackfill {
    public enum Result { ADDED, ALREADY_SAME, PAYLOAD_MISMATCH, NOT_CONFIRMED, MISSING_FACT, ORDER_NOT_FOUND }

    public record Outcome(long orderId, Result result) { }

    private final OrderRepository orderRepository;
    private final OrderConfirmedOutbox orderConfirmedOutbox;
    private final TransactionTemplate transactionTemplate;

    public OrderOutboxBackfill(OrderRepository orderRepository, OrderConfirmedOutbox orderConfirmedOutbox,
        TransactionTemplate transactionTemplate) {
        this.orderRepository = orderRepository;
        this.orderConfirmedOutbox = orderConfirmedOutbox;
        this.transactionTemplate = transactionTemplate;
    }

    public List<Outcome> ensure(List<Long> orderIds) {
        return orderIds.stream()
            .map(orderId -> new Outcome(orderId, transactionTemplate.execute(status -> ensureOne(orderId))))
            .toList();
    }

    private Result ensureOne(long orderId) {
        Optional<Order> found = orderRepository.findById(orderId);
        if (found.isEmpty()) {
            return Result.ORDER_NOT_FOUND;
        }
        Order order = found.get();
        if (order.getStatus() != Order.Status.CONFIRMED) {
            return Result.NOT_CONFIRMED;
        }
        if (order.getConfirmedAt() == null || order.getItems().isEmpty()) {
            return Result.MISSING_FACT;
        }
        OrderConfirmedPayload payload = OrderConfirmedPayloadFactory.create(order);
        return switch (orderConfirmedOutbox.compare(payload)) {
            case ABSENT -> {
                orderConfirmedOutbox.append(payload);
                yield Result.ADDED;
            }
            case SAME -> Result.ALREADY_SAME;
            case DIFFERENT -> Result.PAYLOAD_MISMATCH;
        };
    }
}
