package com.loopers.domains.order.app.port;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

// S4: 사람이 SQL로 바꾸는 설정 행은 범위를 벗어난 필드만 그 필드의 기본값으로 바꾸고 나머지는 그대로 쓴다(필드별 대체, PL 결정 D107).
// 사람이 끈 relay가 다른 필드의 잘못된 값 때문에 켜지면 안 되기 때문이다. 행이 없으면 전체를 기본값(100건, 10초, 켜짐)으로 쓴다.
@ExtendWith(OutputCaptureExtension.class)
class RelaySettingsTest {

    static Stream<Arguments> outOfRange() {
        return Stream.of(
            Arguments.of("묶음 0", new RelaySettings(0, Duration.ofSeconds(5), true),
                new RelaySettings(100, Duration.ofSeconds(5), true), "batchSize=0"),
            Arguments.of("묶음 1001", new RelaySettings(1001, Duration.ofSeconds(5), true),
                new RelaySettings(100, Duration.ofSeconds(5), true), "batchSize=1001"),
            Arguments.of("최대 시간 0", new RelaySettings(50, Duration.ZERO, true),
                new RelaySettings(50, Duration.ofSeconds(10), true), "maxRound=PT0S"),
            Arguments.of("최대 시간 31초", new RelaySettings(50, Duration.ofSeconds(31), true),
                new RelaySettings(50, Duration.ofSeconds(10), true), "maxRound=PT31S"),
            Arguments.of("꺼짐 + 묶음 0", new RelaySettings(0, Duration.ofSeconds(5), false),
                new RelaySettings(100, Duration.ofSeconds(5), false), "batchSize=0"),
            Arguments.of("묶음 하한 1 + 최대 시간 0", new RelaySettings(1, Duration.ZERO, true),
                new RelaySettings(1, Duration.ofSeconds(10), true), "maxRound=PT0S"),
            Arguments.of("묶음 상한 1000 + 최대 시간 31초", new RelaySettings(1000, Duration.ofSeconds(31), false),
                new RelaySettings(1000, Duration.ofSeconds(10), false), "maxRound=PT31S"),
            Arguments.of("최대 시간 하한 1초 + 묶음 0", new RelaySettings(0, Duration.ofSeconds(1), true),
                new RelaySettings(100, Duration.ofSeconds(1), true), "batchSize=0"),
            Arguments.of("최대 시간 상한 30초 + 묶음 1001", new RelaySettings(1001, Duration.ofSeconds(30), true),
                new RelaySettings(100, Duration.ofSeconds(30), true), "batchSize=1001"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("outOfRange")
    @DisplayName("묶음 크기 1~1000·최대 시간 1~30초를 벗어난 필드만 그 기본값으로 바꾸고 경계값·다른 필드·켜기 여부는 그대로 두며 경고 로그에 그 값을 남긴다")
    void outOfRangeFieldFallsBackToItsDefault(String scene, RelaySettings raw, RelaySettings expected, String loggedValue,
        CapturedOutput output) {
        assertThat(RelaySettings.sanitize(raw)).isEqualTo(expected);
        assertThat(output.getAll()).contains("relay 설정").contains(loggedValue);
    }

    @Test
    @DisplayName("설정 행이 없으면 기본값(100건, 10초, 켜짐)을 쓰고 경고 로그에 이유를 남긴다")
    void missingRowFallsBackToDefault(CapturedOutput output) {
        assertThat(RelaySettings.sanitize(null)).isEqualTo(new RelaySettings(100, Duration.ofSeconds(10), true));
        assertThat(output.getAll()).contains("relay 설정").contains("행 없음");
    }

    @Test
    @DisplayName("범위 안의 값과 꺼짐은 그대로 쓴다")
    void validValuesAreKept() {
        RelaySettings raw = new RelaySettings(1000, Duration.ofSeconds(30), false);

        assertThat(RelaySettings.sanitize(raw)).isEqualTo(raw);
    }
}
