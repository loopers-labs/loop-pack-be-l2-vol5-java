package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderEventPublication;
import com.loopers.domain.order.OrderOutboxMessage;
import com.loopers.domain.order.OrderOutboxSender;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox 행에 저장된 key·payload를 그대로 {@code order-events}에 보낸다 (W7 design.md 5절 결정 A).
 *
 * <p>값은 이미 JSON 문자열이라 {@code StringSerializer}로 보낸다. 공통 {@code kafkaTemplate}의 {@code JsonSerializer}에 문자열을 넘기면
 * JSON 문자열 리터럴로 한 번 더 감싸 consumer가 읽지 못한다. 그래서 공통 producer 설정(acks·멱등·timeout)은 그대로 쓰고 직렬화만 바꾼 전용 producer를 둔다.
 * 이 producer는 빈으로 등록하지 않아 공통 {@code kafkaTemplate}과 섞이지 않는다.
 *
 * <p>성공은 send 호출이 예외 없이 끝난 것이 아니라 producer future가 broker의 위치(topic·partition·offset)와 함께 완료된 것이다.
 * 기다리는 시간을 넘기면 실패로 돌려주지만 그 record는 뒤늦게 broker에 쓰였을 수 있다. future에 callback을 달아 상태를 바꾸지 않는다.
 */
@Component
public class KafkaOrderOutboxSender implements OrderOutboxSender, DisposableBean {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final Duration sendTimeout;
    private final DefaultKafkaProducerFactory<String, String> ownedProducerFactory;

    @Autowired
    public KafkaOrderOutboxSender(
        KafkaProperties kafkaProperties,
        @Value("${order-event.topic-name}") String topic,
        @Value("${order-outbox.relay.send-timeout}") Duration sendTimeout
    ) {
        this(new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties(), new StringSerializer(), new StringSerializer()), topic, sendTimeout);
    }

    private KafkaOrderOutboxSender(DefaultKafkaProducerFactory<String, String> producerFactory, String topic, Duration sendTimeout) {
        this.kafkaTemplate = new KafkaTemplate<>(producerFactory);
        this.topic = topic;
        this.sendTimeout = sendTimeout;
        this.ownedProducerFactory = producerFactory;
    }

    /** 이미 만든 template으로 보낸다. 테스트가 격리 broker·topic을 지정할 때 쓴다. template의 수명은 호출자가 맡는다. */
    public KafkaOrderOutboxSender(KafkaTemplate<String, String> kafkaTemplate, String topic, Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.sendTimeout = sendTimeout;
        this.ownedProducerFactory = null;
    }

    @Override
    public OrderEventPublication send(OrderOutboxMessage message) {
        try {
            SendResult<String, String> result = kafkaTemplate.send(topic, message.messageKey(), message.payload())
                .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            RecordMetadata metadata = result.getRecordMetadata();
            return new OrderEventPublication.Sent(metadata.topic(), metadata.partition(), metadata.offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new OrderEventPublication.Failed("broker 확인을 기다리다 중단됐습니다");
        } catch (TimeoutException e) {
            return new OrderEventPublication.Failed("broker 확인 대기 시간(" + sendTimeout + ")을 넘겼습니다. 수신 여부를 모릅니다");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new OrderEventPublication.Failed(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (RuntimeException e) {
            return new OrderEventPublication.Failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public void destroy() {
        if (ownedProducerFactory != null) {
            ownedProducerFactory.destroy();
        }
    }
}
