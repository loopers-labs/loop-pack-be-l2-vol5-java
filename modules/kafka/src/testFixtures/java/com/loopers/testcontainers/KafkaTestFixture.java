package com.loopers.testcontainers;

import java.time.Duration;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

// 테스트가 직접 시작·종료하고 실제 bootstrap 주소를 공유하는 격리 Kafka fixture.
public final class KafkaTestFixture {

    private KafkaTestFixture() {
    }

    public static KafkaContainer create() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"))
            .withStartupTimeout(Duration.ofMinutes(2));
    }
}
