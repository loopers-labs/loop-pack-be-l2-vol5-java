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
        OrderModel order = orderRepository.find(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
        order.requireOwnedBy(userId);
        order.requireConfirmable();

        List<OrderItemModel> items = order.getItems();
        Map<Long, ProductModel> products = findActiveProducts(items);
        PointModel point = pointRepository.findByUserId(userId)
            .orElseThrow(() -> new CoreException(ErrorType.POINT_NOT_INITIALIZED));

        PointChange pointChange = point.use(order.getOrderTotal().toWon());

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

    /** 저장된 OrderItem 의 상품이 모두 존재하고 삭제되지 않았는지 확인한다. */
    private Map<Long, ProductModel> findActiveProducts(List<OrderItemModel> items) {
        List<Long> productIds = items.stream().map(OrderItemModel::getProductId).toList();
        Map<Long, ProductModel> products = productRepository.findAllActiveByIds(productIds).stream()
            .collect(Collectors.toMap(ProductModel::getId, Function.identity()));

        productIds.forEach(productId -> {
            if (!products.containsKey(productId)) {
                throw new CoreException(ErrorType.PRODUCT_NOT_FOUND, "[productId = " + productId + "]");
            }
        });
        return products;
    }
}
