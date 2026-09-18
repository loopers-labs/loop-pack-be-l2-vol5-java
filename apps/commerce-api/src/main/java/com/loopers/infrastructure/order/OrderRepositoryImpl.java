package com.loopers.infrastructure.order;

import com.loopers.domain.common.PageResult;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepositoryImpl implements OrderRepository {
    private final OrderJpaRepository repository;
    private final EntityManager entityManager;

    public OrderRepositoryImpl(OrderJpaRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    public Order save(Order order) {
        return repository.save(order);
    }

    @Override
    public Optional<Order> findById(long orderId) {
        return repository.findById(orderId);
    }

    @Override
    public Optional<Order> lockById(long orderId) {
        return repository.lockById(orderId);
    }

    @Override
    public PageResult<Order> findPage(Long userId, OrderStatus status, int page, int size) {
        String condition = " where (:userId is null or o.user.id = :userId)"
            + " and (:status is null or o.status = :status)";
        long total = entityManager.createQuery("select count(o) from Order o" + condition, Long.class)
            .setParameter("userId", userId).setParameter("status", status).getSingleResult();
        long offset = (long) page * size;
        if (offset >= total) {
            return PageResult.of(List.of(), page, size, total);
        }
        List<Long> ids = entityManager.createQuery("select o.id from Order o" + condition
                + " order by o.createdAt desc, o.id desc", Long.class)
            .setParameter("userId", userId).setParameter("status", status)
            .setFirstResult(Math.toIntExact(offset)).setMaxResults(size).getResultList();
        List<Order> orders = entityManager.createQuery(
                "select distinct o from Order o left join fetch o.items where o.id in :ids"
                    + " order by o.createdAt desc, o.id desc", Order.class)
            .setParameter("ids", ids).getResultList();
        return PageResult.of(orders, page, size, total);
    }
}
