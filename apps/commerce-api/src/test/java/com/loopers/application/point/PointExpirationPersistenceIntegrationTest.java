package com.loopers.application.point;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.point.PointBalanceModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.point.PointBalanceJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PointExpirationPersistenceIntegrationTest {

    @Autowired
    private UserRegistrationService registration;
    @Autowired
    private PointFacade pointFacade;
    @Autowired
    private UserJpaRepository userJpaRepository;
    @Autowired
    private PointBalanceJpaRepository pointBalanceJpaRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void expiresRewardDuringBalanceLookupAndPersistsTheResultOnlyOnce() {
        UserModel user = registration.register();
        transactionTemplate.executeWithoutResult(status -> {
            PointBalanceModel balance = pointBalanceJpaRepository.findByUserId(user.getId()).orElseThrow();
            balance.charge(1_000L);
            balance.reward(500L, ZonedDateTime.now().minusYears(1).minusDays(1));
            pointBalanceJpaRepository.save(balance);
        });

        assertThat(pointFacade.getBalance(user.getId()).balance()).isEqualTo(1_000L);
        assertPersistedBalance(user.getId(), 1_000L);

        assertThat(pointFacade.getBalance(user.getId()).balance()).isEqualTo(1_000L);
        assertPersistedBalance(user.getId(), 1_000L);
    }

    private void assertPersistedBalance(Long userId, long expectedBalance) {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.flush();
            entityManager.clear();
            assertThat(pointBalanceJpaRepository.findByUserId(userId).orElseThrow().getBalance())
                .isEqualTo(expectedBalance);
        });
    }
}
