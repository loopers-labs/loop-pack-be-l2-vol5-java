package com.loopers.domain.point;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.domain.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserPointTest {

    private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");

    @DisplayName("개설하면 잔액이 0원이고 원장이 비어 있다.")
    @Test
    void opensWithZeroBalance() {
        UserPoint userPoint = UserPoint.open(1L);

        assertThat(userPoint.getBalance()).isEqualTo(Money.ZERO);
        assertThat(userPoint.pullNewTransactions()).isEmpty();
    }

    @Nested
    @DisplayName("충전하면")
    class Charge {
        @DisplayName("잔액이 늘고 원장에 CHARGE 한 줄이 남는다.")
        @Test
        void increasesBalanceAndAppendsLedger() {
            UserPoint userPoint = UserPoint.open(1L);

            userPoint.charge(ChargeAmount.of(10_000), NOW);

            assertThat(userPoint.getBalance()).isEqualTo(Money.of(10_000));
            List<PointTransaction> ledger = userPoint.pullNewTransactions();
            assertThat(ledger).hasSize(1);
            assertThat(ledger.get(0).type()).isEqualTo(TransactionType.CHARGE);
            assertThat(ledger.get(0).amount()).isEqualTo(Money.of(10_000));
            assertThat(ledger.get(0).balanceAfter()).isEqualTo(Money.of(10_000));
            assertThat(ledger.get(0).occurredAt()).isEqualTo(NOW);
        }

        @DisplayName("여러 번 충전하면 balanceAfter 가 그때그때의 잔액을 따라간다.")
        @Test
        void balanceAfterTracksRunningBalance() {
            UserPoint userPoint = UserPoint.open(1L);

            userPoint.charge(ChargeAmount.of(1_000), NOW);
            userPoint.charge(ChargeAmount.of(2_000), NOW);

            assertThat(userPoint.pullNewTransactions())
                .extracting(PointTransaction::balanceAfter)
                .containsExactly(Money.of(1_000), Money.of(3_000));
            assertThat(userPoint.getBalance()).isEqualTo(Money.of(3_000));
        }

        @DisplayName("잔액은 언제나 원장에 쌓인 금액의 합과 같다.")
        @Test
        void balanceEqualsSumOfLedger() {
            UserPoint userPoint = UserPoint.open(1L);

            userPoint.charge(ChargeAmount.of(300), NOW);
            userPoint.charge(ChargeAmount.of(700), NOW);
            userPoint.charge(ChargeAmount.of(1), NOW);

            long sum = userPoint.pullNewTransactions().stream()
                .mapToLong(transaction -> transaction.amount().amount())
                .sum();
            assertThat(userPoint.getBalance().amount()).isEqualTo(sum);
        }
    }

    @DisplayName("합산이 표현 범위를 넘으면 상태 충돌로 거절하고, 잔액과 원장은 그대로다.")
    @Test
    void rejectsOverflowAndKeepsExistingState() {
        UserPoint userPoint = UserPoint.restore(1L, Money.of(Long.MAX_VALUE - 10));

        assertThatThrownBy(() -> userPoint.charge(ChargeAmount.of(11), NOW))
            .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.POINT_BALANCE_EXCEEDED);

        assertThat(userPoint.getBalance()).isEqualTo(Money.of(Long.MAX_VALUE - 10));
        assertThat(userPoint.pullNewTransactions()).isEmpty();
    }

    @DisplayName("원장 줄은 한 번 꺼내면 비워진다. 같은 줄이 두 번 저장되지 않게 하기 위해서다.")
    @Test
    void pullClearsPendingTransactions() {
        UserPoint userPoint = UserPoint.open(1L);
        userPoint.charge(ChargeAmount.of(100), NOW);

        assertThat(userPoint.pullNewTransactions()).hasSize(1);
        assertThat(userPoint.pullNewTransactions()).isEmpty();
    }

    @DisplayName("충전액은 0 이하로 만들 수 없다.")
    @Test
    void chargeAmountCannotBeNonPositive() {
        assertThatThrownBy(() -> ChargeAmount.of(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChargeAmount.of(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("ORDER-014 · 포인트 사용")
    class Use {
        @DisplayName("잔액만큼은 사용할 수 있고, 사용 후 잔액이 줄어든다")
        @Test
        void uses() {
            UserPoint userPoint = UserPoint.restore(1L, Money.of(5_000));

            userPoint.use(Money.of(3_000), NOW);

            assertThat(userPoint.getBalance()).isEqualTo(Money.of(2_000));
        }

        @DisplayName("사용도 원장에 한 줄을 남긴다 — 잔액이 바뀌는 모든 순간에 기록이 함께 생긴다")
        @Test
        void writesLedgerLine() {
            UserPoint userPoint = UserPoint.restore(1L, Money.of(5_000));

            userPoint.use(Money.of(3_000), NOW);

            assertThat(userPoint.pullNewTransactions())
                .singleElement()
                .satisfies(transaction -> {
                    assertThat(transaction.type()).isEqualTo(TransactionType.USE);
                    assertThat(transaction.amount()).isEqualTo(Money.of(3_000));
                    assertThat(transaction.balanceAfter()).isEqualTo(Money.of(2_000));
                });
        }

        @DisplayName("잔액 전부를 사용하면 0원이 된다. 0원은 정상 상태다")
        @Test
        void allowsSpendingAll() {
            UserPoint userPoint = UserPoint.restore(1L, Money.of(3_000));

            userPoint.use(Money.of(3_000), NOW);

            assertThat(userPoint.getBalance()).isEqualTo(Money.ZERO);
        }

        @DisplayName("잔액보다 많이 사용할 수 없다")
        @Test
        void rejectsOverspending() {
            UserPoint userPoint = UserPoint.restore(1L, Money.of(3_000));

            assertThatThrownBy(() -> userPoint.use(Money.of(3_001), NOW))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.INSUFFICIENT_POINT);
        }

        @DisplayName("거절된 사용은 잔액도 원장도 건드리지 않는다")
        @Test
        void rejectedUseChangesNothing() {
            UserPoint userPoint = UserPoint.restore(1L, Money.of(3_000));

            assertThatThrownBy(() -> userPoint.use(Money.of(5_000), NOW))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.INSUFFICIENT_POINT);

            assertThat(userPoint.getBalance()).isEqualTo(Money.of(3_000));
            assertThat(userPoint.pullNewTransactions()).isEmpty();
        }

        @DisplayName("0원 사용은 도달하지 않는다 — 결제액은 양수여야 한다")
        @Test
        void rejectsZeroUse() {
            UserPoint userPoint = UserPoint.restore(1L, Money.of(3_000));

            assertThatThrownBy(() -> userPoint.use(Money.ZERO, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
