package com.loopers.domain.ranking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklyRankingKeyTest {
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 17);

    @Test
    void derivesStableSnapshotIdentityAndExplicitUtcWeek() {
        var key = new WeeklyRankingKey(MONDAY, 1);

        assertThat(key.snapshotId()).isEqualTo("weekly-20260817-r1");
        assertThat(key.periodStart()).isEqualTo(MONDAY);
        assertThat(key.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(key.startInclusive()).isEqualTo(Instant.parse("2026-08-17T00:00:00Z"));
        assertThat(key.endExclusive()).isEqualTo(Instant.parse("2026-08-24T00:00:00Z"));
        assertThat(key).isEqualTo(new WeeklyRankingKey(MONDAY, 1));
        assertThat(new WeeklyRankingKey(MONDAY, 2).snapshotId()).isEqualTo("weekly-20260817-r2");
    }

    @ParameterizedTest
    @MethodSource("weekBoundaries")
    void includesStartAndExcludesEndWithoutLosingNanoseconds(String instant, boolean expected) {
        assertThat(new WeeklyRankingKey(MONDAY, 1).contains(Instant.parse(instant))).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("invalidKeys")
    void rejectsMissingDateNonMondayAndNonPositiveRevision(LocalDate periodStart, int revision) {
        assertThatThrownBy(() -> new WeeklyRankingKey(periodStart, revision))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingConfirmationTime() {
        var key = new WeeklyRankingKey(MONDAY, 1);

        assertThatThrownBy(() -> key.contains(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> weekBoundaries() {
        return Stream.of(
            Arguments.of("2026-08-16T23:59:59.999999999Z", false),
            Arguments.of("2026-08-17T00:00:00Z", true),
            Arguments.of("2026-08-17T00:00:00.000000001Z", true),
            Arguments.of("2026-08-23T23:59:59.999999999Z", true),
            Arguments.of("2026-08-24T00:00:00Z", false));
    }

    private static Stream<Arguments> invalidKeys() {
        return Stream.of(
            Arguments.of(null, 1),
            Arguments.of(MONDAY.plusDays(1), 1),
            Arguments.of(MONDAY.minusDays(1), 1),
            Arguments.of(MONDAY, 0),
            Arguments.of(MONDAY, -1));
    }
}
