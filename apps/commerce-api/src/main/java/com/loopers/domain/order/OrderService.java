package com.loopers.domain.order;

import com.loopers.domain.product.ProductModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 여러 주문 요청 품목을 하나의 초안 주문으로 만드는 도메인 규칙을 담당한다. */
@Component
public class OrderService {

    public Map<Long, Long> mergeQuantities(List<OrderItemCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new CoreException(ErrorType.INVALID_ORDER_ITEMS);
        }
        commands.forEach(command -> {
            if (command.quantity() < 1L) {
                throw new CoreException(ErrorType.INVALID_ORDER_QUANTITY);
            }
        });

        Map<Long, Long> merged = new LinkedHashMap<>();
        for (OrderItemCommand command : commands) {
            merged.merge(command.productId(), command.quantity(), (left, right) -> {
                try {
                    return Math.addExact(left, right);
                } catch (ArithmeticException e) {
                    throw new CoreException(ErrorType.NUMERIC_OVERFLOW);
                }
            });
        }
        return Collections.unmodifiableMap(merged);
    }

    public OrderModel createDraft(
        Long userId,
        Map<Long, Long> quantityByProductId,
        Map<Long, ProductModel> productsById
    ) {
        List<OrderItemModel> items = new ArrayList<>();
        quantityByProductId.forEach((productId, quantity) -> {
            ProductModel product = productsById.get(productId);
            items.add(OrderItemModel.of(productId, quantity, product.getPrice()));
        });

        return OrderModel.draft(userId, items);
    }
}
