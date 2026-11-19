package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.ranking.model.vo.RankingPeriod;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RankingPeriodTest {
    @Test
    @DisplayName("UTC 월요일 시작 포함·다음 월요일 끝 제외로 업무 기간을 계산한다")
    void calculatesUtcHalfOpenWeek() {
        var period = new RankingPeriod(LocalDate.of(2026, 8, 17));

        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(period.startInclusive()).isEqualTo(Instant.parse("2026-08-17T00:00:00Z"));
        assertThat(period.endExclusive()).isEqualTo(Instant.parse("2026-08-24T00:00:00Z"));
        assertThat(period.contains(Instant.parse("2026-08-16T23:59:59.999999Z"))).isFalse();
        assertThat(period.contains(Instant.parse("2026-08-17T00:00:00Z"))).isTrue();
        assertThat(period.contains(Instant.parse("2026-08-23T23:59:59.999999Z"))).isTrue();
        assertThat(period.contains(Instant.parse("2026-08-24T00:00:00Z"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-08-18", "2026-08-23", "0999-12-30", "9999-12-27"})
    @DisplayName("월요일 또는 MySQL에 저장할 수 있는 주간 범위를 위반하면 거절한다")
    void rejectsUnsupportedWeeks(String start) {
        assertThatThrownBy(() -> new RankingPeriod(LocalDate.parse(start))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("업무 기간 시작이 없으면 거절한다")
    void rejectsMissingStart() {
        assertThatThrownBy(() -> new RankingPeriod(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
