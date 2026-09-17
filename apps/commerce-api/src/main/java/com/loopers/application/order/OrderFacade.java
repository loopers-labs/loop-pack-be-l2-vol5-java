package com.loopers.application.order;

import com.loopers.application.PageInfo;
import com.loopers.domain.common.PageCondition;
import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class OrderFacade {
    private final OrderService orderService;
    private final ProductService productService;
    private final UserService userService;

    public OrderInfo create(Long userId, List<OrderLineCommand> lines) {
        userService.getUser(userId);
        Map<Long, Product> products = activeProductsById(lines.stream().map(OrderLineCommand::productId).toList());

        // 단가는 요청으로 받지 않고 현재 상품 가격을 쓴다
        List<OrderItem> items = lines.stream()
            .map(line -> new OrderItem(line.productId(), line.quantity(), priceOf(products, line.productId())))
            .toList();

        Order order = orderService.createOrder(userId, items);
        return OrderInfo.of(order, namesOf(products.values()));
    }

    // 재고 차감·포인트 차감·주문 상태 변경을 한 트랜잭션으로 묶는다(결정 3)
    @Transactional
    public OrderInfo confirm(Long userId, Long orderId) {
        User user = userService.getUser(userId);
        Order order = orderService.getOrderOf(userId, orderId);
        Map<Long, Product> products = activeProductsById(order.getProductIds());

        // 상태 확인을 먼저 해, 이미 확정된 주문이 재고 부족 등 다른 사유로 거절되지 않게 한다
        order.confirm(products.values().stream().collect(Collectors.toMap(Product::getId, Product::getPrice)));
        order.getItems().forEach(item -> products.get(item.getProductId()).decreaseStock(item.getQuantity()));
        user.use(order.getPaidAmount());

        return OrderInfo.of(order, namesOf(products.values()));
    }

    @Transactional(readOnly = true)
    public PageInfo<OrderInfo> getOrders(Long userId, int page, int size) {
        PageCondition pageCondition = new PageCondition(page, size);
        userService.getUser(userId);

        List<Order> orders = orderService.getOrders(userId, pageCondition);
        Map<Long, String> productNames = productService.getProductNamesIncludingDeleted(orders.stream().flatMap(order -> order.getProductIds().stream()).toList());
        List<OrderInfo> content = orders.stream().map(order -> OrderInfo.of(order, productNames)).toList();

        return PageInfo.of(content, page, size, orderService.countOrders(userId));
    }

    @Transactional(readOnly = true)
    public OrderInfo getOrder(Long userId, Long orderId) {
        userService.getUser(userId);
        Order order = orderService.getOrderOf(userId, orderId);
        return OrderInfo.of(order, productService.getProductNamesIncludingDeleted(order.getProductIds()));
    }

    private Map<Long, Product> activeProductsById(Collection<Long> productIds) {
        return productService.getActiveProducts(productIds).stream()
            .collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    private Long priceOf(Map<Long, Product> products, Long productId) {
        Product product = products.get(productId);
        if (product == null) {
            throw new DomainException(DomainErrorType.NOT_FOUND, "[productId = " + productId + "] 상품을 찾을 수 없습니다.");
        }
        return product.getPrice();
    }

    private Map<Long, String> namesOf(Collection<Product> products) {
        return products.stream().collect(Collectors.toMap(Product::getId, Product::getName));
    }
}
