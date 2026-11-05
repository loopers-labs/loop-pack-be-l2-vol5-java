package com.loopers.job.reconcile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

// 대사 Job 테스트 전용 대역. 운영 코드에는 분기를 두지 않고 여기서 바꿔 끼운다.
@TestConfiguration
public class ReconcileTestConfig {
    @Bean
    @Primary
    MutableClock mutableReconcileClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    FailingCommandTemplate failingCommandTemplate(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new FailingCommandTemplate(new DefaultKafkaProducerFactory<>(props));
    }

    public static class MutableClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<Instant>(Instant.EPOCH);

        public void set(Instant instant) {
            current.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return zone.equals(getZone()) ? this : Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }

    // 무장한 주문 ID의 명령 send 결과만 실패로 끝낸다. 나머지는 실제 broker로 보낸다.
    public static class FailingCommandTemplate extends KafkaTemplate<String, String> {
        public static final String INJECTED_FAILURE = "주입된 재발행 명령 send 실패";

        private final AtomicReference<String> failingKey = new AtomicReference<String>();

        FailingCommandTemplate(DefaultKafkaProducerFactory<String, String> producerFactory) {
            super(producerFactory);
        }

        public void failFor(long orderId) {
            failingKey.set(String.valueOf(orderId));
        }

        public void disarm() {
            failingKey.set(null);
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
            if (key.equals(failingKey.get())) {
                return CompletableFuture.failedFuture(new KafkaException(INJECTED_FAILURE));
            }
            return super.send(topic, key, data);
        }
    }
}
