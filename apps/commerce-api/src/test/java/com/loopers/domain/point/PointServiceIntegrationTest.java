package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class PointServiceIntegrationTest {

    @Autowired
    private PointService pointService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("포인트를 조회할 때, ")
    @Nested
    class GetBalance {
        @DisplayName("한 번도 충전한 적 없는 사용자는, 잔액 0을 반환한다.")
        @Test
        void returnsZero_whenNeverCharged() {
            // act
            Long result = pointService.getBalance(1L);

            // assert
            assertThat(result).isEqualTo(0L);
        }
    }

    @DisplayName("포인트를 충전할 때, ")
    @Nested
    class Charge {
        @DisplayName("처음 충전하면, 계정이 생성되며 충전한 금액이 반영된다.")
        @Test
        void createsAccount_whenFirstCharged() {
            // act
            Long result = pointService.charge(1L, 1_000L);

            // assert
            assertThat(result).isEqualTo(1_000L);
            assertThat(pointService.getBalance(1L)).isEqualTo(1_000L);
        }

        @DisplayName("여러 번 충전하면, 누적된 잔액이 반환된다.")
        @Test
        void accumulatesBalance_whenChargedMultipleTimes() {
            // arrange
            pointService.charge(1L, 1_000L);

            // act
            Long result = pointService.charge(1L, 2_000L);

            // assert
            assertThat(result).isEqualTo(3_000L);
        }
    }

    @DisplayName("포인트를 사용할 때, ")
    @Nested
    class Pay {
        @DisplayName("잔액 이하의 금액이면, 잔액에서 차감된다.")
        @Test
        void decreasesBalance_whenAmountIsWithinBalance() {
            // arrange
            pointService.charge(1L, 1_000L);

            // act
            pointService.pay(1L, 700L);

            // assert
            assertThat(pointService.getBalance(1L)).isEqualTo(300L);
        }

        @DisplayName("한 번도 충전한 적 없는 사용자가 사용을 요청하면, CONFLICT 예외가 발생한다.")
        @Test
        void throwsConflictException_whenNeverCharged() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> pointService.pay(1L, 100L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }
    }
}
