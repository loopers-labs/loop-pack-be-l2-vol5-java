package com.loopers.application.order;

import com.loopers.application.order.port.OrderRepository;
import com.loopers.application.product.port.ProductRepository;
import com.loopers.application.product.ProductNotFoundException;
import com.loopers.application.point.port.PointRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.Quantity;
import com.loopers.domain.product.ProductId;
import com.loopers.domain.product.Product;
import com.loopers.domain.point.Point;
import com.loopers.domain.common.InvalidValueException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional
public class OrderApplicationService {
    private final OrderRepository orders;
    private final ProductRepository products;
    private final PointRepository points;
    public OrderApplicationService(OrderRepository orders, ProductRepository products, PointRepository points) {
        this.orders = orders;
        this.products = products;
        this.points = points;
    }
    public record ItemRequest(long productId, int quantity) { }

    public OrderResult create(long userId, List<ItemRequest> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 100) {
            throw new InvalidValueException("주문 품목은 1~100개여야 합니다.");
        }
        var items = requested.stream().sorted(java.util.Comparator.comparingLong(ItemRequest::productId)).map(i -> {
            Product product = products.findByIdForUpdate(new ProductId(i.productId()))
                .filter(p -> !p.isDeleted()).orElseThrow(ProductNotFoundException::new);
            return new OrderItem(product.getId(), new Quantity(i.quantity()), product.getPrice());
        }).toList();
        return OrderResult.from(orders.save(Order.create(userId, items)));
    }

    public OrderResult confirm(long userId, long orderId) {
        Order order = orders.findByIdForUpdate(orderId).filter(o -> o.getUserId() == userId)
            .orElseThrow(OrderNotFoundException::new);
        order.requireDraft();
        for (OrderItem item : order.getItems().stream()
            .sorted(java.util.Comparator.comparingLong(i -> i.productId().value())).toList()) {
            Product product = products.findByIdForUpdate(item.productId()).filter(p -> !p.isDeleted())
                .orElseThrow(ProductNotFoundException::new);
            product.decreaseStock(item.quantity().value());
            products.save(product);
        }
        Point point = points.findOrCreateForUpdate(userId);
        point.pay(order.getTotal());
        points.save(point);
        order.confirm();
        return OrderResult.from(orders.save(order));
    }

    @Transactional(readOnly = true)
    public OrderResult getMyOrder(long userId, long orderId) {
        return OrderResult.from(orders.findById(orderId).filter(o -> o.getUserId() == userId)
            .orElseThrow(OrderNotFoundException::new));
    }
    @Transactional(readOnly = true)
    public OrderResult getAdminOrder(long orderId) {
        return OrderResult.from(orders.findById(orderId).orElseThrow(OrderNotFoundException::new));
    }
    @Transactional(readOnly = true)
    public List<OrderResult> listMyOrders(long userId, int page, int size) {
        return orders.findPage(userId, page, size).stream().map(OrderResult::from).toList();
    }
    @Transactional(readOnly = true)
    public List<OrderResult> listAdminOrders(Long userId, int page, int size) {
        return orders.findPage(userId, page, size).stream().map(OrderResult::from).toList();
    }
}
