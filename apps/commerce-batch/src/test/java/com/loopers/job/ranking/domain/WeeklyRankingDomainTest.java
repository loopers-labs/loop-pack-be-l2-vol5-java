package com.loopers.job.ranking.domain;

import com.loopers.batch.job.ranking.domain.FrozenInputSummarizer;
import com.loopers.batch.job.ranking.domain.InvalidPurchaseInputException;
import com.loopers.batch.job.ranking.domain.ProductPurchaseTotal;
import com.loopers.batch.job.ranking.domain.ProductScore;
import com.loopers.batch.job.ranking.domain.RankingPeriod;
import com.loopers.batch.job.ranking.domain.SnapshotId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklyRankingDomainTest {
    @Test
    @DisplayName("기간은 UTC 월요일 00:00부터 시작한다")
    void periodStartsOnMondayUtc() {
        var period = RankingPeriod.parse("2026-08-17");
        assertThat(period.startInclusiveUtc()).isEqualTo(LocalDateTime.parse("2026-08-17T00:00:00"));
    }

    @Test
    @DisplayName("기간은 다음 월요일 00:00 직전까지다")
    void periodEndsBeforeNextMondayUtc() {
        var period = RankingPeriod.parse("2026-08-17");
        assertThat(period.endExclusiveUtc()).isEqualTo(LocalDateTime.parse("2026-08-24T00:00:00"));
    }

    @Test
    @DisplayName("월요일이 아닌 기간 시작은 거부한다")
    void rejectsNonMonday() {
        assertThatThrownBy(() -> new RankingPeriod(LocalDate.parse("2026-08-18")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("snapshotId는 기간 날짜와 revision으로 만든다")
    void snapshotIdContainsPeriodAndRevision() {
        var id = SnapshotId.of(RankingPeriod.parse("2026-08-17"), 3);
        assertThat(id.value()).isEqualTo("weekly-20260817-r3");
    }

    @Test
    @DisplayName("snapshotId 문자열을 같은 값으로 해석한다")
    void snapshotIdRoundTrips() {
        var period = RankingPeriod.parse("2026-08-17");
        assertThat(SnapshotId.parse("weekly-20260817-r3", period)).isEqualTo(SnapshotId.of(period, 3));
    }

    @Test
    @DisplayName("revision 0인 snapshotId는 거부한다")
    void rejectsZeroRevision() {
        var period = RankingPeriod.parse("2026-08-17");
        assertThatThrownBy(() -> SnapshotId.parse("weekly-20260817-r0", period))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("형식이 다른 snapshotId는 거부한다")
    void rejectsMalformedSnapshotId() {
        var period = RankingPeriod.parse("2026-08-17");
        assertThatThrownBy(() -> SnapshotId.parse("weekly-2026-08-17", period))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("입력 요약은 구매 활동 행 수를 센다")
    void countsFrozenInputs() {
        var summarizer = new FrozenInputSummarizer();
        summarizer.accept(42, 7, 2, LocalDateTime.parse("2026-08-17T01:00:00"));
        summarizer.accept(43, 7, 3, LocalDateTime.parse("2026-08-18T01:00:00"));
        assertThat(summarizer.summary().inputCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("입력 요약은 서로 다른 상품 수를 센다")
    void countsDistinctProducts() {
        var summarizer = new FrozenInputSummarizer();
        summarizer.accept(42, 7, 2, LocalDateTime.parse("2026-08-17T01:00:00"));
        summarizer.accept(43, 7, 3, LocalDateTime.parse("2026-08-18T01:00:00"));
        assertThat(summarizer.summary().productCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("입력 요약에서 0 이하 수량을 거부한다")
    void rejectsInvalidInputQuantity() {
        var summarizer = new FrozenInputSummarizer();
        assertThatThrownBy(() -> summarizer.accept(44, 9, 0, LocalDateTime.parse("2026-08-19T01:00:00")))
                .isInstanceOf(InvalidPurchaseInputException.class);
    }

    @Test
    @DisplayName("상품 점수는 수량 합이다")
    void scoreIsQuantitySum() {
        assertThat(new ProductPurchaseTotal(7, 2, 5, 2).toScore()).isEqualTo(new ProductScore(7, 5));
    }

    @Test
    @DisplayName("상품 점수 계산은 0 이하 수량을 거부한다")
    void rejectsInvalidQuantityForScore() {
        assertThatThrownBy(() -> new ProductPurchaseTotal(7, 2, 3, 0).toScore())
                .isInstanceOf(InvalidPurchaseInputException.class);
    }
}
