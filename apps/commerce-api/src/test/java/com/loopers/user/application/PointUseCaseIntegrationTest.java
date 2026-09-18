package com.loopers.user.application;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
@Transactional
class PointUseCaseIntegrationTest {
    @Autowired private PointUseCase useCase;
    @Autowired private EntityManager entityManager;

    @DisplayName("[R-POINT-01] 고객은 자신의 포인트를 충전할 수 있다.")
    @Nested class ChargeOwnPoint {
        @DisplayName("[상태 전이] 식별한 고객에게 1000을 충전하면 저장 잔액이 1000이다.")
        @Test void chargesAndSavesRequester() {
            User user = persist(new User());
            useCase.charge(user.getId(), 1_000L);
            entityManager.flush();
            entityManager.clear();
            assertThat(entityManager.find(User.class, user.getId()).getPoint().balance())
                .isEqualTo(1_000L);
        }
    }

    @DisplayName("[R-POINT-02] 고객은 자신의 저장된 포인트 잔액을 조회할 수 있다.")
    @Nested class ReadStoredBalance {
        @DisplayName("[동등 클래스 분할] 저장된 고객의 현재 잔액을 제공한다.")
        @Test void returnsStoredBalance() {
            User user = new User();
            user.charge(2_000L);
            persist(user);
            assertThat(useCase.getBalance(user.getId())).isEqualTo(2_000L);
        }
    }

    @DisplayName("[R-POINT-06] 충전 후 잔액을 저장하고 제공한다.")
    @Nested class ReturnChargedBalance {
        @DisplayName("[경계값 분석] 잔액 0에 10000을 충전하면 저장하고 10000을 제공한다.")
        @Test void returnsBalanceAfterCharge() {
            User user = persist(new User());
            assertThat(useCase.charge(user.getId(), 10_000L)).isEqualTo(10_000L);
            entityManager.flush();
            entityManager.clear();
            assertThat(entityManager.find(User.class, user.getId()).getPoint().balance())
                .isEqualTo(10_000L);
        }
    }

    @DisplayName("[R-POINT-08] 유효하지 않은 충전 요청은 거절하고 기존 잔액을 유지한다.")
    @Nested class KeepBalanceOnInvalidCharge {
        @DisplayName("[경계값 분석] 잔액 1000에 0을 충전하면 오류이고 저장 잔액을 유지한다.")
        @Test void rejectsZeroAndDoesNotSave() {
            User user = new User();
            user.charge(1_000L);
            persist(user);
            CoreException result = assertThrows(CoreException.class,
                () -> useCase.charge(user.getId(), 0L));
            entityManager.clear();
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_CHARGE_AMOUNT),
                () -> assertThat(entityManager.find(User.class, user.getId()).getPoint().balance())
                    .isEqualTo(1_000L)
            );
        }
    }

    @DisplayName("[P-POINT-01] 한 번도 충전하지 않은 고객의 잔액은 0이다.")
    @Nested class InitialZeroBalance {
        @DisplayName("[경계값 분석] 새 고객의 저장된 잔액을 조회하면 0이다.")
        @Test void returnsZeroForNewUser() {
            User user = persist(new User());
            assertThat(useCase.getBalance(user.getId())).isZero();
        }
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }
}
