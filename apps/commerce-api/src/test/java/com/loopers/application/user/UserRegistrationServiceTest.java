package com.loopers.application.user;

import com.loopers.application.point.PointFacade;
import com.loopers.domain.point.PointBalanceModel;
import com.loopers.infrastructure.point.PointBalanceJpaRepository;
import com.loopers.infrastructure.point.PointBalanceRepositoryImpl;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest
class UserRegistrationServiceTest {
    @Autowired private UserRegistrationService registration;
    @Autowired private PointFacade points;
    @Autowired private UserJpaRepository users;
    @Autowired private PointBalanceJpaRepository balances;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transaction;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoSpyBean private PointBalanceRepositoryImpl persistence;

    @AfterEach
    void clean() {
        reset(persistence);
        cleanUp.truncateAllTables();
    }

    @Test
    void createsUserAndZeroBalanceAndAllowsFirstCharge() {
        Long userId = registration.register().getId();
        transaction.executeWithoutResult(status -> {
            entityManager.flush();
            entityManager.clear();
            assertThat(users.findById(userId)).isPresent();
            assertThat(balances.findByUserId(userId).orElseThrow().getBalance()).isZero();
        });
        assertThat(points.charge(userId, 1_000L).balance()).isEqualTo(1_000L);
        assertThat(balances.count()).isEqualTo(1);
    }

    @Test
    void rollsBackBothRowsWhenFailureOccursAfterAccountInsertSql() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            assertThat(users.count()).isEqualTo(1);
            assertThat(balances.count()).isEqualTo(1);
            throw new IllegalStateException("failure after account insert");
        }).when(persistence).save(any(PointBalanceModel.class));

        assertThatThrownBy(registration::register).isInstanceOf(IllegalStateException.class);
        transaction.executeWithoutResult(status -> {
            entityManager.clear();
            assertThat(users.count()).isZero();
            assertThat(balances.count()).isZero();
        });
    }

    @Test
    void rejectsSecondAccountForSameUser() {
        Long userId = registration.register().getId();
        assertThatThrownBy(() -> balances.saveAndFlush(new PointBalanceModel(userId)))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(balances.count()).isEqualTo(1);
        assertThat(balances.findByUserId(userId).orElseThrow().getBalance()).isZero();
    }
}
