package com.loopers.application.order;

import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.point.PointBalanceService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RequiredArgsConstructor
@Component
public class OrderConfirmationService {

    private final UserService userService;
    private final ProductService productService;
    private final PointBalanceService pointBalanceService;
    private final OrderService orderService;

    @Transactional
    public OrderModel confirm(Long userId, Long orderId) {
        userService.get(userId);
        OrderModel order = orderService.getForUpdate(orderId);
        if (!order.isOwnedBy(userId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다.");
        }
        order.validateConfirmable();

        List<OrderItem> items = order.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::getProductId))
            .toList();
        List<ProductModel> products = loadAndVerifyStock(items);
        if (pointBalanceService.getAvailable(userId).getBalance() < order.getTotalAmount()) {
            throw new CoreException(ErrorType.CONFLICT, "포인트 잔액이 부족합니다.");
        }
        for (int index = 0; index < products.size(); index++) {
            products.get(index).decreaseStock(items.get(index).getQuantity());
        }
        pointBalanceService.use(userId, order.getTotalAmount());
        long reward = rewardFor(order.getTotalAmount());
        if (reward > 0) {
            pointBalanceService.reward(userId, reward);
        }
        order.confirm();
        return orderService.save(order);
    }

    private List<ProductModel> loadAndVerifyStock(List<OrderItem> items) {
        List<ProductModel> products = new ArrayList<>();
        for (OrderItem item : items) {
            ProductModel product = productService.getForUpdate(item.getProductId());
            if (product.getStockQuantity() < item.getQuantity()) {
                throw new CoreException(ErrorType.CONFLICT, "재고가 부족합니다.");
            }
            products.add(product);
        }
        return products;
    }

    private static long rewardFor(long paidAmount) {
        try {
            return Math.multiplyExact(paidAmount, 2L) / 100L;
        } catch (ArithmeticException exception) {
            throw new CoreException(ErrorType.BAD_REQUEST, "적립 포인트 계산 범위를 초과했습니다.");
        }
    }
}
