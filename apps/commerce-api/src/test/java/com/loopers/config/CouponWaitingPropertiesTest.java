package com.loopers.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 대기실 시작 설정 바인딩·기동 거절(설계 §12.10·12.11, WQ1 의 설정 부분). 앱 전체 기동·대기실 비활성·open/close 는 후속 단위가 본다.
 * 각 거절 검사는 속성 하나만 지정하고, 실패 원인에 그 속성 이름이 있는지 확인해 다른 속성 오류에 가려 통과하지 않게 한다.
 */
@DisplayName("대기실 설정은 campaignId 생략을 허용하고 1초·30초 기본값을 가지며, 잘못된 ID·기간은 기동을 거절한다.")
class CouponWaitingPropertiesTest {

    private static final String PREFIX = "commerce.coupon.waiting.";

    /** Long.MAX_VALUE / 1000 + 1 초. Duration 으로는 표현되지만 toMillis 가 넘친다. */
    private static final String MILLIS_OVERFLOW_SECONDS = "9223372036854776s";

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
        .withUserConfiguration(Support.class);

    @Configuration
    @EnableConfigurationProperties(CouponWaitingProperties.class)
    static class Support {
    }

    @DisplayName("설정이 없으면 campaignId 는 null, scheduler-delay 1초, admission-duration 30초이며 기동한다.")
    @Test
    void bindsDefaults() {
        RUNNER.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CouponWaitingProperties.class))
                .isEqualTo(new CouponWaitingProperties(null, Duration.ofSeconds(1), Duration.ofSeconds(30)));
        });
    }

    @DisplayName("campaign-id")
    @Nested
    class CampaignId {

        @DisplayName("양의 Long 범위 값은 그대로 바인딩하고 두 기간은 기본값을 유지한다.")
        @ParameterizedTest
        @ValueSource(strings = {"1", "42", "9223372036854775807"})
        void bindsPositive(String value) {
            RUNNER.withPropertyValues(PREFIX + "campaign-id=" + value)
                .run(context -> assertThat(context.getBean(CouponWaitingProperties.class))
                    .isEqualTo(new CouponWaitingProperties(Long.parseLong(value), Duration.ofSeconds(1), Duration.ofSeconds(30))));
        }

        @DisplayName("0·음수·잘못된 형식·Long 범위 초과는 기동 오류다.")
        @ParameterizedTest
        @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
        void rejectsInvalid(String value) {
            RUNNER.withPropertyValues(PREFIX + "campaign-id=" + value)
                .run(context -> assertFailedOn(context, "campaign-id"));
        }
    }

    @DisplayName("scheduler-delay")
    @Nested
    class SchedulerDelay {

        @DisplayName("1ms 이상이면서 밀리초로 표현 가능한 기간은 그대로 바인딩한다.")
        @ParameterizedTest
        @ValueSource(strings = {"1ms", "1500ms", "2s", "9223372036854775807ms"})
        void bindsPositiveMillis(String value) {
            RUNNER.withPropertyValues(PREFIX + "scheduler-delay=" + value)
                .run(context -> assertThat(context.getBean(CouponWaitingProperties.class).schedulerDelay())
                    .isEqualTo(parsed(value)));
        }

        @DisplayName("0·음수·잘못된 형식은 기동 오류다.")
        @ParameterizedTest
        @ValueSource(strings = {"0s", "0ms", "-1s", "-1ms", "abc", "1x"})
        void rejectsNonPositiveOrMalformed(String value) {
            RUNNER.withPropertyValues(PREFIX + "scheduler-delay=" + value)
                .run(context -> assertFailedOn(context, "scheduler-delay"));
        }

        @DisplayName("양수지만 밀리초 결과가 0 이거나 밀리초 변환이 넘치는 기간은 생성자 검증으로 기동 오류다.")
        @ParameterizedTest
        @ValueSource(strings = {"1ns", "999us", MILLIS_OVERFLOW_SECONDS})
        void rejectsUnrepresentableMillis(String value) {
            assertPositiveButNotPositiveMillis(value);
            RUNNER.withPropertyValues(PREFIX + "scheduler-delay=" + value)
                .run(context -> assertRejectedByConstructor(context, "scheduler-delay"));
        }
    }

    @DisplayName("admission-duration")
    @Nested
    class AdmissionDuration {

        @DisplayName("1ms 이상이면서 밀리초로 표현 가능한 기간은 그대로 바인딩한다.")
        @ParameterizedTest
        @ValueSource(strings = {"1ms", "1500ms", "45s", "9223372036854775807ms"})
        void bindsPositiveMillis(String value) {
            RUNNER.withPropertyValues(PREFIX + "admission-duration=" + value)
                .run(context -> assertThat(context.getBean(CouponWaitingProperties.class).admissionDuration())
                    .isEqualTo(parsed(value)));
        }

        @DisplayName("0·음수·잘못된 형식은 기동 오류다.")
        @ParameterizedTest
        @ValueSource(strings = {"0s", "0ms", "-1s", "-1ms", "abc", "1x"})
        void rejectsNonPositiveOrMalformed(String value) {
            RUNNER.withPropertyValues(PREFIX + "admission-duration=" + value)
                .run(context -> assertFailedOn(context, "admission-duration"));
        }

        @DisplayName("양수지만 밀리초 결과가 0 이거나 밀리초 변환이 넘치는 기간은 생성자 검증으로 기동 오류다.")
        @ParameterizedTest
        @ValueSource(strings = {"1ns", "999us", MILLIS_OVERFLOW_SECONDS})
        void rejectsUnrepresentableMillis(String value) {
            assertPositiveButNotPositiveMillis(value);
            RUNNER.withPropertyValues(PREFIX + "admission-duration=" + value)
                .run(context -> assertRejectedByConstructor(context, "admission-duration"));
        }
    }

    private static Duration parsed(String value) {
        if (value.endsWith("ns")) {
            return Duration.ofNanos(Long.parseLong(value.substring(0, value.length() - 2)));
        }
        if (value.endsWith("us")) {
            return Duration.ofNanos(Long.parseLong(value.substring(0, value.length() - 2)) * 1_000);
        }
        if (value.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
        }
        return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
    }

    /** 거절 사유가 "양수 아님"이 아니라 밀리초 표현임을 고정한다. 입력이 양의 Duration 이어야 이 검사가 의미 있다. */
    private static void assertPositiveButNotPositiveMillis(String value) {
        Duration duration = parsed(value);
        assertThat(duration.isNegative() || duration.isZero()).isFalse();
        if (value.equals(MILLIS_OVERFLOW_SECONDS)) {
            assertThatThrownBy(duration::toMillis).isInstanceOf(ArithmeticException.class);
        } else {
            assertThat(duration.toMillis()).isZero();
        }
    }

    private static void assertFailedOn(AssertableApplicationContext context, String key) {
        assertThat(context).hasFailed();
        assertThat(causeMessages(context.getStartupFailure())).anySatisfy(message -> assertThat(message).contains(PREFIX + key));
    }

    private static void assertRejectedByConstructor(AssertableApplicationContext context, String key) {
        assertThat(context).hasFailed();
        List<Throwable> chain = causeChain(context.getStartupFailure());
        assertThat(chain).anySatisfy(cause -> assertThat(cause)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(PREFIX + key));
    }

    private static List<String> causeMessages(Throwable failure) {
        return causeChain(failure).stream().map(cause -> String.valueOf(cause.getMessage())).toList();
    }

    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cause = failure; cause != null && !chain.contains(cause); cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }
}
