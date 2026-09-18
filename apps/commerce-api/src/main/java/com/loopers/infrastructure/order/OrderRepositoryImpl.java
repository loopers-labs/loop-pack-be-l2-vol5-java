package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional
public class OrderRepositoryImpl implements OrderRepository {
    private final OrderJpaRepository repository;
    @Override
    @Transactional(readOnly = true)
    public Optional<Order> findById(long id) { return repository.findById(id).map(OrderJpaEntity::toDomain); }
    @Override
    public Order save(Order order) {
        OrderJpaEntity entity = order.getId() == null ? new OrderJpaEntity(order) : repository.findById(order.getId()).orElseThrow();
        entity.update(order);
        return repository.save(entity).toDomain();
    }
    @Override
    @Transactional(readOnly = true)
    public List<Order> findByUserId(long userId) {
        return repository.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream().map(OrderJpaEntity::toDomain).toList();
    }
    @Override
    @Transactional(readOnly = true)
    public Page<Order> findAll(Long userId, Pageable pageable) {
        return (userId == null ? repository.findAll(pageable) : repository.findByUserId(userId, pageable)).map(OrderJpaEntity::toDomain);
    }
}
