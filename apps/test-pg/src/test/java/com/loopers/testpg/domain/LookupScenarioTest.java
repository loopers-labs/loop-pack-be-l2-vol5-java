package com.loopers.testpg.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LookupScenarioTest {
    @DisplayName("조회 지연은 0~60,000ms 범위의 정수로 설정한다.")
    @ParameterizedTest
    @ValueSource(longs = {0, 1, 60_000})
    void acceptsDelayWithinRange(long delay) {
        assertThat(new LookupScenario(delay).delayMillis()).isEqualTo(delay);
    }

    @DisplayName("조회 지연의 범위를 벗어나면 설정을 만들지 않는다.")
    @ParameterizedTest
    @ValueSource(longs = {-1, 60_001, Long.MAX_VALUE})
    void rejectsDelayOutsideRange(long delay) {
        assertThatIllegalArgumentException().isThrownBy(() -> new LookupScenario(delay));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 10_001, Integer.MAX_VALUE})
    void rejectsFailureCountsOutsideRange(int count) {
        assertThatIllegalArgumentException().isThrownBy(() -> new LookupScenario(0, count, 503));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400, 429, 500})
    void rejectsUnsupportedFailureStatus(int status) {
        assertThatIllegalArgumentException().isThrownBy(() -> new LookupScenario(0, 1, status));
    }
}
