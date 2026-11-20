package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingParameters;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

// Job 테스트는 revision을 무작위로 정하므로, 정상 형식(r1·r2·r10)이 통과하는지는 여기서 고정해 본다.
class WeeklyRankingParametersTest {
    @ParameterizedTest(name = "snapshotId={0} → revision {1}")
    @CsvSource({
        "weekly-20260817-r1, 1",
        "weekly-20260817-r2, 2",
        "weekly-20260817-r10, 10"
    })
    @DisplayName("앞자리 0이 없는 revision을 가진 snapshotId는 받아들이고 그 revision으로 읽는다")
    void acceptsRevisionWithoutLeadingZero(String snapshotId, int revision) {
        WeeklyRankingParameters parameters = WeeklyRankingParameters.parse("2026-08-17", snapshotId);

        assertThat(parameters.revision()).isEqualTo(revision);
        assertThat(parameters.periodStart()).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(parameters.snapshotId()).isEqualTo(snapshotId);
    }
}
