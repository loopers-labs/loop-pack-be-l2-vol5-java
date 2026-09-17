package com.loopers.domain.point;

import com.loopers.domain.user.UserModel;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("PointService 는 포인트 충전과 잔액 조회를 DB 에 연결한다.")
@SpringBootTest
class PointServiceIntegrationTest {

    @Autowired
    private PointService pointService;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private PointJpaRepository pointJpaRepository;
    @Autowired
    private PointHistoryJpaRepository pointHistoryJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("충전")
    @Nested
    class Charge {
        @DisplayName("충전액을 잔액에 더해 저장하고 충전 이력을 남긴다.")
        @Test
        void savesIncreasedBalanceAndHistory() {
            UserModel user = userFixture.createUserWithPoint();

            PointChange change = pointService.charge(user.getId(), 10_000L);

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

            pointService.charge(user.getId(), 1_000L);
            pointService.charge(user.getId(), 2_500L);

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
            pointService.charge(user.getId(), 1_000L);

            assertThatThrownBy(() -> pointService.charge(user.getId(), 0L))
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

            assertThatThrownBy(() -> pointService.charge(user.getId(), 10_000L))
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
            pointService.charge(user.getId(), 4_200L);

            PointModel point = pointService.getPoint(user.getId());

            assertThat(point.getBalance()).isEqualTo(4_200L);
        }

        @DisplayName("충전한 적 없는 사용자의 잔액은 0 이다.")
        @Test
        void returnsZeroBalance() {
            UserModel user = userFixture.createUserWithPoint();

            PointModel point = pointService.getPoint(user.getId());

            assertThat(point.getBalance()).isZero();
        }

        @DisplayName("Point 가 없는 사용자의 조회는 불변식 위반으로 거절한다.")
        @Test
        void rejectsWhenPointIsNotInitialized() {
            UserModel user = userFixture.createUserWithoutPoint();

            assertThatThrownBy(() -> pointService.getPoint(user.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.POINT_NOT_INITIALIZED);
            assertThat(pointJpaRepository.findByUserId(user.getId())).isEmpty();
        }
    }
}
