package com.loopers.config;

import com.loopers.domain.order.OrderOutboxSender;
import com.loopers.infrastructure.order.KafkaOrderOutboxSender;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * O5 설정/컨텍스트(설계 W7.4-4 전환표). 정상 W7 앱 컨텍스트에서 Outbox 전용 String producer 의 유효 설정과 sender 까지의 qualifier 연결,
 * 공통 Kafka bean 불변을 확인한다. producer 생성·전송을 하지 않으므로 broker 연결이나 실제 record 근거가 아니다.
 * 이 클래스의 W6 JSON producer 기대는 구현 03 에서 W6PreparationContextIntegrationTest 로 옮겼다.
 */
@SpringBootTest
@DisplayName("정상 컨텍스트는 앱 YAML 의 주문 발행 값을 Outbox 전용 String producer 에 쓰고, 공통 Kafka bean 과 섞지 않는다.")
class OrderEventKafkaContextTest {

    @Autowired private ApplicationContext context;
    @Autowired private Environment environment;
    @Autowired private OrderOutboxSender sender;
    @Autowired @Qualifier(OrderOutboxKafkaConfig.ORDER_OUTBOX_PRODUCER_FACTORY)
    private DefaultKafkaProducerFactory<String, String> outboxProducerFactory;
    @Autowired @Qualifier(OrderOutboxKafkaConfig.ORDER_OUTBOX_KAFKA_TEMPLATE)
    private KafkaTemplate<String, String> outboxKafkaTemplate;
    @Autowired @Qualifier("producerFactory") private ProducerFactory<Object, Object> commonProducerFactory;
    @Autowired @Qualifier("kafkaTemplate") private KafkaTemplate<Object, Object> commonKafkaTemplate;

    @DisplayName("전용 factory 의 실제 config map 은 retries 를 물려받지 않고 설계 초기값·String serializer 를 가지며 비트랜잭션이다.")
    @Test
    void dedicatedFactoryHasEffectiveDesignValues() {
        Map<String, Object> config = outboxProducerFactory.getConfigurationProperties();
        assertAll(
            () -> assertThat(config).doesNotContainKey(ProducerConfig.RETRIES_CONFIG)
                .doesNotContainKey(ProducerConfig.TRANSACTIONAL_ID_CONFIG),
            () -> assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
                .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5")
                .containsEntry(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, "500")
                .containsEntry(ProducerConfig.RETRY_BACKOFF_MAX_MS_CONFIG, "1000")
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000")
                .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "20000")
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "30000")
                .containsEntry(ProducerConfig.LINGER_MS_CONFIG, "0")
                .containsEntry(JsonSerializer.ADD_TYPE_INFO_HEADERS, "false")
                .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class),
            () -> assertThat(outboxProducerFactory.getKeySerializer()).isInstanceOf(StringSerializer.class),
            () -> assertThat(outboxProducerFactory.getValueSerializer()).as("저장 JSON 을 다시 인코딩하지 않음")
                .isInstanceOf(StringSerializer.class),
            () -> assertThat(outboxProducerFactory.transactionCapable()).isFalse(),
            () -> assertThat(outboxProducerFactory.getPhysicalCloseTimeout()).isEqualTo(Duration.ofSeconds(15))
        );
    }

    @DisplayName("sender 는 실제 Kafka sender 하나이고 전용 template·factory 로 이어지며, 공통 template 은 기존 공통 factory 와 retries=3 을 그대로 쓴다.")
    @Test
    void beansAreWiredByQualifier() {
        assertAll(
            () -> assertThat(context.getBeansOfType(OrderOutboxSender.class)).containsOnlyKeys("kafkaOrderOutboxSender"),
            () -> assertThat(sender).isInstanceOf(KafkaOrderOutboxSender.class),
            () -> assertThat(ReflectionTestUtils.getField(sender, "template")).isSameAs(outboxKafkaTemplate),
            () -> assertThat(ReflectionTestUtils.getField(sender, "topic")).isEqualTo("order-events"),
            () -> assertThat(outboxKafkaTemplate.getProducerFactory()).isSameAs(outboxProducerFactory),
            () -> assertThat(context.containsBean("orderEventKafkaTemplate")).as("W6 JSON template 없음").isFalse(),
            () -> assertThat(context.containsBean("orderEventProducerFactory")).as("W6 JSON factory 없음").isFalse(),
            () -> assertThat(commonKafkaTemplate.getProducerFactory()).isSameAs(commonProducerFactory)
                .isNotSameAs(outboxProducerFactory),
            () -> assertThat(commonProducerFactory.getConfigurationProperties()).containsEntry(ProducerConfig.RETRIES_CONFIG, 3)
        );
    }

    @DisplayName("HTTP 는 graceful 종료이고 lifecycle phase 대기는 30초로 명시된다.")
    @Test
    void shutdownSettingsAreExplicit() {
        assertAll(
            () -> assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful"),
            () -> assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("30s")
        );
    }
}
