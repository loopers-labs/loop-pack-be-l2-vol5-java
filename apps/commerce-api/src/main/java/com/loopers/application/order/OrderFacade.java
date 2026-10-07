package com.loopers.application.order;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.order.OrderItemCommand;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 주문 생성·조회 API 유스케이스의 처리 순서와 트랜잭션 경계를 담당한다. */
@RequiredArgsConstructor
@Component
public class OrderFacade {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final OrderService orderService;

    @Transactional
    public OrderModel create(Long userId, List<OrderItemCommand> commands) {
        Map<Long, Long> quantityByProductId = orderService.mergeQuantities(commands);
        Map<Long, ProductModel> productsById = findActiveProducts(quantityByProductId.keySet());
        OrderModel order = orderService.createDraft(userId, quantityByProductId, productsById);
        return orderRepository.save(order);
    }

    @Transactional(readOnly = true)
    public OrderModel getOrder(Long userId, Long orderId) {
        OrderModel order = find(orderId);
        order.requireOwnedBy(userId);
        return order;
    }

    /** 관리자 조회는 소유자를 확인하지 않는다. */
    @Transactional(readOnly = true)
    public OrderModel getAnyOrder(Long orderId) {
        return find(orderId);
    }

    @Transactional(readOnly = true)
    public PageResult<OrderModel> getOrders(Long userId, PageCommand page, ListSort sort) {
        return orderRepository.findPageByUserId(userId, page, sort);
    }

    /** 관리자는 소유자와 무관하게 모든 주문을 조회한다. */
    @Transactional(readOnly = true)
    public PageResult<OrderModel> getAllOrders(PageCommand page, ListSort sort) {
        return orderRepository.findPage(page, sort);
    }

    private OrderModel find(Long orderId) {
        return orderRepository.find(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
    }

    private Map<Long, ProductModel> findActiveProducts(Set<Long> productIds) {
        Map<Long, ProductModel> productsById = productRepository.findAllActiveByIds(productIds).stream()
            .collect(Collectors.toMap(ProductModel::getId, Function.identity()));

        productIds.forEach(productId -> {
            if (!productsById.containsKey(productId)) {
                throw new CoreException(ErrorType.PRODUCT_NOT_FOUND, "[productId = " + productId + "]");
            }
        });
        return productsById;
    }
}
