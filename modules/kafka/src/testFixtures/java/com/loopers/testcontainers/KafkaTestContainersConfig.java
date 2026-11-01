package com.loopers.testcontainers;

import org.springframework.context.annotation.Configuration;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** 테스트용 단일 broker(KRaft). producer·consumer·admin 이 모두 이 컨테이너의 실제 주소를 쓰도록 클래스 초기화 때 주입한다. */
@Configuration
public class KafkaTestContainersConfig {

    private static final KafkaContainer kafkaContainer = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    static {
        kafkaContainer.start();

        System.setProperty("spring.kafka.bootstrap-servers", kafkaContainer.getBootstrapServers());
    }

    public static String bootstrapServers() {
        return kafkaContainer.getBootstrapServers();
    }
}
