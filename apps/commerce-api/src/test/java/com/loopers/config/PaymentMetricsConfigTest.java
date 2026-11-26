package com.loopers.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.application.payment.PaymentStatusQuery;
import com.loopers.config.PaymentMetricsConfig.PaymentUnknownGauge;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 저장소 대역으로 gauge 와 조회 서비스의 분기를 검증한다. 실제 경로는 {@link PaymentMetricsIntegrationTest} 가 맡는다.
 */
@DisplayName("UNKNOWN 결제 gauge 단위 검증")
class PaymentMetricsConfigTest {

    private static final String SENSITIVE = "jdbc:mysql://secret-host:3306/loopers?password=hunter2 card=4111-1111";

    @DisplayName("UNKNOWN 만 조회하고 음수는 거절한다.")
    @Test
    void queryAsksUnknownOnlyAndRejectsNegative() {
        StubRepository repository = new StubRepository(() -> 3L);
        PaymentStatusQuery query = new PaymentStatusQuery(repository);

        long count = query.countUnknown();
        repository.next = () -> -1L;

        assertAll(
            () -> assertThat(count).isEqualTo(3),
            () -> assertThat(repository.asked).containsExactly(PaymentStatus.UNKNOWN),
            () -> assertThatThrownBy(query::countUnknown).isInstanceOf(IllegalStateException.class)
        );
    }

    @DisplayName("지표 등록 계약과 관측마다 재조회·정상 0 을 확인한다.")
    @Test
    void readsCurrentValueOnEveryObservation() {
        StubRepository repository = new StubRepository(() -> 3L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PaymentUnknownGauge(new PaymentStatusQuery(repository)).bindTo(registry);
        Gauge gauge = registry.get(PaymentMetricsConfig.UNKNOWN_GAUGE).gauge();

        double first = gauge.value();
        repository.next = () -> 0L;
        double second = gauge.value();

        assertAll(
            () -> assertThat(gauge.getId().getName()).isEqualTo("commerce.payment.unknown"),
            () -> assertThat(gauge.getId().getDescription()).isEqualTo("Current payment rows in UNKNOWN state; excludes PENDING"),
            () -> assertThat(gauge.getId().getTags()).isEmpty(),
            () -> assertThat(gauge.getId().getBaseUnit()).isNull(),
            () -> assertThat(first).isEqualTo(3.0),
            () -> assertThat(second).as("정상 0 은 NaN 이 아님").isEqualTo(0.0),
            () -> assertThat(repository.asked).as("관측마다 조회, 캐시 없음").hasSize(2)
        );
    }

    @DisplayName("중복 등록을 막고 대상의 강한 참조를 유지한다.")
    @Test
    void registersOncePerRegistryAndKeepsStrongReference() {
        StubRepository repository = new StubRepository(() -> 2L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        bindAndForget(registry, repository);
        bindAndForget(registry, repository);
        for (int i = 0; i < 3; i++) {
            System.gc();
        }

        assertAll(
            () -> assertThat(registry.find(PaymentMetricsConfig.UNKNOWN_GAUGE).gauges()).hasSize(1),
            () -> assertThat(registry.getMeters()).hasSize(1),
            () -> assertThat(registry.get(PaymentMetricsConfig.UNKNOWN_GAUGE).gauge().value())
                .as("약한 참조였다면 GC 뒤 NaN 이 될 수 있음").isEqualTo(2.0)
        );
    }

    @DisplayName("조회 예외는 NaN 으로 반환하고 안전한 로그만 남긴다.")
    @Test
    void runtimeFailureBecomesNaNWithoutSensitiveLog() {
        StubRepository repository = new StubRepository(() -> {
            throw new IllegalStateException(SENSITIVE);
        });
        PaymentUnknownGauge gauge = new PaymentUnknownGauge(new PaymentStatusQuery(repository));

        double[] value = new double[1];

        List<ILoggingEvent> events = capture(() -> value[0] = gauge.measure());

        assertAll(
            () -> assertThat(value[0]).isNaN(),
            () -> assertThat(events).hasSize(1),
            () -> assertThat(events.get(0).getLevel()).isEqualTo(Level.WARN),
            () -> assertThat(events.get(0).getFormattedMessage())
                .isEqualTo("PAYMENT_UNKNOWN_QUERY_FAILED exception=java.lang.IllegalStateException"),
            () -> assertThat(events.get(0).getThrowableProxy()).as("stack 미출력").isNull(),
            () -> assertThat(events.get(0).getFormattedMessage()).doesNotContain("secret-host", "hunter2", "4111")
        );
    }

    @DisplayName("음수는 NaN 과 내부 오류 로그로 처리한다.")
    @Test
    void negativeCountBecomesNaN() {
        PaymentUnknownGauge gauge = new PaymentUnknownGauge(new PaymentStatusQuery(new StubRepository(() -> -1L)));
        double[] value = new double[1];

        List<ILoggingEvent> events = capture(() -> value[0] = gauge.measure());

        assertAll(
            () -> assertThat(value[0]).isNaN(),
            () -> assertThat(events).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("PAYMENT_UNKNOWN_QUERY_FAILED exception=java.lang.IllegalStateException")
        );
    }

    @DisplayName("Error 는 NaN 으로 삼키지 않고 그대로 전파한다.")
    @Test
    void errorPropagates() {
        PaymentUnknownGauge gauge = new PaymentUnknownGauge(new PaymentStatusQuery(new StubRepository(() -> {
            throw new TestError();
        })));

        List<ILoggingEvent> events = capture(() -> assertThatThrownBy(gauge::measure).isInstanceOf(TestError.class));

        assertThat(events).isEmpty();
    }

    private static void bindAndForget(SimpleMeterRegistry registry, StubRepository repository) {
        new PaymentUnknownGauge(new PaymentStatusQuery(repository)).bindTo(registry);
    }

    /** 이 테스트가 붙인 appender 만 gauge logger 에서 떼어 낸다. */
    private static List<ILoggingEvent> capture(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(PaymentUnknownGauge.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return List.copyOf(appender.list);
    }

    private static final class TestError extends Error {
    }

    /** countByStatus 만 쓰는 저장소 대역. 다른 메서드는 이 검증에서 호출되면 안 된다. */
    private static final class StubRepository implements PaymentRepository {

        private final List<PaymentStatus> asked = new ArrayList<>();
        private Supplier<Long> next;

        StubRepository(Supplier<Long> next) {
            this.next = next;
        }

        @Override
        public long countByStatus(PaymentStatus status) {
            asked.add(status);
            return next.get();
        }

        @Override
        public Optional<PaymentModel> findByOrderId(Long orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean existsByAttemptKey(String attemptKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Long> findOrderIdsByProviderOrderId(String provider, String providerAccount, String providerOrderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PaymentModel> findAllByOrderIds(Collection<Long> orderIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PaymentModel save(PaymentModel payment) {
            throw new UnsupportedOperationException();
        }
    }
}
