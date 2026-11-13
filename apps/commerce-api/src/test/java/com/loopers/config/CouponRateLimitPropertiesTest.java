package com.loopers.config;

import org.junit.jupiter.api.DisplayName;
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

/**
 * 대기 등록 입구 요청률 제한 설정 바인딩·기동 거절(설계 §12.3·12.11, WQ1·WQ8 의 설정 부분).
 * 실제 limiter 생성·라이브러리 표현 범위·소진/재허용·대기 0·MVC 선행성은 interceptor 를 도입하는 후속 단위가 본다.
 */
@DisplayName("요청률 제한 설정은 생략 시 1초당 10개이고, 0 이하·형식 오류는 기동을 거절한다.")
class CouponRateLimitPropertiesTest {

    private static final String PREFIX = "commerce.coupon.rate-limit.";

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
        .withUserConfiguration(Support.class);

    @Configuration
    @EnableConfigurationProperties(CouponRateLimitProperties.class)
    static class Support {
    }

    @DisplayName("설정이 없으면 limit-for-period 10, refresh-period 1초다.")
    @Test
    void bindsDefaults() {
        RUNNER.run(context -> assertThat(context.getBean(CouponRateLimitProperties.class))
            .isEqualTo(new CouponRateLimitProperties(10, Duration.ofSeconds(1))));
    }

    @DisplayName("명시한 양의 값은 그대로 바인딩한다.")
    @Test
    void bindsExplicit() {
        RUNNER.withPropertyValues(PREFIX + "limit-for-period=3", PREFIX + "refresh-period=500ms")
            .run(context -> assertThat(context.getBean(CouponRateLimitProperties.class))
                .isEqualTo(new CouponRateLimitProperties(3, Duration.ofMillis(500))));
    }

    @DisplayName("limit-for-period 의 1 이상 int 값은 그대로 바인딩한다.")
    @ParameterizedTest
    @ValueSource(strings = {"1", "2147483647"})
    void bindsLimitForPeriod(String value) {
        RUNNER.withPropertyValues(PREFIX + "limit-for-period=" + value)
            .run(context -> assertThat(context.getBean(CouponRateLimitProperties.class).limitForPeriod())
                .isEqualTo(Integer.parseInt(value)));
    }

    @DisplayName("limit-for-period 의 0·음수·소수·문자·빈 값·int 범위 초과는 기동 오류다.")
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "3.5", "abc", "", "2147483648"})
    void rejectsInvalidLimitForPeriod(String value) {
        RUNNER.withPropertyValues(PREFIX + "limit-for-period=" + value)
            .run(context -> assertFailedOn(context, "limit-for-period"));
    }

    @DisplayName("refresh-period 의 0·음수·잘못된 형식은 기동 오류다.")
    @ParameterizedTest
    @ValueSource(strings = {"0s", "0ms", "-1s", "abc", "1x"})
    void rejectsInvalidRefreshPeriod(String value) {
        RUNNER.withPropertyValues(PREFIX + "refresh-period=" + value)
            .run(context -> assertFailedOn(context, "refresh-period"));
    }

    /** 속성 하나만 지정한 뒤 실패 원인에 그 속성 이름이 있는지 확인해 다른 속성 오류에 가려 통과하지 않게 한다. */
    private static void assertFailedOn(AssertableApplicationContext context, String key) {
        assertThat(context).hasFailed();
        List<String> messages = new ArrayList<>();
        for (Throwable cause = context.getStartupFailure(); cause != null && messages.size() < 20; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
        }
        assertThat(messages).anySatisfy(message -> assertThat(message).contains(PREFIX + key));
    }
}
