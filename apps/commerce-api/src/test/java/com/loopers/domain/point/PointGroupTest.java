package com.loopers.domain.point;

import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PointGroupTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZonedDateTime CHARGED_AT = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, SEOUL);
    private static final ZonedDateTime EXPIRES_AT = ZonedDateTime.of(2031, 1, 1, 0, 0, 0, 0, SEOUL);
    private static final Period VALIDITY = Period.ofYears(5);

    private static PointGroup chargedGroup(long amount) {
        return PointGroup.charge(1L, Money.of(amount), CHARGED_AT, VALIDITY);
    }

    @DisplayName("충전으로 그룹을 만들 때, ")
    @Nested
    class Charge {

        @DisplayName("G-1 · PNT-02 10,000원을 충전하면 남은 금액은 10,000원이고, 만료 시각은 충전 시각 5년 뒤다.")
        @Test
        void createsGroupWithRemainingAndExpiry() {
            // act
            PointGroup group = chargedGroup(10_000);

            // assert
            assertThat(group.getRemaining()).isEqualTo(Money.of(10_000));
            assertThat(group.getExpiresAt()).isEqualTo(EXPIRES_AT);
        }

        @DisplayName("G-2 · PNT-02 0원은 충전할 수 없다.")
        @Test
        void rejectsZeroCharge() {
            // act & assert
            assertThatThrownBy(() -> chargedGroup(0))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("그룹의 포인트를 사용할 때, ")
    @Nested
    class Use {

        @DisplayName("G-3 · PNT-06 남은 3,000원에서 3,000원을 쓰면 남은 금액은 0원이다.")
        @Test
        void usesAllRemaining() {
            // arrange
            PointGroup group = chargedGroup(3_000);

            // act
            group.use(Money.of(3_000), CHARGED_AT.plusDays(1));

            // assert
            assertThat(group.getRemaining()).isEqualTo(Money.of(0));
        }

        @DisplayName("G-4 · PNT-06 남은 3,000원에서 3,001원을 쓰면 거절하고, 남은 금액은 3,000원 그대로다.")
        @Test
        void rejectsUsingMoreThanRemaining() {
            // arrange
            PointGroup group = chargedGroup(3_000);

            // act & assert
            assertThatThrownBy(() -> group.use(Money.of(3_001), CHARGED_AT.plusDays(1)))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(group.getRemaining()).isEqualTo(Money.of(3_000));
        }

        @DisplayName("G-5 · PNT-07 만료 1초 전에는 사용할 수 있다.")
        @Test
        void usesJustBeforeExpiry() {
            // arrange
            PointGroup group = chargedGroup(3_000);

            // act
            group.use(Money.of(1_000), EXPIRES_AT.minusSeconds(1));

            // assert
            assertThat(group.getRemaining()).isEqualTo(Money.of(2_000));
        }

        @DisplayName("G-6 · PNT-07 만료 시각 정각에는 사용할 수 없고, 남은 금액은 그대로다.")
        @Test
        void rejectsUsingAtExpiry() {
            // arrange
            PointGroup group = chargedGroup(3_000);

            // act & assert
            assertThatThrownBy(() -> group.use(Money.of(1_000), EXPIRES_AT))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(group.getRemaining()).isEqualTo(Money.of(3_000));
        }
    }

    @DisplayName("그룹을 만료 처리할 때, ")
    @Nested
    class Expire {

        @DisplayName("G-7 · PNT-08 만료된 그룹(남은 4,000원)은 남은 금액이 0원이 되고, 만료된 금액 4,000원을 돌려준다.")
        @Test
        void expiresRemaining() {
            // arrange
            PointGroup group = chargedGroup(4_000);

            // act
            Money expired = group.expire(EXPIRES_AT);

            // assert
            assertThat(expired).isEqualTo(Money.of(4_000));
            assertThat(group.getRemaining()).isEqualTo(Money.of(0));
        }

        @DisplayName("G-8 · PNT-08 만료 전인 그룹은 만료 처리할 수 없고, 남은 금액은 그대로다.")
        @Test
        void rejectsExpiringBeforeExpiry() {
            // arrange
            PointGroup group = chargedGroup(4_000);

            // act & assert
            assertThatThrownBy(() -> group.expire(EXPIRES_AT.minusSeconds(1)))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(group.getRemaining()).isEqualTo(Money.of(4_000));
        }

        @DisplayName("G-9 · PNT-08 이미 남은 금액이 0원인 그룹을 다시 만료 처리해도 변화가 없고, 만료된 금액은 0원이다.")
        @Test
        void expiringTwiceChangesNothing() {
            // arrange
            PointGroup group = chargedGroup(4_000);
            group.expire(EXPIRES_AT);

            // act
            Money expiredAgain = group.expire(EXPIRES_AT.plusDays(1));

            // assert
            assertThat(expiredAgain).isEqualTo(Money.of(0));
            assertThat(group.getRemaining()).isEqualTo(Money.of(0));
        }
    }
}
