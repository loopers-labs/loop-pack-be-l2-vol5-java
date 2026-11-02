package com.loopers.testcontainers;

import org.springframework.context.annotation.Configuration;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 단일 Kafka broker (KRaft). 호스트에서 접근하는 실제 bootstrap 주소를 producer·consumer·admin 공통으로 주입한다.
 * 컨테이너는 JVM 종료 시 Testcontainers 가 정리한다. topic·group 은 각 테스트가 고유 이름으로 만든다.
 */
@Configuration
public class KafkaTestContainersConfig {

    private static final KafkaContainer kafkaContainer;

    static {
        kafkaContainer = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));
        kafkaContainer.start();

        System.setProperty("spring.kafka.bootstrap-servers", kafkaContainer.getBootstrapServers());
    }

    public static String bootstrapServers() {
        return kafkaContainer.getBootstrapServers();
    }
}
