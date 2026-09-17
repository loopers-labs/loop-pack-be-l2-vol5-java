package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
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

    @Transactional
    public OrderInfo create(Long userId, List<OrderRequestItem> requestItems) {
        if (userId == null || requestItems == null || requestItems.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 정보가 올바르지 않습니다.");
        }
        List<OrderItem> items = requestItems.stream().map(this::toOrderItem).toList();
        return OrderInfo.from(orderRepository.save(Order.create(userId, items)));
    }

    private OrderItem toOrderItem(OrderRequestItem item) {
        Product product = productRepository.findById(item.productId())
            .filter(found -> found.getDeletedAt() == null)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
        return new OrderItem(product.getId(), product.getName(), product.getPrice(), item.quantity());
    }

    public record OrderRequestItem(Long productId, int quantity) {}
}
