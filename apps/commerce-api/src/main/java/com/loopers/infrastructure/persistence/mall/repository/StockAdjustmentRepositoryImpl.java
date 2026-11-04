package com.loopers.infrastructure.persistence.mall.repository;

import com.loopers.domain.mall.model.StockAdjustment;
import com.loopers.domain.mall.repository.StockAdjustmentRepository;
import com.loopers.infrastructure.persistence.mall.entity.StockAdjustmentEntityMapper;
import com.loopers.infrastructure.persistence.mall.entity.StockAdjustmentJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class StockAdjustmentRepositoryImpl implements StockAdjustmentRepository {
    private final EntityManager entityManager;
    private final StockAdjustmentEntityMapper mapper;

    @Override
    public StockAdjustment insert(StockAdjustment adjustment) {
        StockAdjustmentJpaEntity entity = mapper.toNewEntity(adjustment);
        entityManager.persist(entity);
        entityManager.flush();
        return mapper.toDomain(entity);
    }

    @Override
    public StockAdjustment save(StockAdjustment adjustment) {
        StockAdjustmentJpaEntity entity = entityManager.find(StockAdjustmentJpaEntity.class, adjustment.getOrderId());
        if (entity == null || mapper.version(entity) != adjustment.getVersion()) {
            throw new OptimisticLockException("재고 처리 기록이 변경되었으므로 최신 상태를 확인해야 합니다.");
        }
        mapper.apply(adjustment, entity);
        entityManager.flush();
        return mapper.toDomain(entity);
    }

    @Override
    public Optional<StockAdjustment> findByOrderId(long orderId) {
        return entityManager.createQuery("""
                select distinct a from StockAdjustmentJpaEntity a left join fetch a.items where a.orderId = :orderId
                """, StockAdjustmentJpaEntity.class).setParameter("orderId", orderId)
                .getResultStream().findFirst().map(mapper::toDomain);
    }
}
