package com.loopers.testcontainers;

import org.springframework.context.annotation.Configuration;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class KafkaTestContainersConfig {
    public static final String IMAGE = "apache/kafka:3.8.1";
    private static final KafkaContainer BROKER = new KafkaContainer(DockerImageName.parse(IMAGE))
            .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
            .withStartupTimeout(Duration.ofSeconds(60));

    static {
        BROKER.start();
        System.setProperty("spring.kafka.bootstrap-servers", BROKER.getBootstrapServers());
        System.setProperty("spring.kafka.admin.properties.bootstrap.servers", BROKER.getBootstrapServers());
        Runtime.getRuntime().addShutdownHook(new Thread(BROKER::stop, "week6-kafka-cleanup"));
    }

    public static String bootstrapServers() {
        return BROKER.getBootstrapServers();
    }
}
