package com.loopers.job.ranking;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyRankingProfileTest {
    @ParameterizedTest
    @ValueSource(strings = {"weekly-ranking", "local,weekly-ranking", "test,weekly-ranking"})
    void restartProfileKeepsAllDatabaseInitializationDisabledAfterConfigImports(String profiles) {
        new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=" + profiles, "spring.config.location=classpath:/application.yml")
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(DataSource.class);
                var environment = context.getEnvironment();
                assertThat(environment.getActiveProfiles()).containsExactly(profiles.split(","));
                assertThat(environment.getProperty("spring.application.name")).isEqualTo("commerce-batch");
                assertThat(environment.getProperty("spring.batch.job.name")).isEqualTo("weeklyRanking");
                assertThat(environment.getProperty("spring.batch.jdbc.initialize-schema")).isEqualTo("never");
                assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
                assertThat(environment.getProperty("spring.sql.init.mode")).isEqualTo("never");
            });
    }
}
