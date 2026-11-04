package com.loopers.infrastructure.persistence.pay.repository;

import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.repository.PaymentRecoveryTaskRepository;
import com.loopers.infrastructure.persistence.pay.entity.PaymentRecoveryTaskEntityMapper;
import com.loopers.infrastructure.persistence.pay.entity.PaymentRecoveryTaskJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentRecoveryTaskRepositoryImpl implements PaymentRecoveryTaskRepository {
    private final EntityManager entityManager;
    private final PaymentRecoveryTaskEntityMapper mapper;

    @Override
    public void insert(PaymentRecoveryTask task) {
        entityManager.persist(mapper.toNewEntity(task));
        entityManager.flush();
    }

    @Override
    public Optional<PaymentRecoveryTask> findByKey(String key) {
        return Optional.ofNullable(entityManager.find(PaymentRecoveryTaskJpaEntity.class, PaymentAttempt.normalizeKey(key)))
                .map(mapper::toDomain);
    }

    @Override
    public boolean claim(String key, String executionId, Instant now) {
        return update(entityManager.createQuery("""
                update PaymentRecoveryTaskJpaEntity t set t.state = 'RUNNING', t.executionId = :execution, t.updatedAt = :now
                where t.attemptKey = :key and t.state = 'READY' and t.executionId is null and t.usedRounds = 0
                """).setParameter("key", PaymentAttempt.normalizeKey(key))
                .setParameter("execution", PaymentAttempt.normalizeKey(executionId)).setParameter("now", now));
    }

    @Override
    public boolean consumeRound(String key, String executionId, int expectedUsed, Instant now) {
        if (expectedUsed < 0 || expectedUsed >= 4) {
            return false;
        }
        return update(ownedUpdate("t.usedRounds = t.usedRounds + 1, t.lastCheckedAt = :now, t.updatedAt = :now",
                key, executionId, expectedUsed).setParameter("now", now));
    }

    @Override
    public boolean handover(String key, PaymentRecoveryTask.State expectedState, String expectedExecutionId,
                            int expectedUsed, String nextExecutionId, Instant now) {
        if (expectedState != PaymentRecoveryTask.State.RUNNING && expectedState != PaymentRecoveryTask.State.MANUAL_REQUIRED) {
            return false;
        }
        String next = PaymentAttempt.normalizeKey(nextExecutionId);
        String previous = PaymentAttempt.normalizeKey(expectedExecutionId);
        if (next.equals(previous) || expectedUsed < 0 || expectedUsed > 4) {
            return false;
        }
        return update(entityManager.createQuery("""
                update PaymentRecoveryTaskJpaEntity t set t.state = 'RUNNING', t.executionId = :next, t.updatedAt = :now
                where t.attemptKey = :key and t.state = :state and t.executionId = :previous and t.usedRounds = :used
                """).setParameter("key", PaymentAttempt.normalizeKey(key)).setParameter("state", expectedState.name())
                .setParameter("previous", previous).setParameter("next", next)
                .setParameter("used", expectedUsed).setParameter("now", now));
    }

    @Override
    public boolean startManualLookup(String key, String executionId, int expectedUsed, Instant now) {
        return update(ownedUpdate("t.lastCheckedAt = :now, t.updatedAt = :now", key, executionId, expectedUsed)
            .setParameter("now", now));
    }

    @Override
    public boolean recordFailure(String key, String executionId, int expectedUsed,
                                 PaymentRecoveryTask.Reason reason, Instant now) {
        ensureReason(reason);
        return update(ownedUpdate("t.lastReason = :reason, t.updatedAt = :now", key, executionId, expectedUsed)
                .setParameter("reason", reason.name()).setParameter("now", now));
    }

    @Override
    public boolean requireManual(String key, String executionId, int expectedUsed,
                                 PaymentRecoveryTask.Reason reason, Instant now) {
        ensureReason(reason);
        return update(ownedUpdate("t.state = 'MANUAL_REQUIRED', t.lastReason = :reason, t.updatedAt = :now",
                key, executionId, expectedUsed).setParameter("reason", reason.name()).setParameter("now", now));
    }

    @Override
    public boolean markApplied(String key, Instant now) {
        return update(entityManager.createQuery("""
                update PaymentRecoveryTaskJpaEntity t set t.state = 'APPLIED', t.updatedAt = :now
                where t.attemptKey = :key and t.state in ('READY', 'RUNNING', 'MANUAL_REQUIRED')
                """).setParameter("key", PaymentAttempt.normalizeKey(key)).setParameter("now", now));
    }

    @Override
    public boolean markApplicationFailed(PaymentRecoveryTask.Snapshot expected, String markerExecutionId, Instant now) {
        if (expected.state() == null || expected.state() == PaymentRecoveryTask.State.APPLIED
                || expected.usedRounds() < 0 || expected.usedRounds() > 4) {
            return false;
        }
        boolean ready = expected.state() == PaymentRecoveryTask.State.READY;
        if (ready && (expected.executionId() != null || expected.usedRounds() != 0)) {
            return false;
        }
        String previous = ready ? null : PaymentAttempt.normalizeKey(expected.executionId());
        String next = ready ? PaymentAttempt.normalizeKey(markerExecutionId) : previous;
        return update(entityManager.createQuery("""
                update PaymentRecoveryTaskJpaEntity t set t.state = 'MANUAL_REQUIRED', t.executionId = :next,
                t.lastReason = 'APPLICATION_FAILED', t.updatedAt = :now
                where t.attemptKey = :key and t.state = :state and t.usedRounds = :used
                and (t.executionId = :previous or (t.executionId is null and :previous is null))
                """).setParameter("key", PaymentAttempt.normalizeKey(expected.attemptKey()))
                .setParameter("state", expected.state().name()).setParameter("used", expected.usedRounds())
                .setParameter("previous", previous).setParameter("next", next).setParameter("now", now));
    }

    private Query ownedUpdate(String assignments, String key, String executionId, int expectedUsed) {
        return entityManager.createQuery("update PaymentRecoveryTaskJpaEntity t set " + assignments
                        + " where t.attemptKey = :key and t.state = 'RUNNING'"
                        + " and t.executionId = :execution and t.usedRounds = :used")
                .setParameter("key", PaymentAttempt.normalizeKey(key))
                .setParameter("execution", PaymentAttempt.normalizeKey(executionId)).setParameter("used", expectedUsed);
    }

    private boolean update(Query query) {
        entityManager.flush();
        int changed = query.executeUpdate();
        entityManager.clear();
        return changed == 1;
    }

    private void ensureReason(PaymentRecoveryTask.Reason reason) {
        if (reason == null || reason == PaymentRecoveryTask.Reason.NONE) {
            throw new IllegalArgumentException("복구 확인 사유가 필요합니다.");
        }
    }
}
