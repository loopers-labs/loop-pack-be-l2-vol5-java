package com.loopers.domain.pay.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PointBillTest {

    @DisplayName("충전 기록 생성")
    @Nested
    class Charge {
        @DisplayName("사용자·충전액으로 CHARGE 기록을 생성한다")
        @Test
        void createsChargeBill() {
            PointBill bill = PointBill.charge(1L, 1_000L);

            assertThat(bill.getUserId()).isEqualTo(1L);
            assertThat(bill.getType()).isEqualTo(PointBillType.CHARGE);
            assertThat(bill.getAmount()).isEqualTo(1_000L);
        }

        @DisplayName("0 이하 사용자 ID로 기록을 만들 수 없다")
        @ParameterizedTest
        @ValueSource(longs = {0L, -1L})
        void rejectsCharge_whenUserIdIsNotPositive(long userId) {
            assertThatThrownBy(() -> PointBill.charge(userId, 1_000L)).isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("0 이하 충전액으로 기록을 만들 수 없다")
        @ParameterizedTest
        @ValueSource(longs = {0L, -1L})
        void rejectsCharge_whenAmountIsNotPositive(long amount) {
            assertThatThrownBy(() -> PointBill.charge(1L, amount)).isInstanceOf(RuntimeException.class);
        }
    }

    @DisplayName("사용 기록 생성")
    @Nested
    class Use {
        @DisplayName("사용자·주문·사용액으로 USE 기록을 생성한다")
        @Test
        void createsUseBill() {
            PointBill bill = PointBill.use(1L, 10L, 1_000L);

            assertThat(bill.getUserId()).isEqualTo(1L);
            assertThat(bill.getType()).isEqualTo(PointBillType.USE);
            assertThat(bill.getOrderId()).isEqualTo(10L);
            assertThat(bill.getAmount()).isEqualTo(1_000L);
        }

        @DisplayName("0 이하 주문 ID로 기록을 만들 수 없다")
        @ParameterizedTest
        @ValueSource(longs = {0L, -1L})
        void rejectsUse_whenOrderIdIsNotPositive(long orderId) {
            assertThatThrownBy(() -> PointBill.use(1L, orderId, 1_000L)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @DisplayName("저장된 기록 복원")
    @Nested
    class Restore {
        @DisplayName("저장된 값으로 기록을 복원한다")
        @Test
        void restoresBill() {
            Instant createdAt = Instant.now();

            PointBill bill = PointBill.restore(1L, 1L, PointBillType.CHARGE, 1_000L, null, createdAt);

            assertThat(bill.getId()).isEqualTo(1L);
            assertThat(bill.getCreatedAt()).isEqualTo(createdAt);
        }

        @DisplayName("주문 ID가 있는 사용 기록도 복원한다")
        @Test
        void restoresUseBill_withOrderId() {
            PointBill bill = PointBill.restore(1L, 1L, PointBillType.USE, 1_000L, 10L, Instant.now());

            assertThat(bill.getOrderId()).isEqualTo(10L);
        }

        @DisplayName("0 이하 ID 또는 저장 시각이 없으면 복원할 수 없다")
        @Test
        void rejectsRestore_whenStateIsInvalid() {
            assertThatThrownBy(() -> PointBill.restore(0L, 1L, PointBillType.CHARGE, 1_000L, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PointBill.restore(1L, 1L, PointBillType.CHARGE, 1_000L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("충전 기록에 주문 ID가 있으면 복원할 수 없다")
        @Test
        void rejectsRestore_whenChargeHasOrderId() {
            assertThatThrownBy(() -> PointBill.restore(1L, 1L, PointBillType.CHARGE, 1_000L, 10L, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
