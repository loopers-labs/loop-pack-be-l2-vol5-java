package com.loopers.infrastructure.order.messaging;

import com.loopers.domain.order.gateway.OrderConfirmedSender;
import com.loopers.domain.order.vo.OrderConfirmedSendResult;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * relay 전용 producer. 공용 KafkaTemplate(JsonSerializer)은 저장된 JSON 문자열을 다시 감싸므로 따로 둠 (7주차 D-107).
 * bean 으로 등록하면 Boot 의 기본 KafkaTemplate 이 빠지므로 안에서 만들고 직접 닫음
 */
@Component
public class KafkaOrderConfirmedSender implements OrderConfirmedSender {

    /** 나열한 것만 영구로 보고 나머지는 일시적으로 다룸. 모르는 오류로 BLOCKED 가 되면 사람이 재개해야 함 (D-106) */
    private static final List<Class<? extends Throwable>> PERMANENT_FAILURES = List.of(
        RecordTooLargeException.class,
        SerializationException.class,
        InvalidTopicException.class,
        AuthorizationException.class
    );

    private final DefaultKafkaProducerFactory<String, String> producerFactory;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final Duration sendTimeout;

    @Autowired
    public KafkaOrderConfirmedSender(
        KafkaProperties kafkaProperties,
        OrderOutboxRelayProducerProperties properties,
        @Value("${order-event.topic}") String topic
    ) {
        this.producerFactory = new DefaultKafkaProducerFactory<>(producerConfig(kafkaProperties, properties));
        this.kafkaTemplate = new KafkaTemplate<>(producerFactory);
        this.topic = topic;
        this.sendTimeout = properties.sendTimeout();
    }

    KafkaOrderConfirmedSender(KafkaTemplate<String, String> kafkaTemplate, String topic, Duration sendTimeout) {
        this.producerFactory = null;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.sendTimeout = sendTimeout;
    }

    private static Map<String, Object> producerConfig(KafkaProperties kafkaProperties, OrderOutboxRelayProducerProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, kafkaProperties.getClientId() + "-outbox-relay");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // broker 확인이 PUBLISHED 의 근거라 기본값에 기대지 않고 명시함
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) properties.requestTimeout().toMillis());
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) properties.deliveryTimeout().toMillis());
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, properties.maxBlock().toMillis());
        return config;
    }

    @Override
    public OrderConfirmedSendResult send(String messageKey, String payload) {
        try {
            RecordMetadata metadata = kafkaTemplate.send(topic, messageKey, payload)
                .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .getRecordMetadata();
            return new OrderConfirmedSendResult.Acked(metadata.topic(), metadata.partition(), metadata.offset());
        } catch (ExecutionException e) {
            return classify(e.getCause());
        } catch (TimeoutException e) {
            return new OrderConfirmedSendResult.Transient("로컬 대기 timeout " + sendTimeout + ". 수신 여부 모름");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new OrderConfirmedSendResult.Transient(describe(e));
        } catch (RuntimeException e) {
            return classify(e);
        }
    }

    /** spring-kafka 가 원인을 KafkaProducerException 으로 감싸므로 원인 사슬 전체를 봄 */
    static OrderConfirmedSendResult classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            Throwable current = cause;
            if (PERMANENT_FAILURES.stream().anyMatch(type -> type.isInstance(current))) {
                return new OrderConfirmedSendResult.Permanent(describe(current));
            }
        }
        return new OrderConfirmedSendResult.Transient(describe(rootOf(failure)));
    }

    private static Throwable rootOf(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private static String describe(Throwable failure) {
        return failure.getClass().getName() + ": " + failure.getMessage();
    }

    Map<String, Object> producerConfiguration() {
        return producerFactory.getConfigurationProperties();
    }

    @PreDestroy
    void close() {
        if (producerFactory != null) {
            producerFactory.destroy();
        }
    }
}
