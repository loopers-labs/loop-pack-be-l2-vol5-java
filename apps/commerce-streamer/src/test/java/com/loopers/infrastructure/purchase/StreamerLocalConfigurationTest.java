package com.loopers.infrastructure.purchase;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class StreamerLocalConfigurationTest {
    @Test
    void localRestartValidatesSchemaInsteadOfErasingCompletionFacts() {
        new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=local")
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> {
                assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
                assertThat(context.getEnvironment().getProperty("spring.kafka.admin.properties.bootstrap.servers"))
                    .isEqualTo(context.getEnvironment().getProperty("spring.kafka.bootstrap-servers"));
                assertThat(context.getEnvironment().getProperty("spring.application.name")).isEqualTo("commerce-streamer");
            });
    }
}
