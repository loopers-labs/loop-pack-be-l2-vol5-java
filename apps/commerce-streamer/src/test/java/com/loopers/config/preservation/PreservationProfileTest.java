package com.loopers.config.preservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.support.preservation.PreservationContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;

class PreservationProfileTest {
    @Test
    @DisplayName("실제 앱 설정을 합쳐도 보존 프로필과 명시한 DB 연결 및 초기화 차단을 유지한다")
    void loadsEffectivePreservationSettings() {
        try (var context = PreservationContexts.start(ProfileConfiguration.class,
            "--COMMERCE_MYSQL_JDBC_URL=jdbc:mysql://localhost:13306/loopers")) {
            var environment = context.getEnvironment();
            assertThat(environment.getActiveProfiles()).containsExactly("ranking-preserve");
            assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(environment.getProperty("spring.sql.init.mode")).isEqualTo("never");
            assertThat(environment.getProperty("datasource.mysql-jpa.main.jdbc-url"))
                .isEqualTo("jdbc:mysql://localhost:13306/loopers");
            assertThat(environment.getProperty("datasource.redis.master.host")).isEqualTo("localhost");
            assertThat(environment.getProperty("datasource.redis.master.port")).isEqualTo("6379");
            assertThat(environment.getProperty("spring.kafka.bootstrap-servers")).isEqualTo("localhost:19092");
            assertThat(org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .isInstanceOfSatisfying(ch.qos.logback.classic.Logger.class,
                    logger -> assertThat(logger.getAppender("CONSOLE")).isNotNull());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProfileConfiguration {}
}
