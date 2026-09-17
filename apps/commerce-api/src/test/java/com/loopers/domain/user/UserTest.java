package com.loopers.domain.user;

import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserTest {

    private User userWithBalance(long balance) {
        User user = new User("user1");
        if (balance > 0) {
            user.charge(balance);
        }
        return user;
    }

    @DisplayName("사용자를 생성할 때, ")
    @Nested
    class Create {

        @DisplayName("포인트 잔액은 null이 아닌 0으로 시작한다. (P-5, PNT-002)")
        @Test
        void startsWithZeroBalance() {
            // act
            User user = new User("user1");

            // assert
            assertThat(user.getBalance()).isZero();
        }
    }

    @DisplayName("포인트를 충전할 때, ")
    @Nested
    class Charge {

        @DisplayName("양의 정수를 충전하면, 잔액에 더해진다. (PNT-001)")
        @Test
        void addsAmountToBalance_whenAmountIsPositive() {
            // arrange
            User user = userWithBalance(0L);

            // act
            user.charge(1_000L);

            // assert
            assertThat(user.getBalance()).isEqualTo(1_000L);
        }

        @DisplayName("충전액이 0 이하이거나 없으면, INVALID_VALUE 예외가 발생하고 잔액이 유지된다. (PNT-001, PNT-003)")
        @ParameterizedTest
        @NullSource
        @ValueSource(longs = {0L, -1_000L})
        void throwsInvalidValue_whenAmountIsNotPositive(Long amount) {
            // arrange
            User user = userWithBalance(5_000L);

            // act
            DomainException result = assertThrows(DomainException.class, () -> user.charge(amount));

            // assert
            assertAll(
                () -> assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE),
                () -> assertThat(user.getBalance()).isEqualTo(5_000L)
            );
        }

        @DisplayName("충전 후 잔액이 표현 범위를 넘으면, INVALID_VALUE 예외가 발생하고 잔액이 유지된다. (PNT-002)")
        @Test
        void throwsInvalidValue_whenBalanceOverflows() {
            // arrange
            User user = userWithBalance(Long.MAX_VALUE);

            // act
            DomainException result = assertThrows(DomainException.class, () -> user.charge(1L));

            // assert
            assertAll(
                () -> assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE),
                () -> assertThat(user.getBalance()).isEqualTo(Long.MAX_VALUE)
            );
        }
    }

    @DisplayName("포인트를 사용할 때, ")
    @Nested
    class Use {

        @DisplayName("잔액 이하 금액을 사용하면, 잔액에서 차감된다.")
        @Test
        void subtractsAmount_whenBalanceIsEnough() {
            // arrange
            User user = userWithBalance(10_000L);

            // act
            user.use(7_000L);

            // assert
            assertThat(user.getBalance()).isEqualTo(3_000L);
        }

        @DisplayName("잔액과 같은 금액을 사용하면, 잔액이 0이 된다. (PNT-002)")
        @Test
        void allowsZeroBalance_whenAmountEqualsBalance() {
            // arrange
            User user = userWithBalance(7_000L);

            // act
            user.use(7_000L);

            // assert
            assertThat(user.getBalance()).isZero();
        }

        @DisplayName("잔액보다 많은 금액을 사용하면, CONFLICT 예외가 발생하고 잔액이 유지된다. (ORD-004)")
        @Test
        void throwsConflict_whenBalanceIsNotEnough() {
            // arrange
            User user = userWithBalance(6_000L);

            // act
            DomainException result = assertThrows(DomainException.class, () -> user.use(7_000L));

            // assert
            assertAll(
                () -> assertThat(result.getType()).isEqualTo(DomainErrorType.CONFLICT),
                () -> assertThat(user.getBalance()).isEqualTo(6_000L)
            );
        }

        @DisplayName("음수 금액을 사용하면, INVALID_VALUE 예외가 발생하고 잔액이 유지된다.")
        @Test
        void throwsInvalidValue_whenAmountIsNegative() {
            // arrange
            User user = userWithBalance(6_000L);

            // act
            DomainException result = assertThrows(DomainException.class, () -> user.use(-1L));

            // assert
            assertAll(
                () -> assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE),
                () -> assertThat(user.getBalance()).isEqualTo(6_000L)
            );
        }
    }
}
