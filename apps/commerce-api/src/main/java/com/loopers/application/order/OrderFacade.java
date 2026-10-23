package com.loopers.application.order;

import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.common.PageWindow;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OrderFacade {

    public enum PaymentMethod {
        POINT,
        PG
    }

    public record OrderDetail(Order order, Payment payment) {

        public PaymentMethod paymentMethod() {
            if (payment != null) {
                return PaymentMethod.PG;
            }
            return order.getStatus() == OrderStatus.CONFIRMED ? PaymentMethod.POINT : null;
        }
    }

    private final OrderService orderService;
    private final PaymentService paymentService;
    private final ProductService productService;

    @Transactional
    public Order place(OrderCreateCommand command, Instant now) {
        List<OrderItem> items = command.lines().stream()
            .map(line -> {
                Product product = productService.get(line.productId());
                return OrderItem.of(
                    product.getId(), product.getName(), product.getPrice(), line.quantity());
            })
            .toList();

        return orderService.place(Order.draft(command.userId(), items, now));
    }

    @Transactional(readOnly = true)
    public Order get(Long userId, Long orderId) {
        return orderService.getOwned(userId, orderId);
    }

    @Transactional(readOnly = true)
    public OrderDetail getDetail(Long userId, Long orderId) {
        Order order = orderService.getOwned(userId, orderId);
        return new OrderDetail(order, paymentService.findByOrderId(orderId).orElse(null));
    }

    @Transactional(readOnly = true)
    public OrderDetail getDetailForAdmin(Long orderId) {
        Order order = orderService.get(orderId);
        return new OrderDetail(order, paymentService.findByOrderId(orderId).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<Order> findMine(Long userId) {
        return orderService.findMine(userId);
    }

    @Transactional(readOnly = true)
    public PageWindow<Order> findPageForAdmin(Long userId, OrderStatus status, PageNumber page, PageSize size) {
        return orderService.findPage(userId, status, page, size);
    }
}
