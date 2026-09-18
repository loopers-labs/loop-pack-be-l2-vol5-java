package com.loopers.domain.order;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 주문 항목을 만들기 전, 같은 상품의 입력 수량을 하나로 모은다.
 */
public final class OrderQuantities {

    private final Map<Long, Integer> quantitiesByProductId;

    private OrderQuantities(Map<Long, Integer> quantitiesByProductId) {
        this.quantitiesByProductId = Map.copyOf(quantitiesByProductId);
    }

    public static OrderQuantities combine(List<Item> items) {
        if (items == null || items.isEmpty()) {
            throw new OrderQuantityException(OrderQuantityException.Reason.INVALID_ITEMS);
        }
        Map<Long, Integer> quantities = new HashMap<>();
        for (Item item : items) {
            validateItem(item);
            quantities.merge(item.productId(), item.quantity(), OrderQuantities::addQuantities);
        }
        return new OrderQuantities(quantities);
    }

    private static void validateItem(Item item) {
        if (item == null) {
            throw new OrderQuantityException(OrderQuantityException.Reason.INVALID_ITEMS);
        }
        if (item.productId() <= 0) {
            throw new OrderQuantityException(OrderQuantityException.Reason.INVALID_PRODUCT_ID);
        }
        if (item.quantity() <= 0) {
            throw new OrderQuantityException(OrderQuantityException.Reason.INVALID_QUANTITY);
        }
    }

    private static int addQuantities(int first, int second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            throw new OrderQuantityException(OrderQuantityException.Reason.QUANTITY_LIMIT_EXCEEDED);
        }
    }

    public Map<Long, Integer> byProductId() {
        return quantitiesByProductId;
    }

    public record Item(long productId, int quantity) {
    }
}
