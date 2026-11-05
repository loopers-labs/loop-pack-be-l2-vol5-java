package com.loopers.interfaces.consumer;

import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.order.RepublishResult;
import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.confg.kafka.NonRetryableEventException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

// 대사(commerce-batch)가 보낸 재발행 명령을 처리한다(설계 27절). ack는 재발행 send의 broker 확인 뒤에만 한다.
// send 실패는 예외로 재시도 후 멈추고(RetryThenStopErrorHandler), 모르는 명령은 바로 멈추며, 깨진 명령은 기록 후 넘어간다.
// committed offset이 없을 때 latest면 그 전 명령이 묻히므로 earliest로 둔다. 재처리는 재발행이 멱등이라 흡수한다.
@Slf4j
@Component
public class RepublishRequestsListener {
    public static final String LISTENER_ID = "order-event-republish-requests";

    private final RepublishRequestParser republishRequestParser;
    private final OrderApplicationService orderApplicationService;
    private final Counter invalidRequests;

    public RepublishRequestsListener(RepublishRequestParser republishRequestParser,
        OrderApplicationService orderApplicationService, MeterRegistry meterRegistry) {
        this.republishRequestParser = republishRequestParser;
        this.orderApplicationService = orderApplicationService;
        this.invalidRequests = Counter.builder("commerce.order.events.republish.invalid").register(meterRegistry);
    }

    @KafkaListener(
        id = LISTENER_ID,
        topics = "${commerce.kafka.topics.order-event-republish-requests}",
        groupId = "${commerce.kafka.groups.order-event-republish}",
        containerFactory = KafkaConfig.RECORD_LISTENER,
        properties = "auto.offset.reset=earliest"
    )
    public void onRequest(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        switch (republishRequestParser.parse(record.key(), record.value())) {
            case RepublishRequestParseResult.Parsed parsed -> {
                RepublishResult result = orderApplicationService.republishConfirmed(parsed.orderId());
                if (result == RepublishResult.REPUBLISHED) {
                    log.info("재발행 명령 처리. orderId={}, offset={}", parsed.orderId(), record.offset());
                } else {
                    log.warn("재발행하지 않고 명령을 넘깁니다. orderId={}, result={}, offset={}",
                        parsed.orderId(), result, record.offset());
                }
            }
            case RepublishRequestParseResult.Invalid invalid -> {
                invalidRequests.increment();
                log.error("깨진 재발행 명령을 기록하고 넘깁니다. topic={}, partition={}, offset={}, detail={}",
                    record.topic(), record.partition(), record.offset(), invalid.detail());
            }
            case RepublishRequestParseResult.Unsupported unsupported -> throw new NonRetryableEventException(
                unsupported.detail() + " (topic=" + record.topic() + ", partition=" + record.partition()
                    + ", offset=" + record.offset() + ")");
        }
        acknowledgment.acknowledge();
    }
}
