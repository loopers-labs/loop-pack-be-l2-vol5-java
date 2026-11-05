package com.loopers.infrastructure.order;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * commerce-api의 실제 설정 파일(application.yml + 공통 kafka.yml)로 producer 직렬화를 확인한다.
 * Outbox에 저장한 JSON 문자열을 그대로 보내야 하므로 값은 문자열 그대로 직렬화한다.
 * 공통 JsonSerializer가 남아 있으면 문자열을 한 번 더 따옴표로 감싸 보낸다.
 */
class OrderEventProducerConfigurationTest {

    @ParameterizedTest
    @ValueSource(strings = {"local", "test"})
    void producerSendsStoredPayloadStringAsIs(String profile) {
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=" + profile)
            .withUserConfiguration(KafkaPropertiesOnly.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                var producer = context.getBean(KafkaProperties.class).buildProducerProperties();

                assertThat(producer)
                    .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                    .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KafkaProperties.class)
    static class KafkaPropertiesOnly {
    }
}
