package com.loopers.config;

import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.purchase.domain.ConflictingOrderConfirmationException;
import com.loopers.purchase.domain.InvalidOrderConfirmedException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.util.HashMap;
import java.util.Map;

/**
 * 구매 활동 consumer 설정 (W6 D-7, ADR-W6-06). 기존 batch factory(BATCH_LISTENER)는 그대로 두고 이 listener만 record factory를 쓴다.
 * <ul>
 *   <li>record 하나 = DB 트랜잭션 하나 = ack 하나. MANUAL_IMMEDIATE — handler 정상 반환 직후 consumer 스레드에서 offset commit.</li>
 *   <li>auto.offset.reset=earliest — committed offset이 없는 새 group이 latest로 시작하면 그 전의 확정 이벤트를 건너뛴다 (D-1 ③).</li>
 *   <li>fetch는 Kafka 기본값(min 1 byte, 대기 500ms). batch factory의 1MiB·5s를 쓰지 않으므로 작은 테스트에서도 늦게 보이지 않는다.</li>
 *   <li>실패: 재시도로 나아질 수 있으면 같은 자리에서 3회(1s·2s·4s) 뒤 DLT, 계약 위반·충돌은 재시도 없이 바로 DLT.
 *       DLT 발행이 성공하면(recover) 원래 record의 offset n+1을 바로 commit한다(commitRecovered=true). DLT 발행이 실패하면 commit하지 않고 다시 시도한다 —
 *       유실은 없지만 그동안 그 partition의 뒤 record도 멈춘다.</li>
 * </ul>
 */
@Configuration
public class PurchaseActivityKafkaConfig {

    public static final String RECORD_LISTENER = "PURCHASE_ACTIVITY_RECORD_LISTENER";
    public static final String LISTENER_ID = "purchase-activity";
    public static final String DLT_SUFFIX = ".DLT";
    public static final int CONCURRENCY = 3;
    public static final int MAX_RETRIES = 3;

    @Bean(name = RECORD_LISTENER)
    public ConcurrentKafkaListenerContainerFactory<String, String> purchaseActivityListenerFactory(
        KafkaProperties kafkaProperties,
        @Qualifier(KafkaConfig.STRING_TEMPLATE) KafkaTemplate<String, String> stringKafkaTemplate
    ) {
        Map<String, Object> consumerConfig = new HashMap<>(kafkaProperties.buildConsumerProperties());
        consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumerConfig));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setConcurrency(CONCURRENCY);
        factory.setCommonErrorHandler(errorHandler(stringKafkaTemplate));
        return factory;
    }

    private static DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
        // DLT 이름을 기본값에 맡기지 않는다 — Spring Kafka 3.3의 기본은 "원래 topic-dlt"라서, ".DLT"로 만든 topic과 어긋나
        // DLT 발행이 계속 실패했다(그동안 offset을 commit하지 않아 같은 record를 반복 재시도하며 partition이 막혔다). 같은 partition 번호로 보낸다.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
            (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(4_000L);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(InvalidOrderConfirmedException.class, ConflictingOrderConfirmationException.class);
        // MANUAL_IMMEDIATE에서는 recover한 record의 offset을 이 값이 true일 때만 바로 commit한다(기본 false).
        // false면 같은 partition의 다음 record가 ack될 때 함께 넘어간다 — 그 전까지 committed offset이 n에 머문다.
        handler.setCommitRecovered(true);
        return handler;
    }

    /**
     * DLT도 원래 topic과 같은 내구성(RF 3, min.insync 2). 기본 recoverer가 같은 partition 번호로 보내므로 partition 수도 같게(3) 둔다.
     */
    @Bean
    NewTopic orderEventsDeadLetterTopic(@Value("${kafka.topics.order-events}") String topic) {
        return TopicBuilder.name(topic + DLT_SUFFIX)
            .partitions(3)
            .replicas(3)
            .config("min.insync.replicas", "2")
            .build();
    }
}
