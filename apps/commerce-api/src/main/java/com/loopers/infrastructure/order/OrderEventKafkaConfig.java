package com.loopers.infrastructure.order;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 주문 확정 이벤트 전용 String producer(docs/week6/decisions.md D-25). 공용 modules/kafka의 `kafkaTemplate`
 * (KafkaTemplate&lt;Object, Object&gt;, value JsonSerializer)은 그대로 두고 따로 만든다.
 *
 * - 공용 JsonSerializer의 ObjectMapper는 Spring 쪽 설정과 달라 시각이 숫자로 나갈 수 있다 — INV-602가 다른 설정 파일에 달리지
 *   않게, JSON은 우리 코드({@link OrderConfirmedJson})가 만들고 여기서는 문자열만 보낸다.
 * - 접속 정보 등 공통 값은 spring.kafka.*(kafka.yml)에서 가져오고, 이 발행만의 사정은 여기서 덮어쓴다:
 *   key·value StringSerializer, acks=all, max.block.ms 5초(D-23). 공용 producer의 규칙으로 번지지 않는다.
 * - 빈 이름을 따로 두고 주입은 @Qualifier로 한다. 타입도 &lt;String, String&gt;이라 공용 빈과 섞이지 않는다.
 */
@Configuration
public class OrderEventKafkaConfig {

    public static final String PRODUCER_FACTORY = "orderEventProducerFactory";
    public static final String KAFKA_TEMPLATE = "orderEventKafkaTemplate";

    @Bean(name = PRODUCER_FACTORY)
    public ProducerFactory<String, String> orderEventProducerFactory(
        KafkaProperties kafkaProperties,
        ObjectProvider<SslBundles> sslBundles,
        @Value("${spring.application.name}") String applicationName,
        @Value("${events.order-confirmed.producer.max-block:5s}") Duration maxBlock
    ) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(sslBundles.getIfAvailable()));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) maxBlock.toMillis());
        // 공용 producer와 client.id가 겹치면 메트릭(JMX) 이름이 부딪힌다.
        props.put(ProducerConfig.CLIENT_ID_CONFIG, applicationName + "-order-confirmed");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean(name = KAFKA_TEMPLATE)
    public KafkaTemplate<String, String> orderEventKafkaTemplate(
        @Qualifier(PRODUCER_FACTORY) ProducerFactory<String, String> orderEventProducerFactory
    ) {
        return new KafkaTemplate<>(orderEventProducerFactory);
    }
}
