package com.loopers.config;

import com.loopers.infrastructure.order.support.W6PreparationContextInitializer;
import com.loopers.infrastructure.order.support.W6KafkaOrderEventPublisher;
import com.loopers.infrastructure.order.support.W6OrderConfirmedEventListener;
import com.loopers.infrastructure.order.support.W6OrderEventKafkaConfig;
import com.loopers.infrastructure.order.support.W6OrderEventPublisher;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
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
 * W6 조립 연결(설계 W7.4-4). 제품에서 직접 발행 조립이 빠졌으므로 W6 준비 컨텍스트 초기화기로 보존 W6 조립을 확인한다.
 * W7 String sender/template 검증으로의 최종 전환은 O5/O10 후속 단위다. 이 테스트를 W7 전송 검증으로 기록하지 않는다.
 *
 * <p>실제 adapter 를 쓰는 전체 앱 컨텍스트에서 전용 producer 의 유효 설정과 bean 연결을 확인한다(P5 설정 부분).
 * producer 생성·전송을 하지 않으므로 broker 연결이나 실제 record 근거가 아니다. fake override 를 두지 않아 실제 adapter 가 그대로 남는다.
 */
@SpringBootTest
@ContextConfiguration(initializers = W6PreparationContextInitializer.class)
@DisplayName("실제 adapter 컨텍스트는 앱 YAML 의 전용 producer 값을 쓰고, 공통 Kafka bean 과 전용 bean 을 섞지 않는다.")
class OrderEventKafkaContextTest {

    @Autowired private ApplicationContext context;
    @Autowired private Environment environment;
    @Autowired private W6OrderEventPublisher port;
    @Autowired private W6OrderConfirmedEventListener listener;
    @Autowired @Qualifier(W6OrderEventKafkaConfig.ORDER_EVENT_PRODUCER_FACTORY)
    private DefaultKafkaProducerFactory<String, Object> orderEventProducerFactory;
    @Autowired @Qualifier(W6OrderEventKafkaConfig.ORDER_EVENT_KAFKA_TEMPLATE)
    private KafkaTemplate<String, Object> orderEventKafkaTemplate;
    @Autowired @Qualifier("producerFactory") private ProducerFactory<Object, Object> commonProducerFactory;
    @Autowired @Qualifier("kafkaTemplate") private KafkaTemplate<Object, Object> commonKafkaTemplate;

    @DisplayName("전용 factory 의 실제 config map 은 retries 를 물려받지 않고 설계 초기값을 가지며 비트랜잭션이다.")
    @Test
    void dedicatedFactoryHasEffectiveDesignValues() {
        Map<String, Object> config = orderEventProducerFactory.getConfigurationProperties();
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
                .containsEntry(JsonSerializer.ADD_TYPE_INFO_HEADERS, "false"),
            () -> assertThat(orderEventProducerFactory.transactionCapable()).isFalse(),
            () -> assertThat(orderEventProducerFactory.getPhysicalCloseTimeout()).isEqualTo(Duration.ofSeconds(15))
        );
    }

    @DisplayName("serializer 는 String key·JSON value 이고, 실제 적용된 JSON serializer 는 type header 를 붙이지 않는다.")
    @Test
    void serializersComeFromConfigMap() {
        // raw supplier 가 아니라 factory 가 producer 에 넘길 때처럼 config map 으로 configure 한 serializer 를 본다.
        var keySerializer = orderEventProducerFactory.getKeySerializer();
        var valueSerializer = orderEventProducerFactory.getValueSerializer();

        assertAll(
            () -> assertThat(keySerializer).isInstanceOf(StringSerializer.class),
            () -> assertThat(valueSerializer).isInstanceOf(JsonSerializer.class),
            () -> assertThat(((JsonSerializer<?>) valueSerializer).isAddTypeInfo()).isFalse()
        );
    }

    @DisplayName("발행 포트는 실제 adapter 하나이고 전용 template·factory 로 이어지며, 공통 template 은 기존 공통 factory 와 retries=3 을 그대로 쓴다.")
    @Test
    void beansAreWiredByQualifier() {
        assertAll(
            () -> assertThat(context.getBeansOfType(W6OrderEventPublisher.class)).containsOnlyKeys("kafkaOrderEventPublisher"),
            () -> assertThat(port).isInstanceOf(W6KafkaOrderEventPublisher.class),
            () -> assertThat(ReflectionTestUtils.getField(listener, "publisher")).isSameAs(port),
            () -> assertThat(ReflectionTestUtils.getField(port, "template")).isSameAs(orderEventKafkaTemplate),
            () -> assertThat(ReflectionTestUtils.getField(port, "topic")).isEqualTo("order-events"),
            () -> assertThat(orderEventKafkaTemplate.getProducerFactory()).isSameAs(orderEventProducerFactory),
            () -> assertThat(commonKafkaTemplate.getProducerFactory()).isSameAs(commonProducerFactory)
                .isNotSameAs(orderEventProducerFactory),
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
