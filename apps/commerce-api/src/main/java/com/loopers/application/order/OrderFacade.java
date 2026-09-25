package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointRepository;
import com.loopers.application.user.UserValidator;
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
    private final UserValidator userValidator;

    @Transactional
    public OrderInfo create(Long userId, List<OrderRequestItem> requestItems) {
        if (userId == null || requestItems == null || requestItems.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 정보가 올바르지 않습니다.");
        }
        userValidator.validateExists(userId);
        List<OrderItem> items = requestItems.stream().map(this::toOrderItem).toList();
        return OrderInfo.from(orderRepository.save(Order.create(userId, items)));
    }

    @Transactional
    public OrderInfo confirm(Long userId, Long orderId) {
        userValidator.validateExists(userId);
        Order order = orderRepository.findById(orderId)
            .filter(found -> found.getUserId().equals(userId))
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
        order.validateDraft();
        Point point = pointRepository.findByUserId(userId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "사용자의 포인트를 찾을 수 없습니다."));
        order.getItems().forEach(item -> {
            Product product = productRepository.findById(item.getProductId())
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new CoreException(ErrorType.CONFLICT, "주문 상품을 확정할 수 없습니다."));
            product.decreaseStock(item.getQuantity());
            productRepository.save(product);
        });
        point.pay(order.getTotalAmount());
        order.confirm(order.getTotalAmount());
        pointRepository.save(point);
        return OrderInfo.from(orderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public List<OrderInfo> getMyOrders(Long userId) {
        userValidator.validateExists(userId);
        return orderRepository.findAllByUserId(userId).stream().map(OrderInfo::from).toList();
    }

    @Transactional(readOnly = true)
    public OrderInfo getMyOrder(Long userId, Long orderId) {
        userValidator.validateExists(userId);
        Order order = orderRepository.findById(orderId)
            .filter(found -> found.getUserId().equals(userId))
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
        return OrderInfo.from(order);
    }

    @Transactional(readOnly = true)
    public List<OrderInfo> getAllOrders() {
        return orderRepository.findAll().stream().map(OrderInfo::from).toList();
    }

    @Transactional(readOnly = true)
    public OrderInfo getOrder(Long orderId) {
        return orderRepository.findById(orderId).map(OrderInfo::from)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
    }

    private OrderItem toOrderItem(OrderRequestItem item) {
        Product product = productRepository.findById(item.productId())
            .filter(found -> found.getDeletedAt() == null)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
        return OrderItem.create(product.getId(), product.getName(), product.getPrice(), item.quantity());
    }

    public record OrderRequestItem(Long productId, int quantity) {}
}
