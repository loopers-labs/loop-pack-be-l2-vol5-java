package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PointServiceTest {

    private static final Long USER_ID = 1L;

    private FakePointRepository pointRepository;
    private PointService pointService;

    @BeforeEach
    void setUp() {
        pointRepository = new FakePointRepository();
        pointService = new PointService(pointRepository);
    }

    @DisplayName("충전한 적이 없으면 잔액은 0 원이고, 조회만으로는 행이 생기지 않는다.")
    @Test
    void returnsZeroBalance_withoutCreatingRow() {
        // act
        Point point = pointService.getPoint(USER_ID);

        // assert
        assertAll(
            () -> assertThat(point.getBalance()).isZero(),
            () -> assertThat(pointRepository.count()).isZero()
        );
    }

    @DisplayName("첫 충전 때 행이 생기고, 다음 충전은 잔액에 더해진다.")
    @Test
    void createsRowOnFirstCharge_andAccumulates() {
        // act
        pointService.charge(USER_ID, 10_000L);
        Point point = pointService.charge(USER_ID, 5_000L);

        // assert
        assertAll(
            () -> assertThat(point.getBalance()).isEqualTo(15_000L),
            () -> assertThat(pointService.getPoint(USER_ID).getBalance()).isEqualTo(15_000L),
            () -> assertThat(pointRepository.count()).isEqualTo(1)
        );
    }

    @DisplayName("첫 충전이 거절되면, 행이 생기지 않는다.")
    @Test
    void createsNoRow_whenFirstChargeIsRejected() {
        // act
        assertThrows(CoreException.class, () -> pointService.charge(USER_ID, 0L));

        // assert
        assertThat(pointRepository.count()).isZero();
    }
}
