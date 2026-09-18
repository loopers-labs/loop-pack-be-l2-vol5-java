package com.loopers.domain.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** DB 없이 주문 서비스의 협력을 확인하기 위한 저장 구현. */
public class FakeOrderRepository implements OrderRepository {

    private final Map<Long, Order> orders = new LinkedHashMap<>();
    private long sequence = 0L;

    @Override
    public Order save(Order order) {
        if (order.getId() == null || order.getId() == 0L) {
            ReflectionTestUtils.setField(order, "id", ++sequence);
        }
        orders.put(order.getId(), order);
        return order;
    }

    @Override
    public Optional<Order> findByIdAndUserId(Long orderId, Long userId) {
        return Optional.ofNullable(orders.get(orderId)).filter(order -> Objects.equals(order.getUserId(), userId));
    }

    @Override
    public Optional<Order> findById(Long orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public Page<Order> findPage(Long userId, OrderStatus status, Pageable pageable) {
        List<Order> matched = orders.values().stream()
            .filter(order -> userId == null || Objects.equals(order.getUserId(), userId))
            .filter(order -> status == null || order.getStatus() == status)
            .sorted(Comparator.comparing(Order::getId).reversed())
            .toList();
        return new PageImpl<>(matched, pageable, matched.size());
    }

    public int count() {
        return orders.size();
    }
}
