package com.loopers.config;

import com.loopers.testcontainers.KafkaTestContainersConfig;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

public class KafkaTestInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        KafkaTestContainersConfig.bootstrapServers();
    }
}
