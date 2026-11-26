package com.loopers.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

// 관리자 HTTP Basic은 local·dev 전용이다. 운영 계열 프로필에서 환경 변수로 켜지는 실수를 시작 단계에서 막는지 본다.
// DB·Kafka 없이 보안 설정만 올려 프로필 판단만 검증한다.
class AdminBasicAuthProfileGuardTest {
    private static final String ENABLED = "commerce.admin.basic-auth.enabled=true";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class, SecurityAutoConfiguration.class))
        .withUserConfiguration(AdminBoundaryConfig.class)
        .withPropertyValues("commerce.admin.basic-auth.username=ops-admin",
            "commerce.admin.basic-auth.password=test-only-admin-pass");

    @ParameterizedTest(name = "프로필={0}")
    @ValueSource(strings = {"prd", "qa"})
    @DisplayName("prd·qa 프로필에서 관리자 Basic 인증이 켜져 있으면 애플리케이션 시작이 실패하고 이유에 설정 이름이 나온다")
    void failsStartupWhenEnabledInProductionProfiles(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile, ENABLED)
            .run(context -> {
                assertThat(context).as("시작 실패").hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("commerce.admin.basic-auth.enabled")
                    .hasMessageContaining(profile);
            });
    }

    @Test
    @DisplayName("dev 프로필에서 관리자 Basic 인증을 켜면 애플리케이션이 정상 시작한다")
    void startsWhenEnabledInDevProfile() {
        runner.withPropertyValues("spring.profiles.active=dev", ENABLED)
            .run(context -> assertThat(context).hasNotFailed());
    }
}
