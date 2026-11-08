package com.loopers.application.order;

import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/** O9 의 순수 계산(설계 W7.3-3). 간격 상한·회차 한도와 overflow·시각 범위만 본다. DB·relay 흐름은 통합 테스트가 본다. */
@DisplayName("재시도 정책은 5→10→20→40→60초에서 상한을 유지하고, 큰 번호·범위 밖 시각에서도 overflow 없이 판정한다.")
class OrderOutboxRetryPolicyTest {

    private final OrderOutboxRetryPolicy policy = new OrderOutboxRetryPolicy(Duration.ofSeconds(5), Duration.ofSeconds(60), 10);

    @DisplayName("기본값의 1~10번째 지연은 5·10·20·40 다음 60초로 고정되고, 아주 큰 번호도 상한이다.")
    @Test
    void doublesUntilCap() {
        assertAll(
            () -> assertThat(IntStream.rangeClosed(1, 10).mapToObj(policy::delayFor).map(Duration::toSeconds).toList())
                .containsExactly(5L, 10L, 20L, 40L, 60L, 60L, 60L, 60L, 60L, 60L),
            () -> assertThat(policy.delayFor(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(60)),
            () -> assertThat(policy.maxCycleAttempts()).isEqualTo(10)
        );
    }

    @DisplayName("두 배가 상한을 넘는 지점은 상한으로 자르고, 초기값과 상한이 같으면 늘지 않는다.")
    @ParameterizedTest
    @CsvSource({"7, 60, 5, 60", "7, 60, 4, 56", "30, 30, 3, 30", "1, 9223372036854775807, 64, 9223372036854775807"})
    void capsAtMax(long initial, long max, int attempt, long expected) {
        OrderOutboxRetryPolicy custom = new OrderOutboxRetryPolicy(Duration.ofSeconds(initial), Duration.ofSeconds(max), 1);
        assertThat(custom.delayFor(attempt)).isEqualTo(Duration.ofSeconds(expected));
    }

    @DisplayName("다음 시각은 now 에 지연을 더하고, DATETIME 범위를 넘으면 INTERNAL_ERROR 다.")
    @Test
    void nextAttemptAtChecksRange() {
        ZonedDateTime now = ZonedDateTime.of(2026, 11, 4, 1, 2, 3, 123_456_000, ZoneOffset.UTC);
        assertAll(
            () -> assertThat(policy.nextAttemptAt(now, 3)).isEqualTo(now.plusSeconds(20)),
            () -> assertThatThrownBy(() -> policy.nextAttemptAt(ZonedDateTime.of(9999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC), 1))
                .isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> new OrderOutboxRetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(Long.MAX_VALUE), 1)
                .nextAttemptAt(now, 64)).isInstanceOf(CoreException.class)
        );
    }

    @DisplayName("0 이하 지연·상한<초기·한도<1·시도 번호<1 은 거절한다.")
    @Test
    void rejectsInvalidInput() {
        assertAll(
            () -> assertThatThrownBy(() -> new OrderOutboxRetryPolicy(Duration.ZERO, Duration.ofSeconds(60), 10))
                .isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> new OrderOutboxRetryPolicy(Duration.ofSeconds(10), Duration.ofSeconds(5), 10))
                .isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> new OrderOutboxRetryPolicy(Duration.ofSeconds(5), Duration.ofSeconds(60), 0))
                .isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> policy.delayFor(0)).isInstanceOf(IllegalArgumentException.class)
        );
    }
}
