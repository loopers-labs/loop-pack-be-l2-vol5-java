package com.loopers.domain.order;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.domain.point.PointUsage;
import com.loopers.domain.product.StockDeduction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;

@Service
@RequiredArgsConstructor
public class OrderConfirmation {

    private final OrderRepository orderRepository;
    private final StockDeduction stockDeduction;
    private final PointUsage pointUsage;

    public Order confirm(Long userId, Long orderId, Instant now) {
        Order order = orderRepository.findByIdForUpdate(orderId)
            .filter(found -> found.isOwnedBy(userId))
            .orElseThrow(() -> new DomainException(DomainError.ORDER_NOT_FOUND));

        order.confirm(now);
        Order confirmed = orderRepository.save(order);

        confirmed.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::productId))
            .forEach(item -> stockDeduction.deductStock(item.productId(), item.quantity().toQuantity()));

        pointUsage.use(userId, confirmed.getPaidAmount(), now);

        return confirmed;
    }
}
