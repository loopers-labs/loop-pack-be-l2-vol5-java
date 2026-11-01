package com.loopers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 전용 producer factory 의 조립 규칙과 기동 거절을 최소 컨텍스트로 확인한다. producer 를 만들거나 broker 에 연결하지 않는다.
 * 앱 YAML 이 실제로 적용된 값은 {@link OrderEventKafkaContextTest} 가 전체 컨텍스트에서 확인한다.
 */
@DisplayName("주문 확정 전용 producer 는 공통 retries 를 물려받지 않고, 트랜잭션 설정·호환되지 않는 값이면 기동을 거절한다.")
class OrderEventKafkaConfigTest {

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
        .withUserConfiguration(Support.class, OrderEventKafkaConfig.class)
        .withPropertyValues(
            "spring.kafka.bootstrap-servers=localhost:19092",
            "spring.kafka.producer.retries=3",
            "commerce.order-events.producer-properties[acks]=all",
            "commerce.order-events.producer-properties[enable.idempotence]=true",
            "commerce.order-events.producer-properties[max.in.flight.requests.per.connection]=5",
            "commerce.order-events.producer-properties[max.block.ms]=5000",
            "commerce.order-events.producer-properties[request.timeout.ms]=20000",
            "commerce.order-events.producer-properties[delivery.timeout.ms]=30000",
            "commerce.order-events.producer-properties[linger.ms]=0",
            "commerce.order-events.producer-properties[spring.json.add.type.headers]=false");

    @Configuration
    @EnableConfigurationProperties({KafkaProperties.class, OrderEventKafkaProperties.class})
    static class Support {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @DisplayName("공통 producer 의 retries=3 은 지우고, 전용 값과 비트랜잭션 factory 로 조립한다.")
    @Test
    void assemblesNonTransactionalFactoryWithoutInheritedRetries() {
        RUNNER.run(context -> {
            DefaultKafkaProducerFactory<?, ?> factory = context.getBean(OrderEventKafkaConfig.ORDER_EVENT_PRODUCER_FACTORY,
                DefaultKafkaProducerFactory.class);
            assertAll(
                () -> assertThat(context).hasNotFailed(),
                () -> assertThat(factory.getConfigurationProperties()).doesNotContainKey(ProducerConfig.RETRIES_CONFIG)
                    .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                    .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, List.of("localhost:19092")),
                () -> assertThat(factory.transactionCapable()).isFalse()
            );
        });
    }

    @DisplayName("transactional.id 가 전용 설정이나 공통 producer 설정에서 섞이면 값을 지우고 이어 가지 않고 기동을 거절한다.")
    @ParameterizedTest
    @CsvSource({
        "commerce.order-events.producer-properties[transactional.id]=secret-tx",
        "spring.kafka.producer.properties[transactional.id]=secret-tx"
    })
    void rejectsTransactionalId(String property) {
        RUNNER.withPropertyValues(property).run(context -> assertAll(
            () -> assertThat(context).hasFailed(),
            () -> assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("[key = transactional.id]"),
            () -> assertThat(context.getStartupFailure()).rootCause().message().doesNotContain("secret-tx")
        ));
    }

    @DisplayName("idempotence·전달 예산·type header 조합이 맞지 않으면 해당 key 를 밝히고 기동을 거절한다.")
    @ParameterizedTest
    @CsvSource({
        "acks, 1",
        "enable.idempotence, false",
        "max.in.flight.requests.per.connection, 6",
        "delivery.timeout.ms, 10000",
        "spring.json.add.type.headers, true"
    })
    void rejectsIncompatibleValues(String key, String value) {
        RUNNER.withPropertyValues("commerce.order-events.producer-properties[" + key + "]=" + value).run(context -> assertAll(
            () -> assertThat(context).hasFailed(),
            () -> assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("[key = " + key + "]")
        ));
    }

    @DisplayName("topic 이 비었거나 종료 대기가 음수면 기동을 거절한다.")
    @ParameterizedTest
    @CsvSource({"commerce.order-events.topic=", "commerce.order-events.physical-close-timeout-seconds=-1"})
    void rejectsInvalidProperties(String property) {
        RUNNER.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
}
