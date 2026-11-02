package com.loopers.testcontainers;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 컨텍스트가 만들어지기 전에 Kafka 주소를 테스트 broker로 바꾼다. producer와 admin 둘 다 같은 실제 주소를 쓴다
 * (docs/week6/decisions.md D-24 — kafka.yml test 프로필은 admin에 kafka:9092를 따로 넣어 둔다).
 * 등록: src/test/resources/META-INF/spring.factories
 */
public class KafkaTestContainersInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        String bootstrapServers = KafkaTestContainers.bootstrapServers();
        TestPropertyValues.of(
            "spring.kafka.bootstrap-servers=" + bootstrapServers,
            "spring.kafka.admin.properties.bootstrap.servers=" + bootstrapServers
        ).applyTo(applicationContext);
    }
}
