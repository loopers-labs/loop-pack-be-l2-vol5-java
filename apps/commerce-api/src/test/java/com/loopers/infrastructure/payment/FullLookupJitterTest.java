package com.loopers.infrastructure.payment;

import com.loopers.application.payment.PaymentLookupPolicy;
import com.loopers.domain.payment.LookupBackoff;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FullLookupJitterTest {
    @Test
    @DisplayName("고정 seed의 full jitter는 0부터 명목 간격 사이에서 서로 다른 값을 선택한다")
    void staysWithinBounds() {
        FullLookupJitter jitter = new FullLookupJitter(new Random(42));
        long minimum = Long.MAX_VALUE;
        long maximum = Long.MIN_VALUE;
        for (int index = 0; index < 100; index++) {
            long value = jitter.chooseMillis(5000);
            assertThat(value).isBetween(0L, 5000L);
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
        }
        assertThat(maximum).isGreaterThan(minimum);
    }

    @Test
    @DisplayName("주입한 jitter의 양 끝 값으로 다음 조회 시각은 지금부터 명목 간격까지 정확히 계산된다")
    void usesInjectedJitter() {
        LookupBackoff backoff = new LookupBackoff(Duration.ofSeconds(5), 2, Duration.ofMinutes(1));
        Instant now = Instant.parse("2026-10-21T00:00:00Z");
        assertThat(new PaymentLookupPolicy(backoff, maximum -> 0, 12, 100).nextAt(now, 0)).isEqualTo(now);
        assertThat(new PaymentLookupPolicy(backoff, maximum -> maximum, 12, 100).nextAt(now, 0))
            .isEqualTo(now.plusSeconds(5));
    }
}
