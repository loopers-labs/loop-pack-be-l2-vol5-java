package com.loopers.application.order;

import com.loopers.application.PageInfo;
import com.loopers.domain.common.PageCondition;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class AdminOrderFacade {
    private final OrderService orderService;
    private final ProductService productService;

    @Transactional(readOnly = true)
    public PageInfo<AdminOrderInfo> getOrders(Long userId, int page, int size) {
        PageCondition pageCondition = new PageCondition(page, size);

        List<Order> orders = orderService.getAllOrders(userId, pageCondition);
        Map<Long, String> productNames = productService.getProductNamesIncludingDeleted(orders.stream().flatMap(order -> order.getProductIds().stream()).toList());
        List<AdminOrderInfo> content = orders.stream().map(order -> AdminOrderInfo.of(order, productNames)).toList();

        return PageInfo.of(content, page, size, orderService.countAllOrders(userId));
    }

    @Transactional(readOnly = true)
    public AdminOrderInfo getOrder(Long orderId) {
        Order order = orderService.getOrder(orderId);
        return AdminOrderInfo.of(order, productService.getProductNamesIncludingDeleted(order.getProductIds()));
    }
}
