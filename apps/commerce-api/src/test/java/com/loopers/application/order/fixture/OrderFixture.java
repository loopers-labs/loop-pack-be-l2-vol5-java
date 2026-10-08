package com.loopers.application.order.fixture;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.order.OrderJpaRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Transactional
public class OrderFixture {
    @Autowired private OrderRepository orders;
    @Autowired private OrderJpaRepository orderRows;
    @PersistenceContext private EntityManager entityManager;

    public Order createOrder(long userId, Product product, int quantity) {
        return createOrder(
                userId,
                List.of(new Order.RequestedItem(product.getId(), quantity, product.getPrice())));
    }

    public Order createOrder(long userId, List<Order.RequestedItem> items) {
        return orders.save(Order.create(userId, items));
    }

    public Order createConfirmedOrder(long userId, List<Order.RequestedItem> items) {
        Order order = Order.create(userId, items);
        order.confirm();
        return orders.save(order);
    }

    @Transactional(readOnly = true)
    public Order order(long id) {
        return orders.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public long rowCount() {
        return orderRows.count();
    }

    @Transactional(readOnly = true)
    public long itemCount() {
        return entityManager
                .createQuery(
                        "select count(item.productId) from OrderJpaEntity o join o.items item",
                        Long.class)
                .getSingleResult();
    }

    @Transactional(readOnly = true)
    public List<Order> orders(List<Order> drafts) {
        return drafts.stream().map(draft -> order(draft.getId())).toList();
    }
}
