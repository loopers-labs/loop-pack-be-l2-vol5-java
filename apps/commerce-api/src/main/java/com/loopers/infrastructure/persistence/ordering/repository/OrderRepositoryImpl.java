package com.loopers.infrastructure.persistence.ordering.repository;

import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderRecord;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.infrastructure.persistence.ordering.entity.OrderEntityMapper;
import com.loopers.infrastructure.persistence.ordering.entity.OrderJpaEntity;
import com.loopers.infrastructure.persistence.ordering.jpa.OrderJpaRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
// 주문 저장소 JPA 구현체
public class OrderRepositoryImpl implements OrderRepository {
    private final OrderJpaRepository orderJpaRepository;
    private final OrderEntityMapper mapper;

    @Override
    public Order insert(Order order) {
        if (order.getId() != null) {
            throw new IllegalArgumentException("새 주문만 insert할 수 있습니다.");
        }
        return mapper.toDomain(orderJpaRepository.saveAndFlush(mapper.toNewEntity(order)));
    }

    @Override
    public boolean prepareIfDraft(long orderId, long userId) {
        return orderJpaRepository.prepareIfDraft(orderId, userId) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean completePgIfPending(long orderId, OrderStatus terminalStatus, OrderRecord pgRecord) {
        Optional<Order> found = findById(orderId);
        if (found.isEmpty() || found.get().getStatus() != OrderStatus.PAYMENT_PENDING) {
            return false;
        }
        Order completed = found.get();
        completed.completePgPayment(terminalStatus, pgRecord);
        if (orderJpaRepository.completePgIfPending(orderId, terminalStatus) != 1) {
            return false;
        }
        // CONFIRMED/기록 없음의 중간 엔티티를 도메인으로 복원하지 않는다.
        OrderJpaEntity entity = orderJpaRepository.findById(orderId).orElseThrow();
        mapper.apply(completed, entity);
        orderJpaRepository.saveAndFlush(entity);
        return true;
    }

    // ID로 주문 조회
    @Override
    public Optional<Order> findById(long orderId) {
        return orderJpaRepository.findById(orderId).map(mapper::toDomain);
    }

    // 비관적 쓰기 잠금으로 조회
    @Override
    public Optional<Order> findByIdForUpdate(long orderId) {
        return orderJpaRepository.findByIdForUpdate(orderId).map(mapper::toDomain);
    }
}
