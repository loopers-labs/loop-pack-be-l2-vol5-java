package com.loopers;

import com.loopers.infrastructure.payment.PgLookupPolicyProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PgExperimentConfigTest {
    @DisplayName("프로필별 조회 정책 활성화, HTTP timeout과 주기 조회 설정을 유지한다.")
    @ParameterizedTest
    @CsvSource({
        "local,false,false,false,false,3s,true",
        "test,false,false,false,false,1s,false",
        "local+pg-lookup-experiment,true,false,false,false,500ms,false",
        "test+pg-lookup-experiment,true,false,false,false,500ms,false",
        "local+pg-retry-experiment,true,true,false,false,500ms,false",
        "test+pg-retry-experiment,true,true,false,false,500ms,false",
        "local+pg-rate-limit-experiment,true,true,true,false,500ms,false",
        "test+pg-rate-limit-experiment,true,true,true,false,500ms,false",
        "local+pg-time-limit-experiment,true,true,true,true,500ms,false",
        "test+pg-time-limit-experiment,true,true,true,true,500ms,false"
    })
    void preservesPolicyActivationAndHttpBudgetForEachProfile(String profiles, boolean circuit, boolean retry,
                                                              boolean rate, boolean time, String timeout,
                                                              boolean periodicCheck) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("pgConfigTest", Map.of(
            "spring.profiles.active", profiles.replace('+', ','),
            "MYSQL_HOST", "configuration-test-host", "MYSQL_PORT", "3307")));
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        PgLookupPolicyProperties policies = Binder.get(environment)
            .bindOrCreate("payment.pg.lookup-policy", PgLookupPolicyProperties.class);

        assertThat(environment.getProperty("payment.pg.lookup-circuit-breaker.enabled", Boolean.class)).isEqualTo(circuit);
        assertThat(policies.retry().enabled()).isEqualTo(retry);
        assertThat(policies.rateLimiter().enabled()).isEqualTo(rate);
        assertThat(policies.timeLimiter().enabled()).isEqualTo(time);
        assertThat(environment.getProperty("payment.pg.read-timeout")).isEqualTo(timeout);
        assertThat(environment.getProperty("payment.periodic-check.enabled", Boolean.class)).isEqualTo(periodicCheck);
    }
}
