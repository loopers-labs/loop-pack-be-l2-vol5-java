package com.loopers.infrastructure.persistence.pay.repository;

import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.infrastructure.persistence.pay.entity.PaymentAttemptEntityMapper;
import com.loopers.infrastructure.persistence.pay.entity.PaymentAttemptJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import java.util.Optional;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentAttemptRepositoryImpl implements PaymentAttemptRepository {
    private final EntityManager entityManager;
    private final PaymentAttemptEntityMapper mapper;

    @Override
    public PaymentAttempt insert(PaymentAttempt attempt) {
        PaymentAttemptJpaEntity entity = mapper.toNewEntity(attempt);
        entityManager.persist(entity);
        entityManager.flush();
        return mapper.toDomain(entity);
    }

    @Override
    public PaymentAttempt save(PaymentAttempt attempt) {
        PaymentAttemptJpaEntity entity = entityManager.find(PaymentAttemptJpaEntity.class, attempt.getAttemptKey());
        if (entity == null || mapper.version(entity) != attempt.getVersion()) {
            throw new OptimisticLockException("결제 시도가 변경되었으므로 최신 상태를 다시 확인해야 합니다.");
        }
        mapper.apply(attempt, entity);
        entityManager.flush();
        return mapper.toDomain(entity);
    }

    @Override
    public Optional<PaymentAttempt> findByKey(String attemptKey) {
        return Optional.ofNullable(entityManager.find(PaymentAttemptJpaEntity.class, PaymentAttempt.normalizeKey(attemptKey)))
                .map(mapper::toDomain);
    }

    @Override
    public Optional<PaymentAttempt> findByKeyForUpdate(String attemptKey) {
        return Optional.ofNullable(entityManager.find(PaymentAttemptJpaEntity.class,
                PaymentAttempt.normalizeKey(attemptKey), LockModeType.PESSIMISTIC_WRITE)).map(mapper::toDomain);
    }

    @Override
    public Optional<PaymentAttempt> findByOrderId(long orderId) {
        return entityManager.createQuery("select a from PaymentAttemptJpaEntity a where a.orderId = :orderId",
                PaymentAttemptJpaEntity.class).setParameter("orderId", orderId)
                .getResultStream().findFirst().map(mapper::toDomain);
    }

    @Override
    public List<PaymentAttempt> findByProviderOrderId(String providerOrderId) {
        return entityManager.createQuery("select a from PaymentAttemptJpaEntity a where a.providerOrderId = :providerOrderId",
                PaymentAttemptJpaEntity.class).setParameter("providerOrderId", providerOrderId)
            .getResultList().stream().map(mapper::toDomain).toList();
    }
}
