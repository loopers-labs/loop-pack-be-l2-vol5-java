package com.loopers.application.order;

import com.loopers.application.order.port.OrderRepository;
import com.loopers.application.point.port.PointRepository;
import com.loopers.application.product.ProductNotFoundException;
import com.loopers.application.product.port.ProductRepository;
import com.loopers.domain.common.InvalidValueException;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.Quantity;
import com.loopers.domain.point.Point;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductId;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrderApplicationService {
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PointRepository pointRepository;

    public OrderApplicationService(OrderRepository orderRepository, ProductRepository productRepository,
        PointRepository pointRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.pointRepository = pointRepository;
    }

    public record ItemRequest(long productId, int quantity) { }

    public OrderResult create(long userId, List<ItemRequest> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 100) {
            throw new InvalidValueException("주문 품목은 1~100개여야 합니다.");
        }
        // 상품 행은 ID 오름차순으로 잠근다. 주문 확정과 같은 순서를 유지해야 교착 가능성이 줄어든다.
        List<OrderItem> items = requested.stream()
            .sorted(Comparator.comparingLong(ItemRequest::productId))
            .map(request -> {
                Product product = productRepository.findByIdForUpdate(new ProductId(request.productId()))
                    .filter(found -> !found.isDeleted())
                    .orElseThrow(ProductNotFoundException::new);
                return new OrderItem(product.getId(), new Quantity(request.quantity()), product.getPrice());
            })
            .toList();
        return OrderResult.from(orderRepository.save(Order.create(userId, items)));
    }

    public OrderResult confirm(long userId, long orderId) {
        // 잠금 순서: 주문 → 상품 ID 오름차순 → 포인트. 다른 변경 경로도 이 순서를 지켜야 교착을 피할 수 있다.
        Order order = orderRepository.findByIdForUpdate(orderId)
            .filter(found -> found.getUserId() == userId)
            .orElseThrow(OrderNotFoundException::new);
        order.requireDraft();
        List<OrderItem> itemsByProductId = order.getItems().stream()
            .sorted(Comparator.comparingLong(orderItem -> orderItem.productId().value()))
            .toList();
        for (OrderItem item : itemsByProductId) {
            Product product = productRepository.findByIdForUpdate(item.productId())
                .filter(found -> !found.isDeleted())
                .orElseThrow(ProductNotFoundException::new);
            product.decreaseStock(item.quantity().value());
            productRepository.save(product);
        }
        Point point = pointRepository.findOrCreateForUpdate(userId);
        point.pay(order.getTotal());
        pointRepository.save(point);
        order.confirm();
        return OrderResult.from(orderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public OrderResult getMyOrder(long userId, long orderId) {
        Order order = orderRepository.findById(orderId)
            .filter(found -> found.getUserId() == userId)
            .orElseThrow(OrderNotFoundException::new);
        return OrderResult.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResult getAdminOrder(long orderId) {
        return OrderResult.from(orderRepository.findById(orderId).orElseThrow(OrderNotFoundException::new));
    }

    @Transactional(readOnly = true)
    public List<OrderResult> listMyOrders(long userId, int page, int size) {
        return orderRepository.findPage(userId, page, size).stream().map(OrderResult::from).toList();
    }

    @Transactional(readOnly = true)
    public List<OrderResult> listAdminOrders(Long userId, int page, int size) {
        return orderRepository.findPage(userId, page, size).stream().map(OrderResult::from).toList();
    }
}
