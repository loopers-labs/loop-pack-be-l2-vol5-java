package com.loopers.infrastructure.persistence.pg.repository;

import com.loopers.domain.pg.model.PgTransaction;
import com.loopers.domain.pg.model.PgStatus;
import com.loopers.domain.pg.repository.PgTransactionRepository;
import com.loopers.infrastructure.persistence.pg.entity.PgTransactionEntityMapper;
import com.loopers.infrastructure.persistence.pg.entity.PgTransactionJpaEntity;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PgTransactionRepositoryImpl implements PgTransactionRepository {
    private final EntityManager entityManager;
    private final PgTransactionEntityMapper mapper;

    @Override
    public void insert(PgTransaction transaction) {
        entityManager.persist(mapper.toNewEntity(transaction));
    }

    @Override
    public Optional<PgTransaction> findByKey(String transactionKey) {
        return Optional.ofNullable(entityManager.find(PgTransactionJpaEntity.class, transactionKey)).map(mapper::toDomain);
    }

    @Override
    public List<PgTransaction> findDue(Instant now, int limit) {
        return entityManager.createQuery("""
                select t from PgTransactionJpaEntity t
                where t.status = 'PENDING' and t.dueAt <= :now
                order by t.dueAt, t.transactionKey
                """, PgTransactionJpaEntity.class).setParameter("now", now).setMaxResults(limit)
                .getResultList().stream().map(mapper::toDomain).toList();
    }

    @Override
    public boolean completeIfPending(PgTransaction completed, Instant now) {
        if (completed.status() == PgStatus.PENDING) {
            throw new IllegalArgumentException("종결할 결과가 필요합니다.");
        }
        entityManager.flush();
        int changed = entityManager.createQuery("""
                update PgTransactionJpaEntity t set t.status = :result
                where t.transactionKey = :key and t.status = 'PENDING'
                    and t.dueAt <= :now and t.scenario.finalStatus = :result
                """).setParameter("result", completed.status().name())
                .setParameter("key", completed.transactionKey()).setParameter("now", now).executeUpdate();
        entityManager.clear();
        return changed == 1;
    }
}
