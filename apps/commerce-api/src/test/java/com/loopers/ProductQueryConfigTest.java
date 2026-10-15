package com.loopers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("상품 조회 실험용 앱 설정")
class ProductQueryConfigTest {

    @Test
    @DisplayName("프로필을 지정하지 않고 실행하면 실험 DB에 연결하고 기존 테이블을 유지한다.")
    void defaultRunUsesPersistentQueryDatabase() {
        StandardEnvironment environment = loadConfiguration();

        assertThat(environment.getActiveProfiles()).containsExactly("local");
        assertThat(environment.getProperty("datasource.mysql-jpa.main.jdbc-url"))
            .isEqualTo("jdbc:mysql://localhost:3306/loopers");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    @DisplayName("local 프로필만 지정해도 실험 DB와 스키마 검증 설정을 사용한다.")
    void localRunUsesPersistentQueryDatabase() {
        StandardEnvironment environment = loadConfiguration("local");

        assertThat(environment.getProperty("datasource.mysql-jpa.main.jdbc-url"))
            .isEqualTo("jdbc:mysql://localhost:3306/loopers");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    @DisplayName("test 프로필은 테스트용 DB 연결과 기존 스키마 생성 설정을 유지한다.")
    void testProfileKeepsItsDatabaseConfiguration() {
        StandardEnvironment environment = loadConfiguration("test");

        assertThat(environment.getActiveProfiles()).containsExactly("test");
        assertThat(environment.getProperty("datasource.mysql-jpa.main.jdbc-url"))
            .isEqualTo("jdbc:mysql://configuration-test-host:3307");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("create");
    }

    @Test
    @DisplayName("local 프로필은 기동 시 상품 목록 캐시 워밍업을 실행하고, test 프로필은 실행하지 않는다.")
    void warmUpRunsOnlyOutsideTestProfile() {
        assertThat(loadConfiguration("local").getProperty("product.list-cache.warm-up.enabled")).isEqualTo("true");
        assertThat(loadConfiguration("test").getProperty("product.list-cache.warm-up.enabled")).isEqualTo("false");
    }

    private StandardEnvironment loadConfiguration(String... profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        Map<String, Object> properties = new HashMap<>(Map.of(
            "MYSQL_HOST", "configuration-test-host",
            "MYSQL_PORT", "3307"
        ));
        if (profiles.length > 0) {
            properties.put("spring.profiles.active", String.join(",", profiles));
        }
        environment.getPropertySources().addFirst(new MapPropertySource("configurationTest", properties));
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }
}
