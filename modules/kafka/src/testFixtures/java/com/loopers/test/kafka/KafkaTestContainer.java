package com.loopers.test.kafka;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

public class KafkaTestContainer {

    private static final KafkaContainer KAFKA = new KafkaContainer(
        DockerImageName.parse("apache/kafka:3.7.0")
    );

    static {
        KAFKA.start();
    }

    public static String getBootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    public static KafkaContainer getInstance() {
        return KAFKA;
    }
}
