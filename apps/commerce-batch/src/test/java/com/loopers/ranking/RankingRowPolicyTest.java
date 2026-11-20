package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.ranking.model.vo.RankingPeriod;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import com.loopers.domain.ranking.policy.FrozenPurchaseInputPolicy;
import com.loopers.domain.ranking.policy.WeeklyRankingOrderPolicy;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RankingRowPolicyTest {
    private static final RankingPeriod PERIOD = new RankingPeriod(LocalDate.of(2026, 8, 17));
    private static final Instant CONFIRMED = Instant.parse("2026-08-17T01:00:00Z");

    @Test
    @DisplayName("입력 정책은 현재·이전 행만 사용해 서로 다른 스트림과 다음 주문을 독립 검증한다")
    void validatesIndependentInputSequences() {
        var laterProduct = new FrozenPurchaseInputPolicy.Row(42, 8, 1, CONFIRMED);
        var earlierProduct = new FrozenPurchaseInputPolicy.Row(42, 7, 2, CONFIRMED);
        FrozenPurchaseInputPolicy.requireValid(PERIOD, laterProduct, null);
        FrozenPurchaseInputPolicy.requireValid(PERIOD, earlierProduct, null);
        FrozenPurchaseInputPolicy.requireValid(PERIOD, laterProduct, earlierProduct);
        FrozenPurchaseInputPolicy.requireValid(PERIOD, new FrozenPurchaseInputPolicy.Row(43, 1, 1, CONFIRMED), laterProduct);
    }

    @ParameterizedTest
    @CsvSource({"0,7,1", "42,0,1", "42,7,0"})
    @DisplayName("입력 정책은 비양수 주문·상품·수량을 거절한다")
    void rejectsInvalidInput(long orderId, long productId, int quantity) {
        assertThatThrownBy(() -> FrozenPurchaseInputPolicy.requireValid(PERIOD,
            new FrozenPurchaseInputPolicy.Row(orderId, productId, quantity, CONFIRMED), null))
            .isInstanceOf(SnapshotIntegrityException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-08-16T23:59:59.999999Z", "2026-08-24T00:00:00Z", "2026-08-17T01:00:00.000000001Z"})
    @DisplayName("입력 정책은 UTC 기간과 마이크로초 계약을 판정한다")
    void rejectsInvalidTime(String timestamp) {
        assertThatThrownBy(() -> FrozenPurchaseInputPolicy.requireValid(PERIOD,
            new FrozenPurchaseInputPolicy.Row(42, 7, 1, Instant.parse(timestamp)), null))
            .isInstanceOf(SnapshotIntegrityException.class);
    }

    @ParameterizedTest
    @CsvSource({"42,8", "42,7", "41,9"})
    @DisplayName("입력 정책은 전달된 이전 키에 대한 중복·역행을 거절한다")
    void rejectsInputOrder(long orderId, long productId) {
        var previous = new FrozenPurchaseInputPolicy.Row(42, 8, 1, CONFIRMED);
        assertThatThrownBy(() -> FrozenPurchaseInputPolicy.requireValid(PERIOD,
            new FrozenPurchaseInputPolicy.Row(orderId, productId, 1, CONFIRMED), previous))
            .isInstanceOf(SnapshotIntegrityException.class);
    }

    @Test
    @DisplayName("순위 정책은 독립 스트림의 첫 행과 동점·점수 감소의 다음 행을 검증한다")
    void validatesIndependentRankingSequences() {
        var first = new WeeklyRankingOrderPolicy.Row(7, 5, 1);
        WeeklyRankingOrderPolicy.requireValid(first, null);
        WeeklyRankingOrderPolicy.requireValid(new WeeklyRankingOrderPolicy.Row(1, 2, 1), null);
        var second = new WeeklyRankingOrderPolicy.Row(9, 5, 2);
        WeeklyRankingOrderPolicy.requireValid(second, first);
        WeeklyRankingOrderPolicy.requireValid(new WeeklyRankingOrderPolicy.Row(8, 1, 3), second);
    }

    @ParameterizedTest
    @CsvSource({"0,5,2", "9,0,2", "9,-1,2", "9,5,1", "9,5,3", "6,5,2", "9,6,2"})
    @DisplayName("순위 정책은 행 유효성·연속 순위·점수·동점 순서를 판정한다")
    void rejectsInvalidRanking(long productId, long score, long rank) {
        assertThatThrownBy(() -> WeeklyRankingOrderPolicy.requireValid(new WeeklyRankingOrderPolicy.Row(productId, score, rank),
            new WeeklyRankingOrderPolicy.Row(7, 5, 1))).isInstanceOf(SnapshotIntegrityException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 2})
    @DisplayName("첫 순위는 양수1부터 시작한다")
    void rejectsWrongFirstRank(long rank) {
        assertThatThrownBy(() -> WeeklyRankingOrderPolicy.requireValid(new WeeklyRankingOrderPolicy.Row(7, 5, rank), null))
            .isInstanceOf(SnapshotIntegrityException.class);
    }

    @Test
    @DisplayName("최대 순위 뒤의 역행을 overflow로 정상화하지 않는다")
    void rejectsRankOverflow() {
        assertThatThrownBy(() -> WeeklyRankingOrderPolicy.requireValid(new WeeklyRankingOrderPolicy.Row(9, 1, 1),
            new WeeklyRankingOrderPolicy.Row(7, 5, Long.MAX_VALUE))).isInstanceOf(SnapshotIntegrityException.class);
    }

    @Test
    @DisplayName("실제 행이 없으면 입력·순위 행 정책은 거절한다")
    void rejectsAbsentRows() {
        assertThatThrownBy(() -> FrozenPurchaseInputPolicy.requireValid(PERIOD, null, null))
            .isInstanceOf(SnapshotIntegrityException.class);
        assertThatThrownBy(() -> WeeklyRankingOrderPolicy.requireValid(null, null)).isInstanceOf(SnapshotIntegrityException.class);
    }
}
