package com.loopers.application.order;

import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.point.PointService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class OrderFacade {

    private final UserService userService;
    private final ProductService productService;
    private final PointService pointService;
    private final OrderService orderService;

    public OrderInfo createOrder(Long userId, List<OrderItemCommand> commands) {
        userService.getUser(userId);
        if (commands == null || commands.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 품목은 비어있을 수 없습니다.");
        }

        Map<Long, Integer> mergedQuantities = new LinkedHashMap<>();
        for (OrderItemCommand command : commands) {
            mergedQuantities.merge(command.productId(), command.quantity(), Integer::sum);
        }

        List<Long> productIds = List.copyOf(mergedQuantities.keySet());
        Map<Long, ProductModel> products = productService.getActiveProductsByIds(productIds).stream()
            .collect(Collectors.toMap(ProductModel::getId, Function.identity()));

        List<OrderItemModel> items = mergedQuantities.entrySet().stream()
            .map(entry -> {
                ProductModel product = products.get(entry.getKey());
                if (product == null) {
                    throw new CoreException(ErrorType.BAD_REQUEST,
                        "[id = " + entry.getKey() + "] 삭제되었거나 존재하지 않는 상품은 주문할 수 없습니다.");
                }
                return new OrderItemModel(product.getId(), entry.getValue(), product.getPrice());
            })
            .toList();

        return OrderInfo.from(orderService.create(userId, items));
    }

    @Transactional
    public OrderInfo confirmOrder(Long userId, Long orderId) {
        userService.getUser(userId);
        OrderModel order = orderService.getMyOrder(userId, orderId);
        order.requireDraft();

        for (OrderItemModel item : order.getItems()) {
            productService.deductStock(item.getProductId(), item.getQuantity());
        }
        pointService.use(userId, order.getTotalAmount());

        order.confirm();
        return OrderInfo.from(orderService.save(order));
    }

    public OrderInfo getMyOrder(Long userId, Long orderId) {
        userService.getUser(userId);
        return OrderInfo.from(orderService.getMyOrder(userId, orderId));
    }

    public List<OrderInfo> getMyOrders(Long userId) {
        userService.getUser(userId);
        return orderService.getMyOrders(userId).stream().map(OrderInfo::from).toList();
    }
}
