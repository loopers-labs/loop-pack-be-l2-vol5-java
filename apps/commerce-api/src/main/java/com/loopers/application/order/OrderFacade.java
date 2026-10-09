package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.point.PointService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@RequiredArgsConstructor
@Component
public class OrderFacade {

    private final UserRepository userRepository;
    private final ProductService productService;
    private final PointService pointService;
    private final OrderService orderService;

    /**
     * 생성 시점에는 재고를 차감하지 않고, 상품의 현재 가격을 단가로 기록한다.
     */
    @Transactional
    public OrderInfo createOrder(Long userId, List<OrderCommand.Item> items) {
        requireIdentifiedUser(userId);

        List<OrderItem> orderItems = items.stream()
            .map(this::toOrderItem)
            .toList();

        return OrderInfo.from(orderService.createOrder(userId, orderItems));
    }

    /**
     * 주문 확정 → 포인트 차감 → 상품 ID 오름차순 재고 차감 순서로 조건부 UPDATE 한다.
     * 어느 단계든 영향 행이 0 이면 CoreException 으로 전체가 롤백되어 주문은 DRAFT 로 남는다.
     * 응답은 갱신 뒤 다시 읽은 주문으로 만든다.
     */
    @Transactional
    public OrderInfo confirmOrder(Long requesterId, Long orderId) {
        Order order = findOwnOrder(requesterId, orderId);

        Order confirmed = orderService.confirmOrder(order);
        pointService.deduct(requesterId, order.getTotalAmount());
        order.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::getProductId))
            .forEach(item -> productService.deductStock(item.getProductId(), item.getQuantity()));

        return OrderInfo.from(confirmed);
    }

    @Transactional(readOnly = true)
    public List<OrderInfo> getOrders(Long userId) {
        requireIdentifiedUser(userId);

        return orderService.getOrders(userId).stream()
            .map(OrderInfo::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public OrderInfo getOrder(Long requesterId, Long orderId) {
        return OrderInfo.from(findOwnOrder(requesterId, orderId));
    }

    /**
     * 관리자 조회. ROLE_ADMIN 검사가 접근 경계이므로 소유권을 확인하지 않는다.
     */
    @Transactional(readOnly = true)
    public List<OrderInfo> getOrdersForAdmin(Long userId, int page, int size) {
        return orderService.getOrdersForAdmin(userId, page, size).stream()
            .map(OrderInfo::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public OrderInfo getOrderForAdmin(Long orderId) {
        return OrderInfo.from(orderService.getOrder(orderId));
    }

    private OrderItem toOrderItem(OrderCommand.Item item) {
        Product product = productService.getActiveProduct(item.productId());
        return new OrderItem(item.productId(), item.quantity(), product.getPrice().getAmount());
    }

    /**
     * 남의 주문은 존재를 알리지 않고 없는 것으로 응답한다.
     */
    private Order findOwnOrder(Long requesterId, Long orderId) {
        requireIdentifiedUser(requesterId);

        Order order = orderService.getOrder(orderId);
        if (!order.isOwnedBy(requesterId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "[id = " + orderId + "] 주문을 찾을 수 없습니다.");
        }
        return order;
    }

    private void requireIdentifiedUser(Long userId) {
        if (userId == null || !userRepository.existsById(userId)) {
            throw new CoreException(ErrorType.UNAUTHORIZED, "사용자 식별에 실패했습니다.");
        }
    }
}
