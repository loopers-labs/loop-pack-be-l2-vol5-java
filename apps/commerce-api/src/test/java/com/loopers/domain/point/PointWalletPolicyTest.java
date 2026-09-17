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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class PointWalletPolicyTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Period VALIDITY = Period.ofYears(5);
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, SEOUL);

    private final PointWalletPolicy policy = new PointWalletPolicy();

    /** 기준 시각(NOW)에 아직 유효한 그룹. */
    private static PointGroup usableGroup(long amount) {
        return PointGroup.charge(1L, Money.of(amount), ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, SEOUL), VALIDITY);
    }

    /** 기준 시각(NOW)에 이미 만료된 그룹. 2020-01-01 충전 → 2025-01-01 만료. */
    private static PointGroup expiredGroup(long amount) {
        return PointGroup.charge(1L, Money.of(amount), ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, SEOUL), VALIDITY);
    }

    /** 기준 시각(NOW)에 유효한 그룹. 충전 연도로 만료 시각을 정한다. 2025년 충전 → 2030-01-01 만료. */
    private static PointGroup groupChargedAt(long amount, int chargedYear) {
        return PointGroup.charge(1L, Money.of(amount), ZonedDateTime.of(chargedYear, 1, 1, 0, 0, 0, 0, SEOUL), VALIDITY);
    }

    @DisplayName("잔액을 계산할 때, ")
    @Nested
    class Balance {

        @DisplayName("W-1 · PNT-01 그룹이 없으면 잔액은 0원이다.")
        @Test
        void isZeroWithoutGroups() {
            // act
            Money balance = policy.balanceOf(List.of(), NOW);

            // assert
            assertThat(balance).isEqualTo(Money.of(0));
        }

        @DisplayName("W-2 · PNT-01 남은 3,000원 그룹과 남은 5,000원 그룹이 있으면 잔액은 8,000원이다.")
        @Test
        void sumsRemainingOfGroups() {
            // act
            Money balance = policy.balanceOf(List.of(usableGroup(3_000), usableGroup(5_000)), NOW);

            // assert
            assertThat(balance).isEqualTo(Money.of(8_000));
        }

        @DisplayName("W-3 · PNT-07 만료된 그룹(4,000원)은 빼고, 유효한 그룹(5,000원)만 더해 잔액은 5,000원이다.")
        @Test
        void excludesExpiredGroups() {
            // act
            Money balance = policy.balanceOf(List.of(expiredGroup(4_000), usableGroup(5_000)), NOW);

            // assert
            assertThat(balance).isEqualTo(Money.of(5_000));
        }
    }

    @DisplayName("충전할 수 있는지 확인할 때, ")
    @Nested
    class Chargeable {

        @DisplayName("W-8 · PNT-03 남은 금액이 (Long 최댓값 - 1)원인 그룹이 있으면 2원 충전은 거절한다.")
        @Test
        void rejectsChargeOverflowingBalance() {
            // arrange
            List<PointGroup> groups = List.of(usableGroup(Long.MAX_VALUE - 1));

            // act & assert
            assertThatThrownBy(() -> policy.checkChargeable(groups, Money.of(2), NOW))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
        }

        @DisplayName("W-8 · PNT-03 남은 금액이 (Long 최댓값 - 1)원인 그룹이 있어도 1원 충전은 가능하다.")
        @Test
        void allowsChargeUpToMaxBalance() {
            // arrange
            List<PointGroup> groups = List.of(usableGroup(Long.MAX_VALUE - 1));

            // act & assert
            assertThatCode(() -> policy.checkChargeable(groups, Money.of(1), NOW))
                .doesNotThrowAnyException();
        }
    }

    @DisplayName("결제할 수 있는지 확인할 때, ")
    @Nested
    class Payable {

        @DisplayName("PNT-04 남은 3,000원 그룹에서 7,000원을 낼 수 있는지 물으면 409이고, 남은 금액은 그대로다.")
        @Test
        void rejectsWhenBalanceIsShort() {
            // arrange
            PointGroup groupA = usableGroup(3_000);

            // act & assert
            assertThatThrownBy(() -> policy.checkPayable(List.of(groupA), Money.of(7_000), NOW))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(groupA.getRemaining()).isEqualTo(Money.of(3_000));
        }

        @DisplayName("PNT-04 남은 7,000원 그룹에서 7,000원을 낼 수 있는지 물으면 통과하고, 확인만 하므로 남은 금액은 7,000원 그대로다.")
        @Test
        void passesWithoutChangingGroups() {
            // arrange
            PointGroup groupA = usableGroup(7_000);

            // act & assert
            assertThatCode(() -> policy.checkPayable(List.of(groupA), Money.of(7_000), NOW))
                .doesNotThrowAnyException();
            assertThat(groupA.getRemaining()).isEqualTo(Money.of(7_000));
        }
    }

    @DisplayName("포인트로 결제할 때, ")
    @Nested
    class Pay {

        @DisplayName("W-4 · PNT-04 남은 10,000원 그룹에서 7,000원을 결제하면 남은 금액은 3,000원이고, 사용 내역은 그 그룹의 7,000원이다.")
        @Test
        void paysFromSingleGroup() {
            // arrange
            PointGroup groupA = usableGroup(10_000);

            // act
            List<PointUsage> usages = policy.pay(List.of(groupA), Money.of(7_000), NOW);

            // assert
            assertThat(groupA.getRemaining()).isEqualTo(Money.of(3_000));
            assertThat(usages)
                .extracting(PointUsage::group, PointUsage::amount)
                .containsExactly(tuple(groupA, Money.of(7_000)));
        }

        @DisplayName("W-5 · PNT-04 남은 3,000원 그룹에서 7,000원을 결제하면 거절하고, 남은 금액은 3,000원 그대로다.")
        @Test
        void rejectsPaymentOverBalance() {
            // arrange
            PointGroup groupA = usableGroup(3_000);

            // act & assert
            assertThatThrownBy(() -> policy.pay(List.of(groupA), Money.of(7_000), NOW))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(groupA.getRemaining()).isEqualTo(Money.of(3_000));
        }

        @DisplayName("W-5b · PNT-04 남은 7,000원 그룹에서 7,000원을 결제하면 성공하고, 남은 금액은 0원이다.")
        @Test
        void paysEntireBalance() {
            // arrange
            PointGroup groupA = usableGroup(7_000);

            // act
            List<PointUsage> usages = policy.pay(List.of(groupA), Money.of(7_000), NOW);

            // assert
            assertThat(groupA.getRemaining()).isEqualTo(Money.of(0));
            assertThat(usages)
                .extracting(PointUsage::group, PointUsage::amount)
                .containsExactly(tuple(groupA, Money.of(7_000)));
        }

        @DisplayName("W-6 · PNT-05 만료가 늦은 A(10,000원), 이른 B(5,000원) 순서로 넣고 12,000원을 결제하면 B부터 차감한다.")
        @Test
        void paysFromEarliestExpiringGroupFirst() {
            // arrange: 넣는 순서(A → B)와 만료 순서(B → A)를 일부러 반대로 둔다
            PointGroup groupA = groupChargedAt(10_000, 2025);  // 2030-01-01 만료
            PointGroup groupB = groupChargedAt(5_000, 2024);   // 2029-01-01 만료

            // act
            List<PointUsage> usages = policy.pay(List.of(groupA, groupB), Money.of(12_000), NOW);

            // assert
            assertThat(groupB.getRemaining()).isEqualTo(Money.of(0));
            assertThat(groupA.getRemaining()).isEqualTo(Money.of(3_000));
            assertThat(usages)
                .extracting(PointUsage::group, PointUsage::amount)
                .containsExactly(tuple(groupB, Money.of(5_000)), tuple(groupA, Money.of(7_000)));
        }

        @DisplayName("W-7 · PNT-07 만료된 A(4,000원)만 있을 때 1,000원을 결제하면 거절하고, A의 남은 금액은 4,000원 그대로다.")
        @Test
        void rejectsPaymentWhenOnlyExpiredGroups() {
            // arrange
            PointGroup groupA = expiredGroup(4_000);

            // act & assert
            assertThatThrownBy(() -> policy.pay(List.of(groupA), Money.of(1_000), NOW))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(groupA.getRemaining()).isEqualTo(Money.of(4_000));
        }

        @DisplayName("W-7b · PNT-07 만료된 A(4,000원)와 유효한 B(5,000원)에서 1,000원을 결제하면 A는 건너뛰고 B에서 차감한다.")
        @Test
        void skipsExpiredGroupAndPaysFromUsableGroup() {
            // arrange: 만료된 A가 만료 시각이 가장 빨라 정렬하면 맨 앞에 온다
            PointGroup groupA = expiredGroup(4_000);
            PointGroup groupB = usableGroup(5_000);

            // act
            List<PointUsage> usages = policy.pay(List.of(groupA, groupB), Money.of(1_000), NOW);

            // assert
            assertThat(groupB.getRemaining()).isEqualTo(Money.of(4_000));
            assertThat(groupA.getRemaining()).isEqualTo(Money.of(4_000));
            assertThat(usages)
                .extracting(PointUsage::group, PointUsage::amount)
                .containsExactly(tuple(groupB, Money.of(1_000)));
        }

        @DisplayName("W-9 · P-21 0원 결제는 거절하고, 남은 금액은 그대로다.")
        @Test
        void rejectsZeroPayment() {
            // arrange
            PointGroup groupA = usableGroup(10_000);

            // act & assert
            assertThatThrownBy(() -> policy.pay(List.of(groupA), Money.of(0), NOW))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(groupA.getRemaining()).isEqualTo(Money.of(10_000));
        }

        @DisplayName("W-10 · P-21 남은 금액이 0원인 A와 유효한 B(5,000원)에서 1,000원을 결제하면 A에는 0원 사용 내역을 남기지 않는다.")
        @Test
        void skipsEmptyGroupWithoutZeroUsage() {
            // arrange: A는 만료가 더 빨라 정렬하면 앞에 오고, 남은 금액을 모두 쓴 상태다
            PointGroup groupA = groupChargedAt(3_000, 2024);
            groupA.use(Money.of(3_000), NOW);
            PointGroup groupB = usableGroup(5_000);

            // act
            List<PointUsage> usages = policy.pay(List.of(groupA, groupB), Money.of(1_000), NOW);

            // assert
            assertThat(usages)
                .extracting(PointUsage::group, PointUsage::amount)
                .containsExactly(tuple(groupB, Money.of(1_000)));
        }
    }
}
