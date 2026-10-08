package com.loopers.application.order;

import com.loopers.application.user.IdentifyUser;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.point.PointBalanceRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;

@Component
@RequiredArgsConstructor
@Transactional
public class ConfirmOrderFacade {
    private final IdentifyUser users;
    private final OrderRepository orders;
    private final ProductRepository products;
    private final PointBalanceRepository points;

    public OrderInfo confirm(Long userId, long orderId) {
        long owner = users.require(userId);
        Order order = findOwnedOrder(owner, orderId);

        confirmOrderState(order);
        deductOrderStock(order);
        points.deduct(owner, order.getTotalAmount());

        return OrderInfo.from(order);
    }

    private Order findOwnedOrder(long owner, long orderId) {
        return orders.findById(orderId)
                .filter(order -> order.getUserId() == owner)
                .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
    }

    private void confirmOrderState(Order order) {
        order.confirm();
        // 같은 주문의 경쟁은 첫 상태 전이에서 걸러낸다. 뒤에서 실패하면 이 변경도 함께 롤백된다.
        orders.confirmIfDraft(order);
    }

    private void deductOrderStock(Order order) {
        for (OrderItem item :
                order.getItems().stream()
                        .sorted(Comparator.comparingLong(OrderItem::getProductId))
                        .toList()) {
            products.deductStock(item.getProductId(), item.getQuantity());
        }
    }
}
