package com.loopers.config;

import com.loopers.CommerceApiApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캐시 시작 설정 바인딩·기동 거절(설계 §13.9·13.11, KQ1). 로컬 품절 TTL 만 나노초 변환 검사를 더하고 Redis 두 TTL 에는 두지 않는다.
 * 각 거절 검사는 속성 하나만 지정하고 실패 원인에 그 속성 이름이 있는지 확인한다.
 */
@DisplayName("캐시 설정은 Redis 30초·30초와 로컬 품절 5초 기본값을 가지며, 1ms 미만·형식 오류·변환 overflow 는 기동을 거절한다.")
class CouponCachePropertiesTest {

    private static final String PREFIX = "commerce.coupon.cache.";
    /** Long.MAX_VALUE / 1000 + 1 초. Duration 으로는 표현되지만 toMillis 가 넘친다. */
    private static final String MILLIS_OVERFLOW_SECONDS = "9223372036854776s";
    /** Long.MAX_VALUE / 10^9 + 1 초. 밀리초로는 표현되지만 toNanos 가 넘친다. */
    private static final String NANOS_OVERFLOW_SECONDS = "9223372037s";

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
        .withUserConfiguration(Support.class);

    @Configuration
    @EnableConfigurationProperties(CouponCacheProperties.class)
    static class Support {
    }

    @DisplayName("설정이 없으면 issued-ttl·sold-out-ttl 은 30초, local-sold-out-ttl 은 5초다.")
    @Test
    void bindsDefaults() {
        RUNNER.run(context -> assertThat(context.getBean(CouponCacheProperties.class))
            .isEqualTo(new CouponCacheProperties(Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(5))));
    }

    @DisplayName("세 값은 서로 독립적으로 바꿀 수 있고, Redis 두 TTL 은 나노초로 넘치는 큰 밀리초 값도 허용한다.")
    @Test
    void bindsChangedValues() {
        RUNNER.withPropertyValues(PREFIX + "issued-ttl=1ms", PREFIX + "sold-out-ttl=9223372036854775807ms")
            .run(context -> assertThat(context.getBean(CouponCacheProperties.class))
                .isEqualTo(new CouponCacheProperties(Duration.ofMillis(1), Duration.ofMillis(Long.MAX_VALUE), Duration.ofSeconds(5))));
        RUNNER.withPropertyValues(PREFIX + "sold-out-ttl=500ms")
            .run(context -> assertThat(context.getBean(CouponCacheProperties.class))
                .isEqualTo(new CouponCacheProperties(Duration.ofSeconds(30), Duration.ofMillis(500), Duration.ofSeconds(5))));
        RUNNER.withPropertyValues(PREFIX + "local-sold-out-ttl=1ms")
            .run(context -> assertThat(context.getBean(CouponCacheProperties.class))
                .isEqualTo(new CouponCacheProperties(Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofMillis(1))));
        RUNNER.withPropertyValues(PREFIX + "issued-ttl=" + NANOS_OVERFLOW_SECONDS, PREFIX + "sold-out-ttl=" + NANOS_OVERFLOW_SECONDS)
            .run(context -> assertThat(context.getBean(CouponCacheProperties.class)).isEqualTo(new CouponCacheProperties(
                Duration.ofSeconds(9_223_372_037L), Duration.ofSeconds(9_223_372_037L), Duration.ofSeconds(5))));
    }

    @DisplayName("0·음수·잘못된 형식·밀리초 결과 0·밀리초 변환 overflow 는 해당 속성 이름으로 기동 오류다.")
    @ParameterizedTest
    @ValueSource(strings = {"0s", "0ms", "-1ms", "abc", "1x", "1ns", "999us", MILLIS_OVERFLOW_SECONDS})
    void rejectsInvalid(String value) {
        for (String key : List.of("issued-ttl", "sold-out-ttl", "local-sold-out-ttl")) {
            RUNNER.withPropertyValues(PREFIX + key + "=" + value).run(context -> assertFailedOn(context, key));
        }
    }

    @DisplayName("local-sold-out-ttl 은 밀리초로 표현되더라도 나노초 변환이 넘치면 기동 오류다.")
    @Test
    void rejectsLocalNanosOverflow() {
        RUNNER.withPropertyValues(PREFIX + "local-sold-out-ttl=" + NANOS_OVERFLOW_SECONDS)
            .run(context -> assertFailedOn(context, "local-sold-out-ttl"));
    }

    @DisplayName("생성자는 null 도 거절한다.")
    @Test
    void rejectsNullInConstructor() {
        assertThat(Stream.of(
            catchFailure(() -> new CouponCacheProperties(null, Duration.ofSeconds(1), Duration.ofSeconds(1))),
            catchFailure(() -> new CouponCacheProperties(Duration.ofSeconds(1), null, Duration.ofSeconds(1))),
            catchFailure(() -> new CouponCacheProperties(Duration.ofSeconds(1), Duration.ofSeconds(1), null))))
            .allSatisfy(failure -> assertThat(failure).isInstanceOf(IllegalArgumentException.class));
    }

    @DisplayName("앱 진입 클래스의 설정 스캔 대상이며, 캐시를 끄는 별도 스위치 속성은 없다.")
    @Test
    void scannedWithoutSwitch() {
        assertThat(CommerceApiApplication.class.isAnnotationPresent(ConfigurationPropertiesScan.class)).isTrue();
        assertThat(CouponCacheProperties.class.getPackageName()).startsWith(CommerceApiApplication.class.getPackageName());
        assertThat(Stream.of(CouponCacheProperties.class.getRecordComponents()).map(c -> c.getName()))
            .containsExactly("issuedTtl", "soldOutTtl", "localSoldOutTtl");
    }

    private static Throwable catchFailure(Runnable call) {
        try {
            call.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static void assertFailedOn(AssertableApplicationContext context, String key) {
        assertThat(context).hasFailed();
        List<String> messages = new ArrayList<>();
        for (Throwable cause = context.getStartupFailure(); cause != null && messages.size() < 20; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
        }
        assertThat(messages).anySatisfy(message -> assertThat(message).contains(PREFIX + key));
    }
}
