package com.loopers.application.point;

import com.loopers.application.point.dto.PointInfo;
import com.loopers.domain.common.Money;
import com.loopers.domain.user.PointBalance;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
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
class PointFacadeTest {

    @Autowired
    private PointFacade pointFacade;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private User saveUserWithBalance(long amount) {
        User user = User.create();
        if (amount > 0) {
            user.chargePoint(new Money(amount));
        }
        return userRepository.save(user);
    }

    private PointBalance storedBalance(Long userId) {
        return userRepository.find(userId).orElseThrow().getPointBalance();
    }

    @DisplayName("포인트를 충전할 때,")
    @Nested
    class Charge {
        @DisplayName("충전 후 잔액을 반환하고, 잔액이 저장된다.")
        @Test
        void returnsChargedBalance_andPersists() {
            // arrange
            User user = saveUserWithBalance(5_000);

            // act
            PointInfo info = pointFacade.charge(user.getId(), 10_000);

            // assert
            assertThat(info.balance()).isEqualTo(15_000);
            assertThat(storedBalance(user.getId())).isEqualTo(new PointBalance(new Money(15_000)));
        }

        @DisplayName("존재하지 않는 사용자이면 NOT_FOUND 로 거절한다.")
        @Test
        void throwsNotFound_whenUserDoesNotExist() {
            // act
            CoreException exception = assertThrows(CoreException.class, () -> pointFacade.charge(999L, 1_000));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("포인트 잔액을 조회할 때,")
    @Nested
    class GetBalance {
        @DisplayName("저장된 잔액을 반환한다.")
        @Test
        void returnsStoredBalance() {
            // arrange
            User user = saveUserWithBalance(7_000);

            // act
            PointInfo info = pointFacade.getBalance(user.getId());

            // assert
            assertThat(info.balance()).isEqualTo(7_000);
        }
    }
}
