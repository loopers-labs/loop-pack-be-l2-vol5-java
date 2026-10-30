package com.loopers.testcontainers;

import org.springframework.context.annotation.Configuration;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Configuration
public class KafkaTestContainersConfig {

    // 애플리케이션이 쓰는 kafka-clients와 같은 버전의 단일 broker다.
    private static final KafkaContainer kafkaContainer;

    static {
        kafkaContainer = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));
        kafkaContainer.start();

        // producer·consumer·admin이 모두 이 주소를 쓰도록 공통 bootstrap 주소만 덮어쓴다.
        System.setProperty("spring.kafka.bootstrap-servers", kafkaContainer.getBootstrapServers());
    }
}
