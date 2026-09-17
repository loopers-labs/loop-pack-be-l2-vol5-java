package com.loopers.domain.order;

import com.loopers.domain.product.ProductModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 주문 확정 판단 (도메인 서비스, ADR-05).
 * 자기 상태 없이 주문 품목과 상품들을 함께 보고, 상품에 "팔 수 있어? 이 단가야? 이만큼 있어?"를 묻는다. 아무것도 바꾸지 않는다.
 */
@Component
public class OrderConfirmPolicy {

    /**
     * ORD-03: 하나라도 아니면 409. 주문은 존재하므로 상품이 사라진 것은 주문의 현재 상태와의 충돌이다.
     */
    public void check(OrderModel order, List<ProductModel> products) {
        Map<Long, ProductModel> productsById = products.stream()
            .collect(Collectors.toMap(ProductModel::getId, Function.identity()));

        for (OrderItemModel item : order.getItems()) {
            ProductModel product = productsById.get(item.getProductId());
            if (product == null || !product.isSellable()) {
                throw new CoreException(ErrorType.CONFLICT, "[productId = " + item.getProductId() + "] 더 이상 판매하지 않는 상품입니다.");
            }
            // 생성 때 복사한 값과 같은 출처(snapshot의 판매 단가)로 비교한다 (ADR-06). 판매 단가의 정의가 바뀌어도 한 곳만 고친다.
            if (!product.snapshot().price().equals(item.getUnitPrice())) {
                throw new CoreException(ErrorType.CONFLICT, "[productId = " + item.getProductId() + "] 주문 후 가격이 바뀌었습니다. 주문을 다시 만들어 주세요.");
            }
            if (!product.hasStock(item.getQuantity())) {
                throw new CoreException(ErrorType.CONFLICT, "[productId = " + item.getProductId() + "] 재고가 부족합니다.");
            }
        }
    }
}
