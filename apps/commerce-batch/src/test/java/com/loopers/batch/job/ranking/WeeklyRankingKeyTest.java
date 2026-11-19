package com.loopers.batch.job.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklyRankingKeyTest {

    @DisplayName("문자열 잡 파라미터 periodStart와 revision으로 작업 식별값을 만든다.")
    @Test
    void readsStringParameters() {
        WeeklyRankingKey key = WeeklyRankingKey.from(Map.of("periodStart", "2026-08-17", "revision", "12"));

        assertThat(key.period()).isEqualTo(RankingPeriod.parse("2026-08-17"));
        assertThat(key.revision()).isEqualTo(12);
    }

    @DisplayName("revision은 0으로 시작하지 않는 양의 정수 한 가지 모양만 받는다.")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"01", "0", "-1", "+1", " 1", "1 ", "1.0", "2147483648"})
    void rejectsOtherRevisionShapes(String value) {
        assertThatThrownBy(() -> WeeklyRankingKey.parseRevision(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("타입이 붙은 파라미터는 받지 않는다. 같은 작업이 다른 JobInstance가 되지 않게 문자열로만 받는다.")
    @Test
    void rejectsTypedParameters() {
        assertThatThrownBy(() -> WeeklyRankingKey.from(Map.of("periodStart", "2026-08-17", "revision", 1L)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
