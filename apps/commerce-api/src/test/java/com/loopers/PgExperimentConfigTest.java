package com.loopers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PgExperimentConfigTest {
    @DisplayName("기본 프로필과 회로 실험의 활성화, HTTP timeout과 주기 조회 설정을 유지한다.")
    @ParameterizedTest
    @CsvSource({
        "local,false,3s,true",
        "test,false,1s,false",
        "local+pg-lookup-experiment,true,500ms,false",
        "test+pg-lookup-experiment,true,500ms,false"
    })
    void preservesCircuitActivationAndHttpBudgetForEachProfile(String profiles, boolean circuit,
                                                              String timeout, boolean periodicCheck) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("pgConfigTest", Map.of(
            "spring.profiles.active", profiles.replace('+', ','),
            "MYSQL_HOST", "configuration-test-host", "MYSQL_PORT", "3307")));
        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        assertThat(environment.getProperty("payment.pg.lookup-circuit-breaker.enabled", Boolean.class)).isEqualTo(circuit);
        assertThat(environment.getProperty("payment.pg.read-timeout")).isEqualTo(timeout);
        assertThat(environment.getProperty("payment.periodic-check.enabled", Boolean.class)).isEqualTo(periodicCheck);
    }
}
