package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.OrderConfirmedMessageSender;
import com.loopers.domains.order.app.port.SendOutcome;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.errors.RetriableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// relay의 전송 어댑터. broker 확인(future 완료)까지 send-timeout만큼 기다리고 callback으로 상태를 바꾸지 않는다(D115).
// 분류 기준은 영향 범위다(relay-1 ①): 원인 사슬에 Kafka RetriableException(timeout·연결·metadata·없는 topic·복제본 부족)이나
// 로컬 대기 timeout이 있으면 모든 행에 해당하는 broker 수준, 그 밖(메시지 크기·직렬화·잘못된 topic 이름 등)은 그 행 수준이다.
@Component
public class KafkaOrderConfirmedMessageSender implements OrderConfirmedMessageSender {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final Duration sendTimeout;

    public KafkaOrderConfirmedMessageSender(KafkaTemplate<String, String> kafkaTemplate,
        @Value("${commerce.kafka.topics.order-events}") String topic,
        @Value("${commerce.outbox.relay.send-timeout}") Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public SendOutcome send(String key, String payload) {
        try {
            kafkaTemplate.send(topic, key, payload).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return new SendOutcome.Published();
        } catch (InterruptedException e) {
            // 인터럽트는 분류하지 않는다. 상태를 되살리고 행 탓으로 세지 않으며, relay는 플래그를 보고 DB를 부르기 전에 회차를 끝낸다.
            Thread.currentThread().interrupt();
            return new SendOutcome.BrokerFailure(describe(e));
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            return classify(e);
        }
    }

    static SendOutcome classify(Throwable failure) {
        String error = describe(failure);
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof RetriableException || cause instanceof TimeoutException) {
                return new SendOutcome.BrokerFailure(error);
            }
        }
        return new SendOutcome.RowFailure(error);
    }

    // last_error에는 원인(사슬의 맨 끝) 예외 이름과 메시지를 남긴다.
    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
