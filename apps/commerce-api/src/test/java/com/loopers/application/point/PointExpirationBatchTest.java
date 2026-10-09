package com.loopers.application.point;

import com.loopers.domain.point.PointBalanceModel;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.infrastructure.point.PointBalanceJpaRepository;
import com.loopers.infrastructure.point.PointBalanceRepositoryImpl;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest
class PointExpirationBatchTest {
    @Autowired private UserRegistrationService registration;
    @Autowired private PointExpirationBatch batch;
    @Autowired private PointBalanceJpaRepository balances;
    @Autowired private TransactionTemplate transaction;
    @Autowired private EntityManager entityManager;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoSpyBean private PointBalanceRepositoryImpl persistence;

    @AfterEach
    void clean() {
        reset(persistence);
        cleanUp.truncateAllTables();
    }

    @Test
    void expiresUnusedRewardsWithoutRequestsAndRepeatedRunDoesNotDuplicateRecords() {
        Long userId = prepare();
        assertThat(batch.run()).isEqualTo(new PointExpirationBatch.Result(1, 0));
        verify(userId, 1_000L);
        assertThat(expirationCount()).isEqualTo(1);
        batch.run();
        verify(userId, 1_000L);
        assertThat(expirationCount()).isEqualTo(1);
    }

    @Test
    void rollsBackFlushedChangesForFailedUserAndContinuesWithNextUser() {
        Long firstUserId = prepare();
        Long secondUserId = prepare();
        AtomicBoolean failFirst = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object saved = invocation.callRealMethod();
            if (failFirst.getAndSet(false)) {
                entityManager.flush();
                assertThat(((Number) entityManager.createNativeQuery(
                    "select balance from point_balance where user_id = :userId").setParameter("userId", firstUserId).getSingleResult()).longValue())
                    .isEqualTo(1_000L);
                throw new IllegalStateException("failure after expiration SQL");
            }
            return saved;
        }).when(persistence).save(any(PointBalanceModel.class));

        assertThat(batch.run()).isEqualTo(new PointExpirationBatch.Result(1, 1));
        verify(firstUserId, 1_300L);
        verify(secondUserId, 1_000L);
        assertThat(expirationCount()).isEqualTo(1);
        reset(persistence);
        batch.run();
        verify(firstUserId, 1_000L);
        assertThat(expirationCount()).isEqualTo(2);
    }

    @Test
    void scansBeyondOnePageWithoutSkippingUsers() {
        Long lastUserId = null;
        for (int index = 0; index < 101; index++) {
            lastUserId = prepare();
        }
        assertThat(batch.run()).isEqualTo(new PointExpirationBatch.Result(101, 0));
        verify(lastUserId, 1_000L);
        assertThat(expirationCount()).isEqualTo(101);
    }

    private Long prepare() {
        Long userId = registration.register().getId();
        transaction.executeWithoutResult(status -> {
            PointBalanceModel balance = balances.findByUserId(userId).orElseThrow();
            ZonedDateTime grantedAt = ZonedDateTime.now().minusYears(1).minusDays(2);
            balance.charge(1_000L, grantedAt);
            balance.reward(500L, grantedAt);
            balance.use(200L, grantedAt.plusMinutes(1));
            balances.saveAndFlush(balance);
        });
        return userId;
    }

    private void verify(Long userId, long expected) {
        transaction.executeWithoutResult(status -> {
            entityManager.flush();
            entityManager.clear();
            assertThat(balances.findByUserId(userId).orElseThrow().getBalance()).isEqualTo(expected);
        });
    }

    private long expirationCount() {
        return transaction.execute(status -> ((Number) entityManager.createNativeQuery(
            "select count(*) from point_usage where type = 'EXPIRATION'").getSingleResult()).longValue());
    }
}
