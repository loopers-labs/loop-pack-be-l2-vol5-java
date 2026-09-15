package com.loopers.application.order;

import com.loopers.domain.order.OrderLines;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSnapshot;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 고객 주문 유스케이스. 주문 확정은 포인트 지갑 판단의 대표 TDD(W-5·W-6) 이후에 추가한다.
 */
@RequiredArgsConstructor
@Component
public class OrderFacade {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    /**
     * ORD-01: 입력 검증(400) → 상품이 팔 수 있는지(404) → 주문 당시 이름·단가를 복사해 DRAFT로 저장. 재고·포인트는 차감하지 않는다.
     */
    @Transactional
    public OrderInfo create(Long userId, List<OrderLines.Line> requestedLines) {
        OrderLines lines = OrderLines.of(requestedLines);
        Map<Long, ProductSnapshot> snapshots = productRepository.findAllByIds(lines.productIds()).stream()
            .filter(ProductModel::isSellable)
            .collect(Collectors.toMap(ProductModel::getId, ProductModel::snapshot));
        lines.productIds().stream()
            .filter(productId -> !snapshots.containsKey(productId))
            .findFirst()
            .ifPresent(productId -> {
                throw new CoreException(ErrorType.NOT_FOUND, "[productId = " + productId + "] 상품을 찾을 수 없습니다.");
            });

        OrderModel saved = orderRepository.save(OrderModel.create(userId, lines, snapshots));
        return OrderInfo.from(saved);
    }

    @Transactional(readOnly = true)
    public Page<OrderSummaryInfo> getMyOrders(Long userId, Pageable pageable) {
        return orderRepository.findAll(userId, pageable).map(OrderSummaryInfo::from);
    }

    /**
     * ORD-06: 내 주문만 조회한다. 없거나 남의 주문이면 존재를 드러내지 않고 404.
     */
    @Transactional(readOnly = true)
    public OrderInfo getMyOrder(Long userId, Long orderId) {
        return OrderInfo.from(getOwnedOrder(userId, orderId));
    }

    private OrderModel getOwnedOrder(Long userId, Long orderId) {
        return orderRepository.findById(orderId)
            .filter(order -> order.isOwnedBy(userId))
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + orderId + "] 주문을 찾을 수 없습니다."));
    }
}
