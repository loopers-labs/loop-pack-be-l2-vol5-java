package com.loopers.infrastructure.persistence.pay.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentScenario;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.support.test.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class PaymentAttemptRepositoryTest {
    @Autowired
    PaymentAttemptRepository repository;
    @Autowired
    EntityManager entityManager;
    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    @Transactional
    @DisplayName("실효 조건·관찰을 복원하고 오래된 객체가 최신 결과를 덮지 못한다")
    void preservesObservationAndRejectsStaleSave() {
        PaymentScenario scenario = PaymentScenario.resolve(PaymentScenario.Preset.DELAYED_SUCCESS,
                new PaymentScenario.Overrides(null, null, null, null, null, PaymentScenario.ResponseMode.DELAY, 17L, null, 11L));
        PaymentAttempt original = repository.insert(PaymentAttempt.create(900000001, UUID.randomUUID().toString(), 7000, scenario));
        entityManager.clear();
        PaymentAttempt loaded = repository.findByOrderId(original.getOrderId()).orElseThrow();
        assertThat(loaded.getScenario()).isEqualTo(scenario);
        PaymentAttempt saved = repository.save(loaded.startDispatch()
                .observe(loaded.getProviderOrderId(), 7000, "PG-" + UUID.randomUUID(), PaymentAttempt.Outcome.SUCCESS));
        entityManager.clear();
        assertThat(repository.findByKey(saved.getAttemptKey()).orElseThrow().getOutcome()).isEqualTo(PaymentAttempt.Outcome.SUCCESS);
        assertThat(saved.getVersion()).isGreaterThan(loaded.getVersion());
        assertThatThrownBy(() -> repository.save(loaded.startDispatch())).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("키는 전체 시도에서 유일하고 같은 주문에 새 키를 넣어도 두 번째 시도를 저장하지 못한다")
    void uniqueKeyAndOrder() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        String key = UUID.randomUUID().toString();
        long orderId = 900000002;
        PaymentScenario scenario = PaymentScenario.resolve(null, null);
        tx.executeWithoutResult(status -> repository.insert(PaymentAttempt.create(orderId, key, 7000, scenario)));
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> repository.insert(
                    PaymentAttempt.create(orderId + 1, key.toUpperCase(java.util.Locale.ROOT), 7000, scenario))))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> repository.insert(
                    PaymentAttempt.create(orderId, UUID.randomUUID().toString(), 7000, scenario))))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            tx.executeWithoutResult(status -> entityManager.createQuery("delete from PaymentAttemptJpaEntity a where a.attemptKey = :key")
                    .setParameter("key", key).executeUpdate());
        }
    }
}
