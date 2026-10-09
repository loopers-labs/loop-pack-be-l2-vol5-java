package com.loopers.infrastructure;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TransactionIsolationIntegrationTest {

    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    TransactionIsolationIntegrationTest(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @DisplayName("W3 1 · 애플리케이션 트랜잭션은 서버 기본값인 READ COMMITTED 로 돈다.")
    @Test
    void runsInReadCommitted() {
        Object isolation = transactionTemplate.execute(status -> entityManager
            .createNativeQuery("SELECT @@SESSION.transaction_isolation")
            .getSingleResult());

        assertThat(isolation).isEqualTo("READ-COMMITTED");
    }
}
