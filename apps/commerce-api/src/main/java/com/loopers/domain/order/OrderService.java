package com.loopers.domain.order;

import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointErrorCode;
import com.loopers.domain.point.PointService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductErrorCode;
import com.loopers.domain.product.ProductErrorDetail;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductService productService;
    private final PointService pointService;

    public record OrderRequestLine(Long productId, int quantity) {}

    /** 살아 있는 상품을 요청 순서대로 조회해 스냅샷을 찍고 DRAFT 로 저장한다 (ORD-01). 차감하지 않는다. */
    @Transactional
    public Order create(Long userId, List<OrderRequestLine> requestLines) {
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
        return orderRepository.save(Order.draft(userId, lines));
    }

    /**
     * 모두 확인한 뒤 모두 변경한다 (설계 5.3). 확인은 규칙마다 품목 순서대로 하며 처음 실패한 품목을 알린다.
     * 어떤 실패에서도 주문은 DRAFT 로, 재고와 잔액은 그대로 남는다 (5.4).
     */
    @Transactional
    public Order confirm(Long userId, Long orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
            .orElseThrow(() -> new CoreException(OrderErrorCode.ORDER_NOT_FOUND));
        if (!order.isDraft()) {
            throw new CoreException(OrderErrorCode.ORDER_ALREADY_CONFIRMED);
        }

        // 확인 단계: 상품 삭제(ORD-08) → 가격(ORD-09) → 재고(ORD-10) → 잔액(ORD-11)
        Map<Long, Product> products = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            products.put(item.getProductId(), productService.getActiveProduct(item.getProductId()));
        }
        for (OrderItem item : order.getItems()) {
            if (!item.isPriceMatched(products.get(item.getProductId()).getPrice())) {
                throw failureOf(OrderErrorCode.PRODUCT_PRICE_CHANGED, item);
            }
        }
        for (OrderItem item : order.getItems()) {
            if (!products.get(item.getProductId()).canDecrease(item.getQuantity())) {
                throw failureOf(ProductErrorCode.OUT_OF_STOCK, item);
            }
        }
        long paymentAmount = order.getTotalAmount();
        Point point = pointService.getPoint(userId);
        if (!point.canPay(paymentAmount)) {
            throw new CoreException(PointErrorCode.INSUFFICIENT_POINT);
        }

        // 변경 단계: 조건 판단은 각 객체가 한다. 충전한 적 없는 사용자의 0원 결제는 Point 행을 만들지 않는다 (D-30).
        for (OrderItem item : order.getItems()) {
            products.get(item.getProductId()).decrease(item.getQuantity());
        }
        point.pay(paymentAmount);
        order.confirm(paymentAmount, ZonedDateTime.now());
        return order;
    }

    /** 요청자 본인의 주문만 조회한다. 타인의 주문은 없는 주문과 같다 (설계 6.1). */
    @Transactional(readOnly = true)
    public Order getMyOrder(Long userId, Long orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
            .orElseThrow(() -> new CoreException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Order getOrder(Long orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new CoreException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    /** 품목을 읽는 요약은 트랜잭션 안에서 만든다 (open-in-view 가 꺼져 있다). */
    @Transactional(readOnly = true)
    public Page<OrderSummary> getOrderSummaries(Long userId, OrderStatus status, Pageable pageable) {
        return orderRepository.findPage(userId, status, pageable).map(OrderSummary::from);
    }

    private static CoreException failureOf(ErrorCode errorCode, OrderItem item) {
        return new CoreException(errorCode, null, ProductErrorDetail.of(item.getProductId()));
    }
}
