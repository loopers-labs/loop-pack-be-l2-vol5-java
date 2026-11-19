package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import com.loopers.domain.ranking.model.WeeklyPurchaseScore;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class WeeklyPurchaseScoreTest {
    private static final UUID SNAPSHOT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("정수 합계는 배율 없이 저장하며 int를 넘는 합계와 long 최댓값도 정확히 보존한다")
    void preservesExactLongQuantity() {
        assertThat(WeeklyPurchaseScore.fromAggregate(SNAPSHOT, 7, new BigDecimal("5"), 2, 2).score()).isEqualTo(5);
        assertThat(WeeklyPurchaseScore.fromAggregate(SNAPSHOT, 7, new BigDecimal("4294967294"), 2, 2147483647).score())
            .isEqualTo(4294967294L);
        assertThat(WeeklyPurchaseScore.fromAggregate(SNAPSHOT, 7, new BigDecimal("9223372036854775807"), 9, 1).score())
            .isEqualTo(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "5.1", "9223372036854775808"})
    @DisplayName("무행·비정수·비양수·long 초과 합계는 정상 점수로 숨기지 않는다")
    void rejectsInvalidAggregate(String total) {
        assertThatThrownBy(() -> WeeklyPurchaseScore.fromAggregate(SNAPSHOT, 7,
            total == null ? null : new BigDecimal(total), 1, 1)).isInstanceOf(SnapshotIntegrityException.class);
    }

    @Test
    @DisplayName("양수 합계에도 빈 입력이나 잘못된 개별 수량이 섞이면 거절한다")
    void rejectsMissingOrNonPositiveInput() {
        assertThatThrownBy(() -> WeeklyPurchaseScore.fromAggregate(SNAPSHOT, 7, BigDecimal.TEN, 0, 1))
            .isInstanceOf(SnapshotIntegrityException.class);
        assertThatThrownBy(() -> WeeklyPurchaseScore.fromAggregate(SNAPSHOT, 7, BigDecimal.TEN, 2, 0))
            .isInstanceOf(SnapshotIntegrityException.class);
        assertThatThrownBy(() -> WeeklyPurchaseScore.fromAggregate(SNAPSHOT, 7, BigDecimal.TEN, 2, -1))
            .isInstanceOf(SnapshotIntegrityException.class);
    }
}
