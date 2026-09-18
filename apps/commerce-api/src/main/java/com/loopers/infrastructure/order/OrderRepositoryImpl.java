package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderJpaRepository orderJpaRepository;
    private final OrderItemJpaRepository orderItemJpaRepository;
    private final OrderQueryRepository orderQueryRepository;

    @Override
    public Order save(Order order) {
        if (order.getId() == null) {
            OrderEntity saved = orderJpaRepository.save(OrderEntity.from(order));
            List<OrderItemEntity> items = order.getItems().stream()
                .map(item -> OrderItemEntity.of(saved.getId(), item))
                .toList();
            orderItemJpaRepository.saveAll(items);
            return saved.toDomain(order.getItems());
        }

        OrderEntity entity = orderJpaRepository.findById(order.getId())
            .orElseThrow(() -> new IllegalStateException("저장할 주문 행이 없습니다: id=" + order.getId()));
        entity.applyConfirmation(order);
        return entity.toDomain(order.getItems());
    }

    @Override
    public Optional<Order> findById(Long orderId) {
        return orderJpaRepository.findById(orderId).map(this::toDomain);
    }

    @Override
    public Optional<Order> findByIdForUpdate(Long orderId) {
        return orderJpaRepository.findByIdForUpdate(orderId).map(this::toDomain);
    }

    @Override
    public List<Order> findByUserId(Long userId) {
        return toDomain(orderJpaRepository.findByUserIdOrderByIdDesc(userId));
    }

    @Override
    public List<Order> findPage(Long userId, OrderStatus status, int offset, int limit) {
        return toDomain(orderQueryRepository.findPage(userId, status, offset, limit));
    }

    private List<Order> toDomain(List<OrderEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        List<Long> orderIds = entities.stream().map(OrderEntity::getId).toList();
        Map<Long, List<OrderItem>> itemsByOrderId =
            orderItemJpaRepository.findByOrderIdInOrderByIdAsc(orderIds).stream()
                .collect(Collectors.groupingBy(
                    OrderItemEntity::getOrderId,
                    Collectors.mapping(OrderItemEntity::toDomain, Collectors.toList())));

        return entities.stream()
            .map(entity -> entity.toDomain(itemsByOrderId.getOrDefault(entity.getId(), List.of())))
            .toList();
    }

    private Order toDomain(OrderEntity entity) {
        List<OrderItem> items = orderItemJpaRepository.findByOrderIdOrderByIdAsc(entity.getId()).stream()
            .map(OrderItemEntity::toDomain)
            .toList();
        return entity.toDomain(items);
    }
}
