package com.loopers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class StreamerSchemaSafetyTest {
    @Test
    @DisplayName("Streamer 로컬 시작 설정은 기존 Inbox와 구매 활동 테이블을 보존한다")
    void preservesTablesWhenUsingLocalProfile() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("streamer-local-test", Map.of(
            "spring.profiles.active", "local",
            "spring.config.location", "classpath:/application.yml"
        )));

        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
    }
}
