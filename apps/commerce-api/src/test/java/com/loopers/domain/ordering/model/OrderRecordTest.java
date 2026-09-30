package com.loopers.domain.ordering.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OrderRecordTest {

    @DisplayName("주문 기록 생성")
    @Nested
    class Paid {
        @DisplayName("성공한 결제만 PAID 상태로 생성한다")
        @Test
        void createsPaidOrderRecord() {
            OrderRecord orderRecord = OrderRecord.paid(1L, 7_000L);

            assertThat(orderRecord.getUserId()).isEqualTo(1L);
            assertThat(orderRecord.getAmount()).isEqualTo(7_000L);
            assertThat(orderRecord.getStatus()).isEqualTo(OrderRecordStatus.PAID);
        }

        @DisplayName("0 이하 결제액은 거절한다")
        @Test
        void rejectsPaid_whenAmountIsNotPositive() {
            assertThatThrownBy(() -> OrderRecord.paid(1L, 0L)).isInstanceOf(RuntimeException.class);
        }
    }

    @DisplayName("주문 기록 복원")
    @Nested
    class Restore {
        @DisplayName("저장된 상태를 복원한다")
        @Test
        void restoresOrderRecord() {
            Instant createdAt = Instant.now();

            OrderRecord orderRecord = OrderRecord.restore(1L, 1L, 7_000L, OrderRecordStatus.PAID, createdAt);

            assertThat(orderRecord.getId()).isEqualTo(1L);
            assertThat(orderRecord.getCreatedAt()).isEqualTo(createdAt);
        }

        @DisplayName("id가 없거나 생성 시각이 없으면 거절한다")
        @Test
        void rejectsRestore_whenMetadataIsMissing() {
            assertThatThrownBy(() -> OrderRecord.restore(0L, 1L, 7_000L, OrderRecordStatus.PAID, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OrderRecord.restore(1L, 1L, 7_000L, OrderRecordStatus.PAID, null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
