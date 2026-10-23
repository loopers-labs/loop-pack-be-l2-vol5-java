package com.loopers.domain.order;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 결제 대기 주문의 재고 확보와 반환. 가용 재고만 움직이고 예약을 따로 저장하지 않는다. 호출하는 쪽의 트랜잭션 안에서
 * 주문 행을 잠근 뒤에 부르며, 상품은 항상 id 오름차순으로 잠근다. 반환이 한 번만 일어나는 것은 호출하는 쪽의 상태 전이가
 * 보장한다.
 */
@RequiredArgsConstructor
@Component
public class OrderStockService {

    private final ProductService productService;

    /**
     * 주문 품목마다 가용 재고를 차감한다. 상품이 삭제됐거나 없으면 {@code PRODUCT_UNAVAILABLE}, 재고가 모자라면
     * {@code OUT_OF_STOCK} 이며 호출하는 쪽의 트랜잭션이 되돌아간다.
     */
    public void deductStock(Order order) {
        Map<Long, Product> products = productService.getAllActiveByIdsForUpdate(order.productIds());
        requireAllAvailable(order, products);
        order.getItems().forEach(item -> products.get(item.getProductId()).decreaseStock(item.getQuantity()));
    }

    /**
     * 확보했던 재고를 돌려준다. 그사이 상품이 삭제됐어도 수량은 돌려받고, 삭제 상태는 그대로라 상품이 다시 노출되지 않는다.
     * 상품 행 자체가 없으면 돌려줄 곳이 없으므로 데이터의 문제로 보고 {@code INTERNAL_ERROR} 다.
     */
    public void restoreStock(Order order) {
        Map<Long, Product> products = productService.getAllByIdsForUpdate(order.productIds());
        for (OrderItem item : order.getItems()) {
            Product product = products.get(item.getProductId());
            if (product == null) {
                throw new CoreException(
                    ErrorType.INTERNAL_ERROR, "재고를 돌려줄 상품이 없습니다. (상품 ID: " + item.getProductId() + ")"
                );
            }
            product.increaseStock(item.getQuantity());
        }
    }

    // 삭제됐거나 없는 상품을 품목 순서대로 모두 주문 당시 이름으로 알린다.
    private void requireAllAvailable(Order order, Map<Long, Product> products) {
        List<String> unavailableNames = order.getItems().stream()
            .filter(item -> !products.containsKey(item.getProductId()))
            .map(OrderItem::getProductName)
            .toList();
        if (!unavailableNames.isEmpty()) {
            ErrorType errorType = ErrorType.PRODUCT_UNAVAILABLE;
            throw new CoreException(errorType, errorType.getMessage() + " (" + String.join(", ", unavailableNames) + ")");
        }
    }
}
