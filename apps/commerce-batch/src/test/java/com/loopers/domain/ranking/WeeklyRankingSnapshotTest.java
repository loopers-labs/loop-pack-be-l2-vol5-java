package com.loopers.domain.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklyRankingSnapshotTest {

    private static final WeeklyPeriod PERIOD = new WeeklyPeriod(LocalDate.parse("2026-08-17"));
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-08-24T01:00:00Z");

    @DisplayName("이미 고정한 snapshot 의 입력이 manifest 의 건수·checksum 과 다르면 계산 대상으로 쓰지 않는다.")
    @Test
    void rejectsInput_whenManifestDiffers() {
        // arrange
        WeeklyRankingSnapshot snapshot = WeeklyRankingSnapshot.freeze(PERIOD, 1, NOW, 4, 3, "abc", 1L);

        // act & assert
        assertThatCode(() -> snapshot.verifyInput(4, "abc")).doesNotThrowAnyException();
        assertThatThrownBy(() -> snapshot.verifyInput(5, "abc")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> snapshot.verifyInput(4, "xyz")).isInstanceOf(IllegalStateException.class);
    }

    @DisplayName("계산된 상품 수가 고정한 상품 수와 다르면 공개할 수 없다.")
    @Test
    void rejectsPublish_whenScoredProductCountDiffers() {
        // arrange
        WeeklyRankingSnapshot snapshot = WeeklyRankingSnapshot.freeze(PERIOD, 1, NOW, 4, 3, "abc", 1L);

        // act & assert
        assertThatCode(() -> snapshot.verifyScoredProducts(3)).doesNotThrowAnyException();
        assertThatThrownBy(() -> snapshot.verifyScoredProducts(2)).isInstanceOf(IllegalStateException.class);
    }
}
