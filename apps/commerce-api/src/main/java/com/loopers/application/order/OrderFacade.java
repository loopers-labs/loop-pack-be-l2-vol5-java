package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderLine;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.PointService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 주문 유스케이스. 주문 · 상품 · 포인트 여러 애그리거트의 순서와 트랜잭션을 여기서 정함 (설계 4.4, D-11).
 * 규칙 검사와 오류 코드는 각 객체가 맡고, 여기서는 순서만 정함
 */
@RequiredArgsConstructor
@Component
public class OrderFacade {
    private final OrderService orderService;
    private final ProductService productService;
    private final PointService pointService;

    public record OrderRequestLine(Long productId, int quantity) {}

    /** 살아 있는 상품을 요청 순서대로 조회해 스냅샷을 찍고 DRAFT 로 저장함 (ORD-01, 설계 5.2). 차감하지 않음 */
    @Transactional
    public OrderInfo createOrder(Long userId, List<OrderRequestLine> requestLines) {
        Map<Long, Product> products = new LinkedHashMap<>();
        for (OrderRequestLine line : requestLines) {
            products.computeIfAbsent(line.productId(), productService::getActiveProduct);
        }
        List<OrderLine> lines = requestLines.stream()
            .map(line -> {
                Product product = products.get(line.productId());
                return new OrderLine(product.getId(), product.getName(), product.getPrice(), line.quantity());
            })
            .toList();
        return OrderInfo.from(orderService.create(userId, lines));
    }

    public Page<OrderSummaryInfo> getMyOrders(Long userId, OrderStatus status, Pageable pageable) {
        return orderService.getOrderSummaries(userId, status, pageable).map(OrderSummaryInfo::from);
    }

    /** 판매 여부는 업무 규칙이 아니라 응답 구성 값이라 여기서 조합함. 품목의 상품들을 한 번에 물음 (설계 6.4, D-42) */
    @Transactional(readOnly = true)
    public OrderDetailInfo getMyOrder(Long userId, Long orderId) {
        Order order = orderService.getMyOrder(userId, orderId);
        List<Long> productIds = order.getItems().stream().map(OrderItem::getProductId).toList();
        return OrderDetailInfo.of(order, productService.getActiveProductIds(productIds));
    }

    /**
     * 주문 → 상품 → 포인트 순으로 각 객체의 행동을 부르고, 규칙을 어기면 그 객체가 예외를 던짐 (설계 5.3).
     * 처음 실패에서 멈추며, 앞서 바뀐 재고 · 잔액은 트랜잭션 롤백으로 DB 에 반영되지 않음 (5.4).
     * 결제액은 주문서 합계이며 현재 가격과 비교하지 않음 (설계 2.3)
     */
    @Transactional
    public OrderInfo confirmOrder(Long userId, Long orderId) {
        Order order = orderService.getConfirmableOrder(userId, orderId);

        // 품목의 상품들을 식별자 오름차순으로 한 번에 잠근 뒤(3주차 설계 4.3), 삭제된 상품(ORD-09)을 모두 확인하고 차감(ORD-10)함.
        // 실패한 상품은 품목 순서대로 처음 것
        List<Long> productIds = order.getItems().stream().map(OrderItem::getProductId).toList();
        Map<Long, Product> products = productService.getActiveProductsForUpdate(productIds);
        for (OrderItem item : order.getItems()) {
            products.get(item.getProductId()).decrease(item.getQuantity());
        }
        // 충전한 적 없는 사용자의 0원 결제는 Point 행을 만들지 않음 (D-30)
        long paymentAmount = order.getTotalAmount();
        pointService.getPoint(userId).pay(paymentAmount);
        return OrderInfo.from(orderService.confirm(order, paymentAmount));
    }
}
