package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.application.user.UserValidator;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class OrderFacade {
    private final OrderRepository orderRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final ProductRepository productRepository;
    private final UserValidator userValidator;

    @Transactional
    public OrderInfo create(Long userId, List<OrderRequestItem> requestItems) {
        if (userId == null || requestItems == null || requestItems.isEmpty()
            || requestItems.stream().anyMatch(item -> item == null || item.productId() == null)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 정보가 올바르지 않습니다.");
        }
        userValidator.validateExists(userId);
        List<OrderItem> items = requestItems.stream().map(this::toOrderItem).toList();
        return OrderInfo.from(orderRepository.save(Order.create(userId, items)), null);
    }

    @Transactional(readOnly = true)
    public List<OrderInfo> getMyOrders(Long userId) {
        userValidator.validateExists(userId);
        return withPayments(orderRepository.findAllByUserId(userId));
    }

    @Transactional(readOnly = true)
    public OrderInfo getMyOrder(Long userId, Long orderId) {
        userValidator.validateExists(userId);
        Order order = orderRepository.findById(orderId)
            .filter(found -> found.getUserId().equals(userId))
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
        return withPayment(order);
    }

    @Transactional(readOnly = true)
    public List<OrderInfo> getAllOrders() {
        return withPayments(orderRepository.findAll());
    }

    @Transactional(readOnly = true)
    public OrderInfo getOrder(Long orderId) {
        return orderRepository.findById(orderId).map(this::withPayment)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
    }

    // 주문 조회는 저장된 결제 상태만 읽는다. PG를 조회하거나 결제 요청을 만들지 않는다.
    private OrderInfo withPayment(Order order) {
        return OrderInfo.from(order, paymentAttemptRepository.findByOrderId(order.getId()).orElse(null));
    }

    // 목록은 주문마다 시도를 따로 읽지 않고 한 번에 읽는다.
    private List<OrderInfo> withPayments(List<Order> orders) {
        Map<Long, PaymentAttempt> attempts = paymentAttemptRepository
            .findAllByOrderIds(orders.stream().map(Order::getId).toList()).stream()
            .collect(Collectors.toMap(PaymentAttempt::getOrderId, Function.identity()));
        return orders.stream().map(order -> OrderInfo.from(order, attempts.get(order.getId()))).toList();
    }

    private OrderItem toOrderItem(OrderRequestItem item) {
        Product product = productRepository.findById(item.productId())
            .filter(found -> found.getDeletedAt() == null)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
        return OrderItem.create(product.getId(), product.getName(), product.getPrice(), item.quantity());
    }

    public record OrderRequestItem(Long productId, int quantity) {}
}
