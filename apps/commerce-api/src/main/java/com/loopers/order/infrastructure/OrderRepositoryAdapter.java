package com.loopers.order.infrastructure;

import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class OrderRepositoryAdapter implements OrderRepository {

    private final OrderJpaRepository jpaRepository;
    private final EntityManager entityManager;

    public OrderRepositoryAdapter(OrderJpaRepository jpaRepository, EntityManager entityManager) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
    }

    @Override
    public Order save(Order order) {
        if (order.getId() == 0L) {
            entityManager.persist(order);
            return order;
        }
        return jpaRepository.save(order);
    }

    @Override
    public Optional<Order> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<Order> findAllByBuyerId(Long buyerId, int page, int size) {
        return jpaRepository.findAllByBuyerId(buyerId, pageRequest(page, size));
    }

    @Override
    public List<Order> findAll(Long buyerId, int page, int size) {
        PageRequest pageRequest = pageRequest(page, size);
        if (buyerId == null) {
            return jpaRepository.findAll(pageRequest).getContent();
        }
        return jpaRepository.findAllByBuyerId(buyerId, pageRequest);
    }

    @Override
    public long countAllByBuyerId(Long buyerId) {
        return jpaRepository.countByBuyerId(buyerId);
    }

    @Override
    public long countAll(Long buyerId) {
        if (buyerId == null) {
            return jpaRepository.count();
        }
        return jpaRepository.countByBuyerId(buyerId);
    }

    private PageRequest pageRequest(int page, int size) {
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
        return PageRequest.of(page, size, sort);
    }
}
