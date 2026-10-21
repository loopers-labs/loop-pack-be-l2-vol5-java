package com.loopers.order.application;

import com.loopers.order.application.port.in.OrderCommandUseCase;
import com.loopers.order.application.port.in.OrderInfo;
import com.loopers.order.application.port.out.OrderPort;
import com.loopers.order.domain.OrderLines;
import com.loopers.order.domain.OrderModel;
import com.loopers.product.application.port.out.ProductPort;
import com.loopers.product.domain.ProductModel;
import com.loopers.product.domain.ProductSnapshot;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 고객 주문 변경 유스케이스: 생성(DRAFT). 내 주문 조회는 OrderQueryService가 맡는다.
 * 확정은 W5부터 PG 결제 요청 TX A(payment.application.PaymentRequestService)가 맡는다 — 포인트 확정 경로는 없앴다 (W5 plan.md 4장, 10-1).
 */
@RequiredArgsConstructor
@Component
public class OrderCommandService implements OrderCommandUseCase {

    private final OrderPort orderPort;
    private final ProductPort productPort;

    /**
     * ORD-01: 입력 검증(400) → 상품이 팔 수 있는지(404) → 주문 당시 이름·단가를 복사해 DRAFT로 저장. 재고·포인트는 차감하지 않는다.
     */
    @Transactional
    @Override
    public OrderInfo create(Long userId, List<OrderLines.Line> requestedLines) {
        OrderLines lines = OrderLines.of(requestedLines);
        Map<Long, ProductSnapshot> snapshots = productPort.findAllByIds(lines.productIds()).stream()
            .filter(ProductModel::isSellable)
            .collect(Collectors.toMap(ProductModel::getId, ProductModel::snapshot));
        lines.productIds().stream()
            .filter(productId -> !snapshots.containsKey(productId))
            .findFirst()
            .ifPresent(productId -> {
                throw new CoreException(ErrorType.NOT_FOUND, "[productId = " + productId + "] 상품을 찾을 수 없습니다.");
            });

        OrderModel saved = orderPort.save(OrderModel.create(userId, lines, snapshots));
        return OrderInfo.from(saved);
    }
}
