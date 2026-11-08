package com.loopers.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Outbox 저장 원문을 보내는 전용 String/String producer 조립(설계 W7.3-5). 공통 producerFactory·kafkaTemplate 과 W6 JSON 설정은 바꾸지 않고,
 * 이름 붙인 전용 bean 을 qualifier 로만 연결한다. @Primary 를 두지 않는다.
 *
 * <p>저장한 JSON 문자열을 JsonSerializer 로 다시 인코딩하면 인용·이스케이프된 JSON 문자열이 wire 에 실리므로 value 도 StringSerializer 다.
 */
@Configuration
public class OrderOutboxKafkaConfig {

    public static final String ORDER_OUTBOX_PRODUCER_FACTORY = "orderOutboxProducerFactory";
    public static final String ORDER_OUTBOX_KAFKA_TEMPLATE = "orderOutboxKafkaTemplate";

    private static final String ADD_TYPE_HEADERS = "spring.json.add.type.headers";

    /**
     * 공통 producer map 복사 → 상속 retries=3 제거 → 주문 발행 전용 값 덮어쓰기 → key/value serializer 를 String 으로 명시한다.
     * 비트랜잭션 발행이 전제이므로 transactional.id 가 섞이면 값을 지워 이어 가지 않고 기동을 거절한다.
     */
    @Bean(ORDER_OUTBOX_PRODUCER_FACTORY)
    DefaultKafkaProducerFactory<String, String> orderOutboxProducerFactory(KafkaProperties kafkaProperties,
                                                                           OrderEventKafkaProperties properties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties());
        config.remove(ProducerConfig.RETRIES_CONFIG);
        config.putAll(properties.producerProperties());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        if (config.containsKey(ProducerConfig.TRANSACTIONAL_ID_CONFIG)) {
            throw invalid(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "비트랜잭션 producer 에 둘 수 없습니다");
        }
        requireCompatible(config);

        DefaultKafkaProducerFactory<String, String> factory =
            new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new StringSerializer());
        factory.setPhysicalCloseTimeout(properties.physicalCloseTimeoutSeconds());
        if (factory.transactionCapable()) {
            throw invalid(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "전용 producer factory 가 트랜잭션을 지원하도록 구성되었습니다");
        }
        return factory;
    }

    @Bean(ORDER_OUTBOX_KAFKA_TEMPLATE)
    KafkaTemplate<String, String> orderOutboxKafkaTemplate(
        @Qualifier(ORDER_OUTBOX_PRODUCER_FACTORY) ProducerFactory<String, String> orderOutboxProducerFactory) {
        return new KafkaTemplate<>(orderOutboxProducerFactory);
    }

    /**
     * idempotence 와 맞는 acks·in-flight·retries, 전달 예산 관계를 확인한다. type header 설정은 String value 에는 쓰이지 않지만
     * 기존 주문 발행 설정 계약(false)을 그대로 요구한다. 오류에는 값 없이 key 만 남긴다.
     */
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
        return new IllegalStateException("Outbox 주문 확정 producer 설정 오류: " + reason + ". [key = " + key + "]");
    }
}
