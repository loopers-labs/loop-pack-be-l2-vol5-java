package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.ConflictingOrderConfirmationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;

/**
 * 주문 확정 listener의 오류 처리 (ADR 0041, 0047~0049, 0053).
 * <ul>
 *   <li>메시지 오류·사실 충돌: 재시도 없이 DLQ로 보내고, 전송 성공 뒤에만 원본 offset을 넘긴다.</li>
 *   <li>그 밖의 오류(DB 등): 간격을 늘리며(기본 1초부터 2배, 최대 30초) 총 대기 시간(기본 5분)까지 다시 시도한다.
 *       그 안에 풀리면 스스로 이어 가고, 넘기면 listener 전체를 멈춘다. 실제 poll 사이 시간은
 *       DB 처리·재시도 대기 등을 합쳐 max.poll.interval.ms(주문용 기본 5분)와 비교해야 한다.</li>
 *   <li>DLQ 전송 실패: listener 전체를 멈춘다. 사람이 원인을 해결하고 같은 group으로 다시 시작한다.</li>
 * </ul>
 * 멈출 때 recoverer가 예외로 끝나므로 원본 offset은 commit되지 않는다.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderConfirmedRetryProperties.class)
@ConditionalOnProperty(prefix = "commerce.order-events.consumer", name = "enabled", havingValue = "true")
public class OrderConfirmedKafkaErrorHandling {
    public static final String DEAD_LETTER_SUFFIX = "-dlt";
    // 이 값은 최소 대기다. 기본 delivery timeout 120초 + buffer 5초로 실제 결과 대기 예산은 125초다.
    // 10초 상한을 보장하는 설정이 아니며, 이 대기 시간을 받아들인 이유는 ADR 0055에 둔다.
    private static final Duration DEAD_LETTER_SEND_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public ProducerFactory<String, byte[]> orderConfirmedDeadLetterProducerFactory(KafkaProperties kafkaProperties) {
        var settings = new HashMap<String, Object>(kafkaProperties.buildProducerProperties());
        // 원본 bytes를 그대로 보관한다. 공통 producer의 JsonSerializer를 쓰면 bytes가 JSON 문자열로 바뀐다.
        settings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        settings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new DefaultKafkaProducerFactory<>(settings);
    }

    @Bean(name = OrderConfirmedKafkaConfiguration.ERROR_HANDLER)
    public CommonErrorHandler orderConfirmedKafkaErrorHandler(
        ProducerFactory<String, byte[]> orderConfirmedDeadLetterProducerFactory,
        KafkaListenerEndpointRegistry registry,
        OrderConfirmedRetryProperties retry
    ) {
        // partition -1: 원본 key(orderId)로 DLQ의 partition을 정한다.
        var deadLetters = new DeadLetterPublishingRecoverer(new KafkaTemplate<>(orderConfirmedDeadLetterProducerFactory),
            (record, exception) -> new TopicPartition(record.topic() + DEAD_LETTER_SUFFIX, -1));
        deadLetters.setWaitForSendResultTimeout(DEAD_LETTER_SEND_TIMEOUT);
        var stopping = new CommonContainerStoppingErrorHandler();

        ConsumerAwareRecordRecoverer recoverer = (record, consumer, exception) -> {
            if (!isMessageProblem(exception)) {
                stop(stopping, registry, record, consumer, exception, "processing failed after retries");
                return;
            }
            try {
                deadLetters.accept(record, consumer, exception);
            } catch (RuntimeException deadLetterFailure) {
                stop(stopping, registry, record, consumer, deadLetterFailure, "dead letter publication failed");
            }
        };

        // maxElapsedTime은 벽시계가 아니라 기다린 간격의 합이다. 처리·poll 시간은 따로 더해진다.
        var backOff = new ExponentialBackOff(retry.initialInterval().toMillis(), retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        backOff.setMaxElapsedTime(retry.maxElapsedTime().toMillis());
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
            InvalidOrderConfirmedMessageException.class, ConflictingOrderConfirmationException.class);
        // BATCH는 recoverer가 정상 반환한 record의 위치를 container가 기록한다.
        // DLT 실패·재시도 소진의 중단은 예외로 끝나므로 해당 record는 완료로 기록하지 않는다.
        handler.setAckAfterHandle(true);
        handler.setCommitRecovered(false);
        return handler;
    }

    private static boolean isMessageProblem(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidOrderConfirmedMessageException
                || cause instanceof ConflictingOrderConfirmationException) {
                return true;
            }
        }
        return false;
    }

    /** listener 전체를 멈춘다. 예외로 끝나므로 원본 offset은 그대로 남는다. */
    private static void stop(CommonContainerStoppingErrorHandler stopping, KafkaListenerEndpointRegistry registry,
                             ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception cause, String reason) {
        log.error("Order confirmation consumer stopped ({}): topic={}, partition={}, offset={}, group={}",
            reason, record.topic(), record.partition(), record.offset(), consumer.groupMetadata().groupId(), cause);
        stopping.handleRemaining(cause, List.of(record), consumer,
            registry.getListenerContainer(OrderConfirmedKafkaListener.ID));
    }
}
