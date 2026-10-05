package com.loopers.order.application;

import com.loopers.common.domain.Money;
import com.loopers.order.application.port.in.OrderCommandUseCase;
import com.loopers.order.application.port.in.OrderInfo;
import com.loopers.order.application.port.out.OrderPort;
import com.loopers.order.domain.OrderConfirmPolicy;
import com.loopers.order.domain.OrderItemModel;
import com.loopers.order.domain.OrderLines;
import com.loopers.order.domain.OrderModel;
import com.loopers.point.application.port.out.PointGroupPort;
import com.loopers.point.application.port.out.PointHistoryPort;
import com.loopers.point.domain.PointGroup;
import com.loopers.point.domain.PointHistory;
import com.loopers.point.domain.PointUsage;
import com.loopers.point.domain.PointWalletPolicy;
import com.loopers.product.application.port.out.ProductPort;
import com.loopers.product.domain.ProductModel;
import com.loopers.product.domain.ProductSnapshot;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 고객 주문 변경 유스케이스: 생성(DRAFT), 확정(포인트 결제). 내 주문 조회는 OrderQueryService가 맡는다.
 */
@RequiredArgsConstructor
@Component
public class OrderCommandService implements OrderCommandUseCase {

    private final OrderPort orderPort;
    private final ProductPort productPort;
    private final PointGroupPort pointGroupPort;
    private final PointHistoryPort pointHistoryPort;
    private final OrderConfirmPolicy orderConfirmPolicy;
    private final PointWalletPolicy pointWalletPolicy;

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

    /**
     * ORD-02~05: 소유·상태(404·409) → 상품 재검증(409) → 잔액(409)을 모두 확인한 뒤에만 재고·포인트·주문을 바꾼다 (8장).
     * 네 가지 변경(재고, 그룹의 남은 금액, 사용 이력, 주문 상태)은 이 트랜잭션 하나로 함께 반영되거나 함께 되돌려진다.
     * TODO : 로직 확인 필요 (forEach 2개)
     */
    @Transactional
    @Override
    public OrderInfo confirm(Long userId, Long orderId) {
        ZonedDateTime now = ZonedDateTime.now();
        OrderModel order = orderPort.findByIdForUpdate(orderId)
                .filter(found -> found.isOwnedBy(userId))
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + orderId + "] 주문을 찾을 수 없습니다."));
        order.checkConfirmable();

        List<Long> productIds = order.getItems().stream().map(OrderItemModel::getProductId).toList();
        List<ProductModel> products = productPort.findAllByIdsForUpdate(productIds);
        orderConfirmPolicy.check(order, products);

        Money paymentAmount = order.paymentAmount();
        List<PointGroup> groups = pointGroupPort.findRemainingByUserIdForUpdate(userId);
        pointWalletPolicy.checkPayable(groups, paymentAmount, now);

        // 여기부터 변경. orderConfirmPolicy.check가 모든 품목의 상품이 있고 재고가 충분함을 이미 확인했다.
        Map<Long, ProductModel> productsById = products.stream()
            .collect(Collectors.toMap(ProductModel::getId, Function.identity()));
        order.getItems().forEach(item -> productsById.get(item.getProductId()).decreaseStock(item.getQuantity()));
        products.forEach(productPort::save);

        List<PointUsage> usages = pointWalletPolicy.pay(groups, paymentAmount, now);
        usages.forEach(usage -> pointGroupPort.save(usage.group()));
        pointHistoryPort.saveAll(usages.stream().map(usage -> PointHistory.use(usage, order.getId(), now)).toList());

        order.confirm(now);
        return OrderInfo.from(orderPort.save(order));
    }
}
