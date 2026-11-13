package com.loopers.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** 수용량 설정 바인딩·기동 거절(설계 §7, Q15 의 C 부분). 앱 YAML 의 실제 값 3 은 CouponAdmissionRedisIntegrationTest 가 본다. */
@DisplayName("수용량 C 는 생략 시 3, 양의 정수만 받고 잘못된 명시 값은 기동을 거절한다.")
class CouponAdmissionPropertiesTest {

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
        .withUserConfiguration(Support.class);

    @Configuration
    @EnableConfigurationProperties(CouponAdmissionProperties.class)
    static class Support {
    }

    @DisplayName("설정이 없으면 3 이다.")
    @Test
    void defaultsToThree() {
        RUNNER.run(context -> assertThat(context.getBean(CouponAdmissionProperties.class).capacity()).isEqualTo(3));
    }

    @DisplayName("1 이상의 정수는 그대로 바인딩한다.")
    @ParameterizedTest
    @ValueSource(strings = {"1", "5"})
    void bindsPositive(String value) {
        RUNNER.withPropertyValues("commerce.coupon.admission.capacity=" + value)
            .run(context -> assertThat(context.getBean(CouponAdmissionProperties.class).capacity())
                .isEqualTo(Integer.parseInt(value)));
    }

    @DisplayName("0·음수·소수·문자·빈 값·int 범위 초과는 기동 오류다.")
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "3.5", "abc", "", "2147483648"})
    void rejectsInvalid(String value) {
        RUNNER.withPropertyValues("commerce.coupon.admission.capacity=" + value)
            .run(context -> assertThat(context).hasFailed());
    }
}
