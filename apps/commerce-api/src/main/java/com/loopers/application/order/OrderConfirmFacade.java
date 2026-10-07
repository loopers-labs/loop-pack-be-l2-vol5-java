package com.loopers.application.order;

import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.point.PointChange;
import com.loopers.domain.point.PointHistoryModel;
import com.loopers.domain.point.PointHistoryRepository;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.StockChange;
import com.loopers.domain.product.StockHistoryModel;
import com.loopers.domain.product.StockHistoryRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 주문 확정은 Order·Point·Product 라는 여러 비즈니스 애그리게잇을 함께 변경하므로
 * Facade 가 처리 순서와 전체 트랜잭션을 관리한다. 업무 규칙은 각 모델이 지킨다.
 */
@RequiredArgsConstructor
@Component
public class OrderConfirmFacade {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PointRepository pointRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final StockHistoryRepository stockHistoryRepository;

    @Transactional
    public OrderInfo confirm(Long userId, Long orderId) {
        OrderModel order = orderRepository.findForUpdate(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
        order.requireOwnedBy(userId);
        order.requireConfirmable();

        // 잠근 Order 의 소유권·상태를 확인한 뒤 같은 트랜잭션에서 품목을 별도로 복원하고, 상품 ID 오름차순으로 처리한다.
        List<OrderItemModel> items = order.getItems().stream()
            .sorted(Comparator.comparing(OrderItemModel::getProductId))
            .toList();

        // Point 부족 요청이 공유 Product 잠금을 얻기 전에 끝나도록 Point 를 먼저 잠그고 바로 사용한다.
        PointModel point = pointRepository.findByUserIdForUpdate(userId)
            .orElseThrow(() -> new CoreException(ErrorType.POINT_NOT_INITIALIZED));
        PointChange pointChange = point.use(order.getOrderTotal().toWon());

        Map<Long, ProductModel> products = lockActiveProducts(items);

        for (OrderItemModel item : items) {
            ProductModel product = products.get(item.getProductId());
            StockChange stockChange = product.decreaseStock(item.getQuantity());
            stockHistoryRepository.save(
                StockHistoryModel.deductedByOrder(product.getId(), orderId, stockChange));
            productRepository.save(product);
        }

        pointHistoryRepository.save(PointHistoryModel.usedForOrder(point.getId(), orderId, pointChange));
        order.confirmWithPoints(pointChange.changedAmount());

        pointRepository.save(point);
        return OrderInfo.from(orderRepository.save(order));
    }

    /**
     * 저장된 OrderItem 의 상품을 중복 제거 ID 오름차순으로 잠가 조회하고,
     * 하나라도 없거나 삭제됐으면 거절한다.
     */
    private Map<Long, ProductModel> lockActiveProducts(List<OrderItemModel> items) {
        List<Long> productIds = items.stream().map(OrderItemModel::getProductId).distinct().sorted().toList();
        Map<Long, ProductModel> products = productRepository.findAllActiveByIdsForUpdate(productIds).stream()
            .collect(Collectors.toMap(ProductModel::getId, Function.identity()));

        productIds.forEach(productId -> {
            if (!products.containsKey(productId)) {
                throw new CoreException(ErrorType.PRODUCT_NOT_FOUND, "[productId = " + productId + "]");
            }
        });
        return products;
    }
}
