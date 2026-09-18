package com.loopers.application.point;

import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class PointFacadeIntegrationTest {

    @Autowired
    private PointFacade pointFacade;

    @Autowired
    private PointJpaRepository pointJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("포인트를 충전할 때,")
    @Nested
    class Charge {
        @DisplayName("요청자의 Point가 존재하면, 충전한 잔액을 저장하고 반환한다.")
        @Test
        void chargesPoint_whenPointExistsForUser() {
            // arrange
            User user = saveUser();
            Point point = pointJpaRepository.save(Point.create(user.getId()));

            // act
            PointInfo result = pointFacade.charge(point.getUserId(), 200L);

            // assert
            pointJpaRepository.flush();
            entityManager.clear();
            Point savedPoint = pointJpaRepository.findByUserId(point.getUserId()).orElseThrow();
            assertAll(
                () -> assertThat(result.balance()).isEqualTo(200L),
                () -> assertThat(savedPoint.getBalance().amount()).isEqualTo(200L)
            );
        }

        @DisplayName("충전액이 0이면, BAD_REQUEST 예외가 발생하고 저장된 잔액을 유지한다.")
        @Test
        void keepsBalance_whenChargeAmountIsZero() {
            // arrange
            User user = saveUser();
            Point point = pointJpaRepository.save(Point.create(user.getId()));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                pointFacade.charge(point.getUserId(), 0L);
            });

            // assert
            pointJpaRepository.flush();
            entityManager.clear();
            Point savedPoint = pointJpaRepository.findByUserId(point.getUserId()).orElseThrow();
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(savedPoint.getBalance().amount()).isZero()
            );
        }
    }

    @DisplayName("포인트 잔액을 조회할 때,")
    @Nested
    class GetBalance {
        @DisplayName("요청자의 Point가 존재하면, 저장된 잔액을 반환한다.")
        @Test
        void returnsSavedBalance_whenPointExistsForUser() {
            // arrange
            User user = saveUser();
            Point point = pointJpaRepository.save(Point.create(user.getId(), new PointBalance(300L)));

            // act
            PointInfo result = pointFacade.getBalance(point.getUserId());

            // assert
            assertThat(result.balance()).isEqualTo(300L);
        }
    }

    private User saveUser() {
        return userJpaRepository.save(User.create());
    }
}
