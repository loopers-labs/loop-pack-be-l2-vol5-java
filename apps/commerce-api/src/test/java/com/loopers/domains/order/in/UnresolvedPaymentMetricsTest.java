package com.loopers.domains.order.in;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.domains.order.app.port.UnresolvedPaymentCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// 포트만 fake로 두고 실제 MeterRegistry(Simple)에 등록된 gauge 값을 본다. 스케줄러는 부르지 않고 refresh()를 직접 호출한다.
class UnresolvedPaymentMetricsTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(UnresolvedPaymentMetrics.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("갱신 중 조회가 실패하면 예외를 밖으로 내지 않고 네 status의 마지막 값을 유지하며 원인 종류를 WARN으로 남긴다")
    void keepsLastValuesWhenRefreshFails() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FailingAfterFirstCounter counter = new FailingAfterFirstCounter(
            Map.of("SEND_STARTED", 1L, "PENDING", 2L, "UNKNOWN", 3L, "NEEDS_REVIEW", 4L));
        UnresolvedPaymentMetrics metrics = new UnresolvedPaymentMetrics(counter, registry);
        metrics.refresh();
        counter.failFromNow();

        assertThatCode(metrics::refresh).doesNotThrowAnyException();

        assertThat(value(registry, "send_started")).isEqualTo(1.0);
        assertThat(value(registry, "pending")).isEqualTo(2.0);
        assertThat(value(registry, "unknown")).isEqualTo(3.0);
        assertThat(value(registry, "needs_review")).isEqualTo(4.0);
        List<ILoggingEvent> warnings = appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFormattedMessage()).contains("IllegalStateException");
    }

    @Test
    @DisplayName("첫 갱신 전에는 네 status gauge가 모두 있고 값은 0이 아닌 NaN이다")
    void startsWithNaNBeforeFirstRefresh() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new UnresolvedPaymentMetrics(() -> Map.of("PENDING", 2L), registry);

        for (String status : List.of("send_started", "pending", "unknown", "needs_review")) {
            assertThat(value(registry, status)).as("status=%s 값", status).isNaN();
        }
    }

    @Test
    @DisplayName("조회 결과에 네 status 밖의 값(SUCCESS)이 있으면 어느 gauge에도 넣지 않고 WARN 1건을 남기며 나머지는 반영한다")
    void ignoresStatusOutsideFourWithWarning() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UnresolvedPaymentMetrics metrics = new UnresolvedPaymentMetrics(() -> Map.of(
            "SEND_STARTED", 1L, "PENDING", 2L, "UNKNOWN", 3L, "NEEDS_REVIEW", 4L, "SUCCESS", 9L), registry);

        metrics.refresh();

        assertThat(value(registry, "send_started")).isEqualTo(1.0);
        assertThat(value(registry, "pending")).isEqualTo(2.0);
        assertThat(value(registry, "unknown")).isEqualTo(3.0);
        assertThat(value(registry, "needs_review")).isEqualTo(4.0);
        assertThat(registry.find("commerce.payment.unresolved").gauges()).hasSize(4);
        List<ILoggingEvent> warnings = appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFormattedMessage()).contains("SUCCESS");
    }

    private static double value(SimpleMeterRegistry registry, String status) {
        Gauge gauge = registry.find("commerce.payment.unresolved").tag("status", status).gauge();
        assertThat(gauge).as("status=%s gauge", status).isNotNull();
        return gauge.value();
    }

    private static final class FailingAfterFirstCounter implements UnresolvedPaymentCounter {
        private final Map<String, Long> counts;
        private final AtomicBoolean failing = new AtomicBoolean();

        FailingAfterFirstCounter(Map<String, Long> counts) {
            this.counts = counts;
        }

        void failFromNow() {
            failing.set(true);
        }

        @Override
        public Map<String, Long> countByProviderStatus() {
            if (failing.get()) {
                throw new IllegalStateException("db down");
            }
            return counts;
        }
    }
}
