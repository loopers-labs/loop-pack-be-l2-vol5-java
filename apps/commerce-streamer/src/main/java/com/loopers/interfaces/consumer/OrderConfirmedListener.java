package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.DeadLetterPublisher;
import com.loopers.application.purchase.PurchaseActivityService;
import com.loopers.config.kafka.OrderActivityKafkaConfig;
import com.loopers.domain.purchase.OrderConfirmedEvent;
import com.loopers.domain.purchase.PermanentEventException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * order-events 의 record 하나를 파싱 → 구매 활동 반영 → ack 한다 (설계 D12·D13).
 * - 영구 오류(PermanentEventException): 재시도 없이 원본 record 를 DLT 로 보낸 뒤 ack. 구매 활동 없음
 * - 그 밖의 예외(DLT 발행 실패 포함): backoff 재시도, 상한 초과 시 예외를 던져 CommonContainerStoppingErrorHandler 가 컨테이너를 중단.
 *   ack 하지 않으므로 offset 은 실패 record 앞에 머문다
 */
@Slf4j
@Component
public class OrderConfirmedListener {
    private final OrderConfirmedEventParser parser;
    private final PurchaseActivityService purchaseActivityService;
    private final DeadLetterPublisher deadLetterPublisher;
    private final int maxRetries;
    private final long initialBackoffMs;
    private final int multiplier;

    public OrderConfirmedListener(
        OrderConfirmedEventParser parser,
        PurchaseActivityService purchaseActivityService,
        DeadLetterPublisher deadLetterPublisher,
        @Value("${purchase-activity.retry.max-retries}") int maxRetries,
        @Value("${purchase-activity.retry.initial-backoff-ms}") long initialBackoffMs,
        @Value("${purchase-activity.retry.multiplier}") int multiplier
    ) {
        this.parser = parser;
        this.purchaseActivityService = purchaseActivityService;
        this.deadLetterPublisher = deadLetterPublisher;
        this.maxRetries = maxRetries;
        this.initialBackoffMs = initialBackoffMs;
        this.multiplier = multiplier;
    }

    @KafkaListener(
        topics = "${purchase-activity.topic}",
        groupId = "${purchase-activity.group-id}",
        containerFactory = OrderActivityKafkaConfig.ORDER_ACTIVITY_LISTENER
    )
    public void listen(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        String position = record.topic() + "-" + record.partition() + "@" + record.offset() + ", key=" + record.key();
        try {
            OrderConfirmedEvent event = parser.parse(record.key(), record.value());
            withRetry("구매 활동 반영", position + ", eventId=" + event.eventId(), () -> purchaseActivityService.consume(event));
        } catch (PermanentEventException e) {
            // 영구 오류는 재시도해도 같으므로 DLT 로 보낸다. DLT 발행 실패는 일시 오류처럼 재시도하고, 소진되면 ack 없이 중단한다
            withRetry("DLT 발행", position, () -> deadLetterPublisher.publish(record, e));
        }
        ack.acknowledge();
    }

    /**
     * 리스너에는 TX 가 없으므로 service 호출마다 새 TX 가 열리고, 실패한 시도는 그 TX 와 함께 rollback 된다.
     * PermanentEventException 은 재시도 대상이 아니라 그대로 던진다.
     */
    private void withRetry(String what, String target, Runnable action) {
        long backoffMs = initialBackoffMs;
        for (int attempt = 1; ; attempt++) {
            try {
                action.run();
                return;
            } catch (PermanentEventException e) {
                throw e;
            } catch (RuntimeException e) {
                if (attempt > maxRetries) {
                    log.error("{} 일시 오류 재시도 소진 ({}회 시도). 컨테이너를 중단합니다. {}", what, attempt, target, e);
                    throw e;
                }
                log.warn("{} 일시 오류, {}ms 후 재시도 ({}/{}). {}: {}", what, backoffMs, attempt, maxRetries, target, e.toString());
                sleep(backoffMs);
                backoffMs *= multiplier;
            }
        }
    }

    private static void sleep(long backoffMs) {
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재시도 대기 중 인터럽트", e);
        }
    }
}
