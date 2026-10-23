package com.loopers.infrastructure.persistence.pg;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.domain.pg.model.PgScenario;
import com.loopers.domain.pg.model.PgStatus;
import com.loopers.domain.pg.model.PgTransaction;
import com.loopers.domain.pg.repository.PgTransactionRepository;
import com.loopers.support.PgDatabaseTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class PgTransactionRepositoryTest extends PgDatabaseTest {

    @Autowired
    PgTransactionRepository repository;
    @Autowired
    EntityManager entityManager;

    @Test
    @Transactional
    @DisplayName("같은 주문의 생성 두 건을 따로 저장하고 예정 시각과 실효 조건을 복원한다")
    void preservesTransactionsAndEffectiveScenario() {
        // arrange
        Instant now = Instant.parse("2026-10-21T00:00:00Z");
        PgScenario scenario = PgScenario.resolve(PgScenario.Preset.LOST_FAILURE, null);
        PgTransaction first = PgTransaction.create("LP-ORD-0000000042", 7000,
                "http://127.0.0.1:8080/api/v1/payments/callback", scenario, now);
        PgTransaction second = PgTransaction.create(first.orderId(), 7000, first.callbackUrl(), scenario, now);

        // act
        repository.insert(first);
        repository.insert(second);
        entityManager.flush();
        entityManager.clear();

        // assert
        PgTransaction restored = repository.findByKey(first.transactionKey()).orElseThrow();
        assertThat(restored).isEqualTo(first);
        assertThat(restored.status()).isEqualTo(PgStatus.PENDING);
        assertThat(restored.dueAt()).isEqualTo(now.plusSeconds(1));
        assertThat(repository.findByKey(second.transactionKey())).contains(second);
        assertThat(first.transactionKey()).isNotEqualTo(second.transactionKey());
    }
}
