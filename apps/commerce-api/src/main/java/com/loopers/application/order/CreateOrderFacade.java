package com.loopers.application.order;

import com.loopers.application.user.IdentifyUser;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Transactional
public class CreateOrderFacade {
    private final IdentifyUser users;
    private final ProductRepository products;
    private final OrderRepository orders;
    public record Item(Long productId, Integer quantity) {}

    public OrderInfo create(Long userId, List<Item> items) {
        long owner = users.require(userId);
        if (items == null) { throw new CoreException(ErrorType.INVALID_REQUEST); }
        List<Order.RequestedItem> requested = new ArrayList<>();
        for (Item item : items) {
            if (item == null || item.productId() == null || item.quantity() == null) { throw new CoreException(ErrorType.INVALID_REQUEST); }
            Product product = products.findById(item.productId()).orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND));
            product.requireActive();
            requested.add(new Order.RequestedItem(product.getId(), item.quantity(), product.getPrice()));
        }
        return OrderInfo.from(orders.save(Order.create(owner, requested)));
    }
}
