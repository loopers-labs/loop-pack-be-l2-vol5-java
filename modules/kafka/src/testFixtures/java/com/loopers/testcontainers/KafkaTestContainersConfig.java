package com.loopers.testcontainers;

import org.springframework.context.annotation.Configuration;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Configuration
public class KafkaTestContainersConfig {

    private static final KafkaContainer kafkaContainer;

    static {
        kafkaContainer = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));
        kafkaContainer.start();

        // kafka.yml local·test 프로필의 localhost:19092와 admin kafka:9092를 모두 같은 컨테이너 주소로 덮는다.
        String bootstrapServers = kafkaContainer.getBootstrapServers();
        System.setProperty("spring.kafka.bootstrap-servers", bootstrapServers);
        System.setProperty("spring.kafka.admin.properties.bootstrap.servers", bootstrapServers);
    }
}
