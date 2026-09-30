package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 고객 전용 — 생성·확정·내 목록/상세. 관리자 조회(전체 목록/상세)는 OrderAdminFacade로 분리한다.
// Order는 Brand/Product와 달리 고객도 변경(생성·확정)을 하므로 "조회=공유, 변경=분리" 기준을
// 그대로 재사용할 수 없다 (docs/week2/design.md 1번 섹션·3번 섹션 "Facade 경계" 참고).
@RequiredArgsConstructor
@Component
public class OrderFacade {

    private final OrderService orderService;
    private final ProductService productService;
    private final UserService userService;

    /**
     * 요청 품목마다 단가를 스냅샷으로 찍어(DRAFT 생성 시점, 3번 섹션) Order에 넘긴다.
     * 품목 병합·빈 목록 거절은 Order 생성자의 책임이라 여기서 다시 검사하지 않는다.
     */
    @Transactional
    public OrderInfo createOrder(Long userId, List<OrderItemRequest> requestItems) {
        List<OrderItemRequest> items = requestItems == null ? List.of() : requestItems;

        List<Long> distinctProductIds = items.stream()
            .map(OrderItemRequest::productId)
            .distinct()
            .toList();
        Map<Long, ProductModel> productsById = productService.getProductsByIds(distinctProductIds).stream()
            .collect(Collectors.toMap(ProductModel::getId, product -> product));

        List<Order.OrderItemDraft> drafts = items.stream()
            .map(item -> toDraft(item, productsById))
            .toList();

        Order order = orderService.createOrder(userId, drafts);
        return OrderInfo.from(order);
    }

    private Order.OrderItemDraft toDraft(OrderItemRequest item, Map<Long, ProductModel> productsById) {
        ProductModel product = productsById.get(item.productId());
        if (product == null) {
            throw new CoreException(ErrorType.NOT_FOUND, "[id = " + item.productId() + "] 상품을 찾을 수 없습니다.");
        }
        return new Order.OrderItemDraft(item.productId(), item.quantity(), product.getPrice());
    }

    /**
     * 확정: 소유자·상태 확인(Order 락) → 재고 차감(Product 락, productId 오름차순) → 포인트 차감(User 락)
     * → 상태 전이. 락 순서는 Order → Product(오름차순) → User로 고정한다 — 데드락 방지 규칙과
     * 일관되고, 같은 주문에 확정 요청이 겹쳐도(더블클릭·재시도) 두 번 반영되지 않는다.
     * (docs/week2/design.md 5번 섹션 "Order 자신의 DRAFT→CONFIRMED 전이도 같은 방식으로 보호한다" 참고)
     * 하나라도 실패하면 트랜잭션 전체가 롤백된다 (docs/week2/design.md 4번 섹션 시퀀스 다이어그램).
     */
    @Transactional
    public OrderInfo confirmOrder(Long orderId, Long requesterId) {
        Order order = orderService.getOwnedOrderForUpdate(orderId, requesterId);
        order.assertDraft();

        order.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::getProductId))
            .forEach(item -> productService.decreaseStock(item.getProductId(), item.getQuantity()));

        long totalAmount = order.calculateTotalAmount();
        userService.payPoint(requesterId, totalAmount);

        order.confirm(totalAmount);
        Order confirmed = orderService.save(order);
        return OrderInfo.from(confirmed);
    }

    public List<OrderInfo> getMyOrders(Long requesterId) {
        return orderService.getMyOrders(requesterId).stream()
            .map(OrderInfo::from)
            .toList();
    }

    public OrderInfo getMyOrder(Long orderId, Long requesterId) {
        return OrderInfo.from(orderService.getOwnedOrder(orderId, requesterId));
    }
}
