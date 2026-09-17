package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class OrderFacade {
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PointRepository pointRepository;

    @Transactional
    public OrderInfo create(Long userId, List<OrderRequestItem> requestItems) {
        if (userId == null || requestItems == null || requestItems.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 정보가 올바르지 않습니다.");
        }
        List<OrderItem> items = requestItems.stream().map(this::toOrderItem).toList();
        return OrderInfo.from(orderRepository.save(Order.create(userId, items)));
    }

    @Transactional
    public OrderInfo confirm(Long userId, Long orderId) {
        Order order = orderRepository.findById(orderId)
            .filter(found -> found.getUserId().equals(userId))
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
        Point point = pointRepository.findByUserId(userId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "사용자의 포인트를 찾을 수 없습니다."));
        order.getItems().forEach(item -> productRepository.findById(item.getProductId())
            .filter(product -> product.getDeletedAt() == null)
            .orElseThrow(() -> new CoreException(ErrorType.CONFLICT, "주문 상품을 확정할 수 없습니다."))
            .decreaseStock(item.getQuantity()));
        point.pay(order.getTotalAmount());
        order.confirm();
        pointRepository.save(point);
        return OrderInfo.from(orderRepository.save(order));
    }

    private OrderItem toOrderItem(OrderRequestItem item) {
        Product product = productRepository.findById(item.productId())
            .filter(found -> found.getDeletedAt() == null)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
        return new OrderItem(product.getId(), product.getName(), product.getPrice(), item.quantity());
    }

    public record OrderRequestItem(Long productId, int quantity) {}
}
