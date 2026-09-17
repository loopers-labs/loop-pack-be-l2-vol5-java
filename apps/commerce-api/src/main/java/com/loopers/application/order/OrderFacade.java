package com.loopers.application.order;

import com.loopers.domain.common.Money;
import com.loopers.domain.order.OrderConfirmPolicy;
import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderLines;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.point.PointGroup;
import com.loopers.domain.point.PointGroupRepository;
import com.loopers.domain.point.PointHistory;
import com.loopers.domain.point.PointHistoryRepository;
import com.loopers.domain.point.PointUsage;
import com.loopers.domain.point.PointWalletPolicy;
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

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 고객 주문 유스케이스: 생성(DRAFT), 확정(포인트 결제), 내 주문 조회.
 */
@RequiredArgsConstructor
@Component
public class OrderFacade {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PointGroupRepository pointGroupRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final OrderConfirmPolicy orderConfirmPolicy;
    private final PointWalletPolicy pointWalletPolicy;

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

    /**
     * ORD-02~05: 소유·상태(404·409) → 상품 재검증(409) → 잔액(409)을 모두 확인한 뒤에만 재고·포인트·주문을 바꾼다 (8장).
     * 네 가지 변경(재고, 그룹의 남은 금액, 사용 이력, 주문 상태)은 이 트랜잭션 하나로 함께 반영되거나 함께 되돌려진다.
     */
    @Transactional
    public OrderInfo confirm(Long userId, Long orderId) {
        ZonedDateTime now = ZonedDateTime.now();
        OrderModel order = getOwnedOrder(userId, orderId);
        order.checkConfirmable();

        List<Long> productIds = order.getItems().stream().map(OrderItemModel::getProductId).toList();
        List<ProductModel> products = productRepository.findAllByIds(productIds);
        orderConfirmPolicy.check(order, products);

        Money paymentAmount = order.paymentAmount();
        List<PointGroup> groups = pointGroupRepository.findRemainingByUserId(userId);
        pointWalletPolicy.checkPayable(groups, paymentAmount, now);

        // 여기부터 변경. orderConfirmPolicy.check가 모든 품목의 상품이 있고 재고가 충분함을 이미 확인했다.
        Map<Long, ProductModel> productsById = products.stream()
            .collect(Collectors.toMap(ProductModel::getId, Function.identity()));
        order.getItems().forEach(item -> productsById.get(item.getProductId()).decreaseStock(item.getQuantity()));

        List<PointUsage> usages = pointWalletPolicy.pay(groups, paymentAmount, now);
        pointHistoryRepository.saveAll(usages.stream().map(usage -> PointHistory.use(usage, order.getId(), now)).toList());

        order.confirm(now);
        return OrderInfo.from(orderRepository.save(order));
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
