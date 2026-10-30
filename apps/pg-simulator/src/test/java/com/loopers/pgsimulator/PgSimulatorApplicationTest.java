package com.loopers.pgsimulator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("수동 실행 인자는 포트와 자동 처리 지연만 받는다.")
class PgSimulatorApplicationTest {

    @DisplayName("인자를 생략하면 포트 18080, 지연 1000ms, 금액 규칙으로 시작한다.")
    @Test
    void usesDefaults() {
        PgSimulatorOptions options = PgSimulatorApplication.parse(new String[0]);

        assertAll(
            () -> assertThat(options.port()).isEqualTo(18080),
            () -> assertThat(options.settlement()).isEqualTo(Settlement.after(Duration.ofMillis(1000))),
            () -> assertThat(options.outcomePolicy()).isInstanceOf(AmountModuloOutcomePolicy.class)
        );
    }

    @DisplayName("포트와 지연을 지정할 수 있고 지연 0 도 허용한다.")
    @Test
    void parsesArguments() {
        PgSimulatorOptions options = PgSimulatorApplication.parse(new String[] {"--port=19090", "--settlement-delay-ms=0"});

        assertAll(
            () -> assertThat(options.port()).isEqualTo(19090),
            () -> assertThat(options.settlement()).isEqualTo(Settlement.after(Duration.ZERO))
        );
    }

    @DisplayName("잘못된 값이나 알 수 없는 인자면 시작하지 않는다.")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"--port=0", "--port=65536", "--port=abc", "--settlement-delay-ms=-1",
        "--settlement-delay-ms=1s", "--host=0.0.0.0"})
    void rejectsInvalidArguments(String arg) {
        assertThatThrownBy(() -> PgSimulatorApplication.parse(new String[] {arg}))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
