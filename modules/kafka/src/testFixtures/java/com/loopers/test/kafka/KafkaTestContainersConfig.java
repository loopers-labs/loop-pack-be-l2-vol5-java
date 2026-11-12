package com.loopers.test.kafka;

import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaTestContainersConfig {

    static {
        KafkaTestContainer.getInstance();
        System.setProperty("spring.kafka.bootstrap-servers", KafkaTestContainer.getBootstrapServers());
    }
}
