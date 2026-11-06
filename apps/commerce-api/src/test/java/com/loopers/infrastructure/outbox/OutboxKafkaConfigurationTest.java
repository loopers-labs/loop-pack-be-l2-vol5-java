package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxSender;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Configuration and byte-level serializer contract; no producer is connected by these tests. */
class OutboxKafkaConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(OutboxKafkaConfiguration.class)
        .withPropertyValues("spring.kafka.bootstrap-servers=127.0.0.1:1",
            "spring.kafka.producer.acks=all", "spring.kafka.producer.retries=3",
            "spring.kafka.producer.properties.enable.idempotence=true",
            "spring.kafka.producer.properties.max.block.ms=1000",
            "spring.kafka.producer.properties.request.timeout.ms=3000",
            "spring.kafka.producer.properties.delivery.timeout.ms=10000");

    @Test
    void storedJsonIsEncodedOnceAsUtf8WithoutTypeHeaders() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(OutboxSender.class);
            var factory = factory(context);
            String payload = "{ \"eventId\": \"order-confirmed:v1:42\", \"note\": \"한글\" }\n";
            var headers = new RecordHeaders();

            assertThat(factory.getKeySerializer()).isInstanceOf(StringSerializer.class);
            assertThat(factory.getValueSerializer()).isInstanceOf(StringSerializer.class);
            assertThat(factory.getKeySerializer().serialize("saved-topic", "42"))
                .isEqualTo("42".getBytes(StandardCharsets.UTF_8));
            assertThat(factory.getValueSerializer().serialize("saved-topic", headers, payload))
                .isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
            assertThat(headers).isEmpty();
            assertThat(context.getBean(OutboxSender.class)).isInstanceOf(KafkaOutboxSender.class);
            assertThat(context.getBean("outboxKafkaTemplate", KafkaTemplate.class).getProducerFactory()).isSameAs(factory);
        });
    }

    @Test
    void inheritsExistingProducerBudgetsAndAcknowledgementSettings() {
        runner.run(context -> {
            var properties = factory(context).getConfigurationProperties();
            assertThat(properties.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
            assertThat(properties.get(ProducerConfig.RETRIES_CONFIG)).isEqualTo(3);
            assertThat(properties.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isEqualTo("true");
            assertThat(properties.get(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo("1000");
            assertThat(properties.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG)).isEqualTo("3000");
            assertThat(properties.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)).isEqualTo("10000");
        });
    }

    @SuppressWarnings("unchecked")
    private DefaultKafkaProducerFactory<String, String> factory(AssertableApplicationContext context) {
        return context.getBean("outboxProducerFactory", DefaultKafkaProducerFactory.class);
    }
}
