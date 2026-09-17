package com.loopers.domain.order;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Order 만 주된 상태로 변경하는 유스케이스를 담당한다.
 * 주문 생성에서 Product 의 상태를 읽지만 Product 를 변경하지 않는다.
 */
@RequiredArgsConstructor
@Component
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    @Transactional
    public OrderModel create(Long userId, List<OrderItemCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new CoreException(ErrorType.INVALID_ORDER_ITEMS);
        }
        commands.forEach(command -> {
            if (command.quantity() < 1L) {
                throw new CoreException(ErrorType.INVALID_ORDER_QUANTITY);
            }
        });

        Map<Long, Long> quantityByProductId = mergeQuantities(commands);
        Map<Long, ProductModel> activeProducts = productRepository
            .findAllActiveByIds(quantityByProductId.keySet()).stream()
            .collect(Collectors.toMap(ProductModel::getId, Function.identity()));

        List<OrderItemModel> items = new ArrayList<>();
        quantityByProductId.forEach((productId, quantity) -> {
            ProductModel product = activeProducts.get(productId);
            if (product == null) {
                throw new CoreException(ErrorType.PRODUCT_NOT_FOUND, "[productId = " + productId + "]");
            }
            items.add(OrderItemModel.of(productId, quantity, product.getPrice()));
        });

        return orderRepository.save(OrderModel.draft(userId, items));
    }

    @Transactional(readOnly = true)
    public OrderModel getOrder(Long userId, Long orderId) {
        OrderModel order = find(orderId);
        order.requireOwnedBy(userId);
        return order;
    }

    /** 관리자 조회: 소유자를 확인하지 않는다. */
    @Transactional(readOnly = true)
    public OrderModel getAnyOrder(Long orderId) {
        return find(orderId);
    }

    @Transactional(readOnly = true)
    public PageResult<OrderModel> getOrders(Long userId, PageCommand page, ListSort sort) {
        return orderRepository.findPageByUserId(userId, page, sort);
    }

    /** 관리자 조회: 소유자와 무관하게 모든 주문을 페이지로 반환한다. */
    @Transactional(readOnly = true)
    public PageResult<OrderModel> getAllOrders(PageCommand page, ListSort sort) {
        return orderRepository.findPage(page, sort);
    }

    private OrderModel find(Long orderId) {
        return orderRepository.find(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
    }

    /** 같은 상품의 수량을 합산한다. 요청 순서를 유지해 품목 순서를 안정적으로 만든다. */
    private Map<Long, Long> mergeQuantities(List<OrderItemCommand> commands) {
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
        return merged;
    }
}
