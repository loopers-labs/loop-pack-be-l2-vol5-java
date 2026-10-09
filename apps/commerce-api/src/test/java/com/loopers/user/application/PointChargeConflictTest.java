package com.loopers.user.application;

import com.loopers.support.fixture.CommerceFixture;
import com.loopers.support.fixture.TestEntities;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.Point;
import com.loopers.user.domain.User;
import com.loopers.user.infrastructure.PointRepositoryAdapter;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
class PointChargeConflictTest {

    @Autowired private PointUseCase useCase;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @MockitoSpyBean private PointRepositoryAdapter pointRepository;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        new CommerceFixture(entityManager, transactionManager).truncateRemainingTables();
    }

    @DisplayName("[R-POINT-06] 충전의 낙관적 충돌은 자동 재시도하지 않고 변경을 롤백한다.")
    @Test
    void propagatesConflictWithoutRetryAndRollsBackCharge() {
        User buyer = new CommerceFixture(entityManager, transactionManager).userWithPoint(10_000L);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            assertThat(TestEntities.pointBalance(entityManager, buyer.getId())).isEqualTo(12_000L);
            throw new OptimisticLockingFailureException("injected point version conflict");
        }).when(pointRepository).save(any(Point.class));

        OptimisticLockingFailureException failure = assertThrows(OptimisticLockingFailureException.class,
            () -> useCase.charge(buyer.getId(), 2_000L));

        assertThat(failure).hasMessage("injected point version conflict");
        verify(pointRepository, times(1)).save(any(Point.class));
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            assertThat(TestEntities.pointBalance(entityManager, buyer.getId())).isEqualTo(10_000L));
    }
}
