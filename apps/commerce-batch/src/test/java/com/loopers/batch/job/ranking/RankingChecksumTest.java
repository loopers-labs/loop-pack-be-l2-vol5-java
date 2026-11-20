package com.loopers.batch.job.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RankingChecksumTest {

    @DisplayName("입력은 orderId·productId 순, 필드 TAB, 행 LF(마지막 포함), UTC 마이크로초 6자리로 직렬화한다. 받은 순서와 무관하다.")
    @Test
    void serializesInputsCanonically() {
        List<RankingChecksum.InputLine> lines = List.of(
            new RankingChecksum.InputLine(43, 7, 3, LocalDateTime.parse("2026-08-18T01:00:00")),
            new RankingChecksum.InputLine(42, 8, 1, LocalDateTime.parse("2026-08-17T01:00:00.000001")),
            new RankingChecksum.InputLine(42, 7, 2, LocalDateTime.parse("2026-08-17T01:00:00")));

        assertThat(RankingChecksum.inputText(lines)).isEqualTo(
            "42\t7\t2\t2026-08-17T01:00:00.000000Z\n"
                + "42\t8\t1\t2026-08-17T01:00:00.000001Z\n"
                + "43\t7\t3\t2026-08-18T01:00:00.000000Z\n");
        assertThat(RankingChecksum.ofInputs(lines)).isEqualTo(RankingChecksum.ofInputs(lines.reversed())).hasSize(64);
    }

    @DisplayName("결과는 rank 순 productId·score·rank 이고, 빈 입력·결과는 빈 문자열의 SHA-256 이다.")
    @Test
    void serializesResultsAndEmpty() {
        List<RankingChecksum.ResultLine> lines = List.of(
            new RankingChecksum.ResultLine(8, 1, 3), new RankingChecksum.ResultLine(7, 5, 1), new RankingChecksum.ResultLine(9, 5, 2));

        assertThat(RankingChecksum.resultText(lines)).isEqualTo("7\t5\t1\n9\t5\t2\n8\t1\t3\n");
        assertThat(RankingChecksum.ofInputs(List.of()))
            .isEqualTo(RankingChecksum.ofResults(List.of()))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @DisplayName("job 대상은 UTC 월요일·1 이상 revision·정규 표기만 받는다.")
    @Test
    void validatesTarget() {
        assertThatThrownBy(() -> WeeklyRankingTarget.of("2026-08-18", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeeklyRankingTarget.of("2026-8-17", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeeklyRankingTarget.of("2026-08-17", "+1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeeklyRankingTarget.of("2026-08-17", "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeeklyRankingTarget.of(null, "1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(WeeklyRankingTarget.of("2026-08-17", "1").toJobParameters().getParameters())
            .allSatisfy((key, parameter) -> assertThat(parameter.isIdentifying()).isTrue())
            .containsOnlyKeys("periodStart", "revision");
    }
}
