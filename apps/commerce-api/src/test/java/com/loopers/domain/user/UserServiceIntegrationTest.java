package com.loopers.domain.user;

import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class UserServiceIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("포인트를 충전할 때,")
    @Nested
    class ChargePoint {
        @DisplayName("존재하는 사용자 ID를 주면, 잔액이 늘어난 사용자를 반환한다.")
        @Test
        void increasesBalance_whenUserExists() {
            // arrange
            UserModel user = userJpaRepository.save(new UserModel());

            // act
            UserModel result = userService.chargePoint(user.getId(), 1000L);

            // assert
            assertThat(result.getPoint().getBalance()).isEqualTo(1000L);
            assertThat(userJpaRepository.findById(user.getId()).orElseThrow().getPoint().getBalance())
                .isEqualTo(1000L);
        }

        @DisplayName("존재하지 않는 사용자 ID를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenUserDoesNotExist() {
            // arrange
            Long invalidId = 999L;

            // act
            CoreException result = assertThrows(CoreException.class, () -> userService.chargePoint(invalidId, 1000L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("같은 사용자에게 동시에 두 번 충전 요청이 오면, 둘 다 유실 없이 반영된다.")
        @Test
        void reflectsBothCharges_whenConcurrentRequestsHappen() throws InterruptedException {
            // arrange
            UserModel user = userJpaRepository.save(new UserModel());
            int threadCount = 2;
            long amountEach = 1000L;
            ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            // act
            for (int i = 0; i < threadCount; i++) {
                executorService.submit(() -> {
                    try {
                        userService.chargePoint(user.getId(), amountEach);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await(5, TimeUnit.SECONDS);
            executorService.shutdown();

            // assert
            long finalBalance = userJpaRepository.findById(user.getId()).orElseThrow().getPoint().getBalance();
            assertThat(finalBalance).isEqualTo(amountEach * threadCount);
        }
    }

    @DisplayName("포인트 잔액을 조회할 때,")
    @Nested
    class GetBalance {
        @DisplayName("존재하는 사용자 ID를 주면, 잔액을 반환한다.")
        @Test
        void returnsBalance_whenUserExists() {
            // arrange
            UserModel user = userJpaRepository.save(new UserModel());
            userService.chargePoint(user.getId(), 500L);

            // act
            long balance = userService.getBalance(user.getId());

            // assert
            assertThat(balance).isEqualTo(500L);
        }

        @DisplayName("존재하지 않는 사용자 ID를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenUserDoesNotExist() {
            // arrange
            Long invalidId = 999L;

            // act
            CoreException result = assertThrows(CoreException.class, () -> userService.getBalance(invalidId));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
