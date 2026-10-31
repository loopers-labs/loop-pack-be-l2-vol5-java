package com.loopers.confg.kafka;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/** 실제 kafka.yml의 profile·Spring 바인딩을 검증한다. broker·consumer는 시작하지 않는다. */
class KafkaClientConfigurationTest {
    private static final List<String> LOCAL_SERVERS = List.of(
        "localhost:19092", "localhost:29092", "localhost:39092"
    );

    @ParameterizedTest
    @ValueSource(strings = {"local", "test"})
    void producerConsumerAndAdminUseTheSameThreeBrokerAddresses(String profile) {
        context(profile).run(context -> {
            assertThat(context).hasNotFailed();
            KafkaProperties properties = context.getBean(KafkaProperties.class);

            assertSoftly(softly -> {
                softly.assertThat(properties.buildProducerProperties())
                    .containsEntry(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, LOCAL_SERVERS);
                softly.assertThat(properties.buildConsumerProperties())
                    .containsEntry(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, LOCAL_SERVERS);
                softly.assertThat(properties.buildAdminProperties(null))
                    .containsEntry(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, LOCAL_SERVERS);
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test"})
    void consumerUsesByteArrayValuesAndExplicitlyDisablesAutoCommit(String profile) {
        context(profile).run(context -> {
            assertThat(context).hasNotFailed();
            var configuration = context.getBean(KafkaProperties.class).buildConsumerProperties();

            assertSoftly(softly -> {
                softly.assertThat(configuration)
                    .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                    .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
                softly.assertThat(configuration).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
                softly.assertThat(configuration).doesNotContainKey("enable-auto-commit");
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test"})
    void producerWaitsAtMostFiveSecondsForMetadataAndKeepsClientRetryDefault(String profile) {
        context(profile).run(context -> {
            assertThat(context).hasNotFailed();
            var configuration = context.getBean(KafkaProperties.class).buildProducerProperties();

            assertSoftly(softly -> {
                softly.assertThat(configuration).containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
                // 재시도 횟수를 제한하지 않고 delivery.timeout.ms(클라이언트 기본 120초)가 실패 시점을 정한다.
                softly.assertThat(configuration).doesNotContainKey(ProducerConfig.RETRIES_CONFIG);
            });
        });
    }

    @Test
    void bootstrapOverrideAlsoAppliesToAdmin() {
        context("test").withPropertyValues("spring.kafka.bootstrap-servers=localhost:49092").run(context -> {
            assertThat(context).hasNotFailed();
            KafkaProperties properties = context.getBean(KafkaProperties.class);
            List<String> servers = List.of("localhost:49092");

            assertSoftly(softly -> {
                softly.assertThat(properties.buildProducerProperties())
                    .containsEntry(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, servers);
                softly.assertThat(properties.buildConsumerProperties())
                    .containsEntry(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, servers);
                softly.assertThat(properties.buildAdminProperties(null))
                    .containsEntry(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, servers);
            });
        });
    }

    private ApplicationContextRunner context(String profile) {
        return new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues("spring.config.location=classpath:kafka.yml", "spring.profiles.active=" + profile,
                "spring.application.name=kafka-config-test");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KafkaProperties.class)
    static class PropertiesConfiguration {}
}
