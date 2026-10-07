package com.loopers.domain.point;

import com.loopers.application.point.PointFacade;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.LockProbe;
import com.loopers.fixture.UserFixture;
import com.loopers.infrastructure.point.PointHistoryJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("PointFacade 는 포인트 충전과 잔액 조회를 DB 에 연결한다.")
@SpringBootTest
class PointFacadeIntegrationTest {

    @Autowired
    private PointFacade pointFacade;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private PointJpaRepository pointJpaRepository;
    @Autowired
    private PointHistoryJpaRepository pointHistoryJpaRepository;
    @Autowired
    private PointRepository pointRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private LockProbe lockProbe;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    /** LockProbe 확인 스레드 종료를 확인하지 못했으면 살아 있는 트랜잭션이 남았을 수 있어 TRUNCATE 를 보류하고 실패시킨다. */
    @AfterEach
    void tearDown() {
        if (!lockProbe.allThreadsTerminated()) {
            throw new IllegalStateException("LockProbe 확인 스레드 종료 미확인: TRUNCATE 를 보류했다");
        }
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("충전")
    @Nested
    class Charge {
        @DisplayName("충전액을 잔액에 더해 저장하고 충전 이력을 남긴다.")
        @Test
        void savesIncreasedBalanceAndHistory() {
            UserModel user = userFixture.createUserWithPoint();

            PointChange change = pointFacade.charge(user.getId(), 10_000L);

            PointModel saved = pointJpaRepository.findByUserId(user.getId()).orElseThrow();
            List<PointHistoryModel> histories = pointHistoryJpaRepository.findAll();
            assertAll(
                () -> assertThat(change.afterBalance()).isEqualTo(10_000L),
                () -> assertThat(saved.getBalance()).isEqualTo(10_000L),
                () -> assertThat(histories).hasSize(1),
                () -> assertThat(histories.get(0).getPointId()).isEqualTo(saved.getId()),
                () -> assertThat(histories.get(0).getBeforeBalance()).isZero(),
                () -> assertThat(histories.get(0).getAfterBalance()).isEqualTo(10_000L),
                () -> assertThat(histories.get(0).getCause()).isEqualTo(PointChangeCause.CHARGE),
                () -> assertThat(histories.get(0).getOrderId()).isNull()
            );
        }

        @DisplayName("연속 충전은 저장된 잔액에 누적된다.")
        @Test
        void accumulatesBalance() {
            UserModel user = userFixture.createUserWithPoint();

            pointFacade.charge(user.getId(), 1_000L);
            pointFacade.charge(user.getId(), 2_500L);

            PointModel saved = pointJpaRepository.findByUserId(user.getId()).orElseThrow();
            assertAll(
                () -> assertThat(saved.getBalance()).isEqualTo(3_500L),
                () -> assertThat(pointHistoryJpaRepository.findAll()).hasSize(2)
            );
        }

        @DisplayName("충전액 0 을 거절하고 저장된 잔액과 이력을 유지한다.")
        @Test
        void keepsStoredStateOnInvalidAmount() {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 1_000L);

            assertThatThrownBy(() -> pointFacade.charge(user.getId(), 0L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_POINT_AMOUNT);

            PointModel saved = pointJpaRepository.findByUserId(user.getId()).orElseThrow();
            assertAll(
                () -> assertThat(saved.getBalance()).isEqualTo(1_000L),
                () -> assertThat(pointHistoryJpaRepository.findAll()).hasSize(1)
            );
        }

        @DisplayName("Point 가 없는 사용자의 충전은 새 Point 를 만들지 않고 불변식 위반으로 거절한다.")
        @Test
        void rejectsWhenPointIsNotInitialized() {
            UserModel user = userFixture.createUserWithoutPoint();

            assertThatThrownBy(() -> pointFacade.charge(user.getId(), 10_000L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.POINT_NOT_INITIALIZED);

            assertAll(
                () -> assertThat(pointJpaRepository.findByUserId(user.getId())).isEmpty(),
                () -> assertThat(pointHistoryJpaRepository.findAll()).isEmpty()
            );
        }
    }

    @DisplayName("잔액 조회")
    @Nested
    class GetPoint {
        @DisplayName("저장된 잔액을 반환한다.")
        @Test
        void returnsStoredBalance() {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 4_200L);

            PointModel point = pointFacade.getPoint(user.getId());

            assertThat(point.getBalance()).isEqualTo(4_200L);
        }

        @DisplayName("충전한 적 없는 사용자의 잔액은 0 이다.")
        @Test
        void returnsZeroBalance() {
            UserModel user = userFixture.createUserWithPoint();

            PointModel point = pointFacade.getPoint(user.getId());

            assertThat(point.getBalance()).isZero();
        }

        @DisplayName("Point 가 없는 사용자의 조회는 불변식 위반으로 거절한다.")
        @Test
        void rejectsWhenPointIsNotInitialized() {
            UserModel user = userFixture.createUserWithoutPoint();

            assertThatThrownBy(() -> pointFacade.getPoint(user.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.POINT_NOT_INITIALIZED);
            assertThat(pointJpaRepository.findByUserId(user.getId())).isEmpty();
        }
    }

    @DisplayName("Point 잠금 범위")
    @Nested
    class PointLock {
        private static final String LOCK_POINT = "SELECT id FROM point WHERE id = ? FOR UPDATE NOWAIT";

        @DisplayName("변경용 조회는 사용자의 Point 행을 트랜잭션이 끝날 때까지 잠근다.")
        @Test
        void locksPointRowForUpdate() {
            UserModel user = userFixture.createUserWithPoint();
            Long pointId = pointJpaRepository.findByUserId(user.getId()).orElseThrow().getId();

            LockProbe.Result whileLocked = transactionTemplate.execute(status -> {
                PointModel point = pointRepository.findByUserIdForUpdate(user.getId()).orElseThrow();
                assertThat(point.getId()).isEqualTo(pointId);
                return lockProbe.probe(LOCK_POINT, pointId);
            });

            assertAll(
                () -> assertThat(whileLocked).isEqualTo(LockProbe.Result.LOCKED),
                () -> assertThat(lockProbe.probe(LOCK_POINT, pointId)).as("트랜잭션 종료 후")
                    .isEqualTo(LockProbe.Result.ACQUIRED)
            );
        }

        @DisplayName("잔액 조회용 일반 조회는 Point 행을 잠그지 않는다.")
        @Test
        void keepsBalanceReadUnlocked() {
            UserModel user = userFixture.createUserWithPoint();
            Long pointId = pointJpaRepository.findByUserId(user.getId()).orElseThrow().getId();

            LockProbe.Result duringRead = transactionTemplate.execute(status -> {
                pointRepository.findByUserId(user.getId()).orElseThrow();
                return lockProbe.probe(LOCK_POINT, pointId);
            });

            assertThat(duringRead).isEqualTo(LockProbe.Result.ACQUIRED);
        }

        @DisplayName("Point 가 없는 사용자의 변경용 조회는 비어 있다.")
        @Test
        void returnsEmptyWithoutPoint() {
            UserModel user = userFixture.createUserWithoutPoint();

            Optional<PointModel> found = transactionTemplate.execute(
                status -> pointRepository.findByUserIdForUpdate(user.getId()));

            assertThat(found).isEmpty();
        }
    }
}
