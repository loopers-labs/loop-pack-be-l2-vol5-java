package com.loopers.domain.ranking;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompletedRankingTest {
    private static final WeeklyRankingKey KEY = new WeeklyRankingKey(LocalDate.of(2026, 8, 17), 1);
    private static final Instant TIME = Instant.parse("2026-08-18T01:00:00.123456789Z");

    @Test
    void ranksHandCalculatedTotalsWithProductIdTieBreakAndIndependentChecksum() {
        var input = input(List.of(purchase(42, 7, 2), purchase(42, 8, 1), purchase(43, 7, 3), purchase(44, 9, 5)));
        var complete = CompletedRanking.calculate(input, List.of(score(9, 5), score(8, 1), score(7, 5)));

        assertThat(complete.products()).containsExactly(new RankedProduct(7, 5, 1),
            new RankedProduct(9, 5, 2), new RankedProduct(8, 1, 3));
        assertThat(complete.checksumVersion()).isEqualTo("v1");
        // Independent fixture: UTF-8 bytes "7\t5\t1\n9\t5\t2\n8\t1\t3\n".
        assertThat(complete.checksum()).isEqualTo("745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2");
        assertThatThrownBy(() -> complete.products().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyFrozenInputBecomesACompleteEmptyRanking() {
        var complete = CompletedRanking.calculate(input(List.of()), List.of());

        assertThat(complete.products()).isEmpty();
        assertThat(complete.checksum()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void missingExtraDuplicateAndWrongTotalsCannotBecomeComplete() {
        var input = input(List.of(purchase(42, 7, 5), purchase(42, 8, 1)));
        for (var invalid : List.of(
            List.of(score(7, 5)),
            List.of(score(7, 5), score(8, 1), score(9, 1)),
            List.of(score(7, 5), score(9, 1)),
            List.of(score(7, 5), score(7, 5)),
            List.of(score(7, 6), score(8, 1)))) {
            assertThatThrownBy(() -> CompletedRanking.calculate(input, invalid))
                .isInstanceOf(RankingSnapshotConflictException.class);
        }
    }

    @Test
    void anotherSnapshotsScoreCannotCompleteThisInput() {
        assertThatThrownBy(() -> CompletedRanking.calculate(input(List.of(purchase(42, 7, 5))),
            List.of(new ProductScore("another-snapshot", 7, 5))))
            .isInstanceOf(RankingSnapshotConflictException.class);
    }

    @Test
    void scoreAboveIntegerRangeKeepsItsExactValue() {
        var input = input(List.of(purchase(42, 7, Integer.MAX_VALUE), purchase(43, 7, 1)));
        assertThat(CompletedRanking.calculate(input, List.of(score(7, 2147483648L))).products())
            .containsExactly(new RankedProduct(7, 2147483648L, 1));
    }

    private static FrozenRankingInput input(List<RankingPurchase> purchases) {
        return new FrozenRankingInput(KEY, TIME, purchases);
    }

    private static RankingPurchase purchase(long order, long product, int quantity) {
        return new RankingPurchase(order, product, quantity, TIME);
    }

    private static ProductScore score(long product, long score) {
        return new ProductScore(KEY.snapshotId(), product, score);
    }
}
