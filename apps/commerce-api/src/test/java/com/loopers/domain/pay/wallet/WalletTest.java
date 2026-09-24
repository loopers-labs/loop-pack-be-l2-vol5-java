package com.loopers.domain.pay.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.shared.Money;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WalletTest {

    @DisplayName("지갑 생성")
    @Nested
    class Create {
        @DisplayName("사용자별 초기 잔액 0의 지갑을 생성한다")
        @Test
        void createsZeroBalanceWallet() {
            Wallet wallet = Wallet.zero(1L);

            assertThat(wallet.getUserId()).isEqualTo(1L);
            assertThat(wallet.getBalance()).isZero();
        }

        @DisplayName("저장된 잔액으로 지갑을 복원한다")
        @Test
        void restoresWallet_withStoredBalance() {
            Wallet wallet = Wallet.restore(1L, 1_000L);

            assertThat(wallet.getBalance()).isEqualTo(1_000L);
        }

        @DisplayName("0 이하 사용자 ID로 지갑을 만들 수 없다")
        @ParameterizedTest
        @ValueSource(longs = {0L, -1L})
        void rejectsCreate_whenUserIdIsNotPositive(long userId) {
            assertThatThrownBy(() -> Wallet.zero(userId)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Wallet.restore(userId, 0L)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @DisplayName("포인트 충전")
    @Nested
    class Charge {
        @DisplayName("충전액만큼 잔액을 더하고 충전 기록을 반환한다")
        @Test
        void increasesBalance_andReturnsChargeBill() {
            Wallet wallet = Wallet.zero(1L);

            PointBill bill = wallet.charge(Money.positive(1_000L));

            assertThat(wallet.getBalance()).isEqualTo(1_000L);
            assertThat(bill.getUserId()).isEqualTo(1L);
            assertThat(bill.getType()).isEqualTo(PointBillType.CHARGE);
            assertThat(bill.getAmount()).isEqualTo(1_000L);
        }

        @DisplayName("충전 후 잔액이 범위를 초과하면 거절하고 기존 잔액을 유지한다")
        @Test
        void rejectsOverflow_andKeepsOriginalBalance() {
            Wallet wallet = Wallet.restore(1L, Long.MAX_VALUE);

            assertThatThrownBy(() -> wallet.charge(Money.positive(1L)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.CALCULATION_OVERFLOW);
            assertThat(wallet.getBalance()).isEqualTo(Long.MAX_VALUE);
        }
    }

    @DisplayName("포인트 사용")
    @Nested
    class Use {
        @DisplayName("사용액만큼 잔액을 빼고 주문 ID를 담은 사용 기록을 반환한다")
        @Test
        void decreasesBalance_andReturnsUseBill() {
            Wallet wallet = Wallet.restore(1L, 1_000L);

            PointBill bill = wallet.use(Money.positive(1_000L), 10L);

            assertThat(wallet.getBalance()).isZero();
            assertThat(bill.getUserId()).isEqualTo(1L);
            assertThat(bill.getType()).isEqualTo(PointBillType.USE);
            assertThat(bill.getOrderId()).isEqualTo(10L);
            assertThat(bill.getAmount()).isEqualTo(1_000L);
        }

        @DisplayName("잔액이 사용액보다 1 부족하면 거절하고 기존 잔액을 유지한다")
        @Test
        void rejectsInsufficientBalance_andKeepsOriginalBalance() {
            Wallet wallet = Wallet.restore(1L, 999L);

            assertThatThrownBy(() -> wallet.use(Money.positive(1_000L), 10L))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INSUFFICIENT_POINT);
            assertThat(wallet.getBalance()).isEqualTo(999L);
        }
    }
}
