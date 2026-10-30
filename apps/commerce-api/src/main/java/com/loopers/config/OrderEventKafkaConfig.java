package com.loopers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * 주문 확정 이벤트 발행에 쓰는 bean 을 조립한다. 공통 producerFactory·kafkaTemplate 은 바꾸지 않고 이름 붙인 전용 bean 을 따로 둔다.
 * 전용 bean 을 @Primary 로 두지 않으며, 주문 발행 adapter 만 qualifier 로 전용 template 을 쓴다.
 */
@Configuration
public class OrderEventKafkaConfig {

    public static final String ORDER_CONFIRMATION_CLOCK = "orderConfirmationClock";
    public static final String ORDER_EVENT_PRODUCER_FACTORY = "orderEventProducerFactory";
    public static final String ORDER_EVENT_KAFKA_TEMPLATE = "orderEventKafkaTemplate";

    private static final String ADD_TYPE_HEADERS = JsonSerializer.ADD_TYPE_INFO_HEADERS;

    /** 최초 PG 확정 시각 전용 Clock. 테스트는 이 bean 만 고정 Clock 으로 바꾸며, 다른 생성·감사 시각은 이 Clock 을 쓰지 않는다. */
    @Bean(ORDER_CONFIRMATION_CLOCK)
    Clock orderConfirmationClock() {
        return Clock.systemUTC();
    }

    /**
     * 공통 producer map 을 복사한 뒤 retries=3 상속을 지워 전달 제한 시간 안의 client 기본 재시도를 쓰고, 전용 값을 덮어쓴다.
     * 비트랜잭션 발행이 전제이므로 transactional.id 가 섞이면 값을 지워 이어 가지 않고 기동을 거절한다.
     * serializer 는 인스턴스로 넘기고 type header 는 setter 가 아닌 config map 으로만 정한다(JsonSerializer 는 둘의 혼용을 거절한다).
     */
    @Bean(ORDER_EVENT_PRODUCER_FACTORY)
    DefaultKafkaProducerFactory<String, Object> orderEventProducerFactory(KafkaProperties kafkaProperties,
                                                                          OrderEventKafkaProperties properties,
                                                                          ObjectMapper objectMapper) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties());
        config.remove(ProducerConfig.RETRIES_CONFIG);
        config.putAll(properties.producerProperties());
        if (config.containsKey(ProducerConfig.TRANSACTIONAL_ID_CONFIG)) {
            throw invalid(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "비트랜잭션 producer 에 둘 수 없습니다");
        }
        requireCompatible(config);

        DefaultKafkaProducerFactory<String, Object> factory =
            new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new JsonSerializer<>(objectMapper));
        factory.setPhysicalCloseTimeout(properties.physicalCloseTimeoutSeconds());
        if (factory.transactionCapable()) {
            throw invalid(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "전용 producer factory 가 트랜잭션을 지원하도록 구성되었습니다");
        }
        return factory;
    }

    @Bean(ORDER_EVENT_KAFKA_TEMPLATE)
    KafkaTemplate<String, Object> orderEventKafkaTemplate(
        @Qualifier(ORDER_EVENT_PRODUCER_FACTORY) ProducerFactory<String, Object> orderEventProducerFactory) {
        return new KafkaTemplate<>(orderEventProducerFactory);
    }

    /** idempotence 와 맞는 acks·in-flight·retries, 전달 예산 관계, type header 미발행을 확인한다. 오류에는 값 없이 key 만 남긴다. */
    private static void requireCompatible(Map<String, Object> config) {
        if (!"all".equals(text(config, ProducerConfig.ACKS_CONFIG))) {
            throw invalid(ProducerConfig.ACKS_CONFIG, "all 이어야 합니다");
        }
        if (!"true".equals(text(config, ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG))) {
            throw invalid(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true 여야 합니다");
        }
        long inFlight = number(config, ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION);
        if (inFlight < 1 || inFlight > 5) {
            throw invalid(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "idempotence 와 맞게 1~5 여야 합니다");
        }
        if (config.containsKey(ProducerConfig.RETRIES_CONFIG) && number(config, ProducerConfig.RETRIES_CONFIG) < 1) {
            throw invalid(ProducerConfig.RETRIES_CONFIG, "idempotence 와 맞게 양수여야 합니다");
        }
        long delivery = number(config, ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG);
        long request = number(config, ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG);
        long linger = number(config, ProducerConfig.LINGER_MS_CONFIG);
        if (delivery < request + linger) {
            throw invalid(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "request.timeout.ms + linger.ms 이상이어야 합니다");
        }
        number(config, ProducerConfig.MAX_BLOCK_MS_CONFIG);
        if (!"false".equals(text(config, ADD_TYPE_HEADERS))) {
            throw invalid(ADD_TYPE_HEADERS, "false 여야 합니다");
        }
    }

    private static String text(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null) {
            throw invalid(key, "필수입니다");
        }
        return String.valueOf(value).trim();
    }

    private static long number(Map<String, Object> config, String key) {
        try {
            return Long.parseLong(text(config, key));
        } catch (NumberFormatException failure) {
            throw invalid(key, "정수여야 합니다");
        }
    }

    private static IllegalStateException invalid(String key, String reason) {
        return new IllegalStateException("주문 확정 이벤트 producer 설정 오류: " + reason + ". [key = " + key + "]");
    }
}
