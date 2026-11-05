package com.loopers.infrastructure.order.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.loopers.config.OrderEventKafkaProperties;
import com.loopers.domain.order.OrderConfirmed;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * W6 보존(설계 W7.4-1). 출처: 53f912b apps/commerce-api/src/main/java/com/loopers/infrastructure/order/KafkaOrderEventPublisher.java.
 * 허용 변경: package·타입 이름, 테스트 소스에 Lombok 처리가 없어 @Slf4j 를 같은 SLF4J Logger 필드로 풀어 씀, 포트·qualifier 상수를 W6 보존 타입으로 연결, {@code @Component} 제거(W6 조립이 bean
 * kafkaOrderEventPublisher 로 명시 등록), 패키지 이동으로 다른 패키지 테스트가 쓰는 v1 DTO·상수를 public 으로 노출.
 * wire 필드·표현·비동기 send·오류 로그는 원본 그대로다.
 *
 * <p>주문 확정 사건을 OrderConfirmed v1 wire 계약으로 바꿔 전용 template 으로 보낸다. key 는 주문 ID 이고 partition 은 producer 가 고른다.
 *
 * <p>Future 의 완료는 기다리지 않고 결과만 로그로 관측한다. 성공 로그는 설정한 acks 조건의 broker 확인이며 소비 완료가 아니고,
 * 실패·timeout 은 broker 미기록의 증거가 아니다. 자체 재시도·flush 는 하지 않는다. 오류는 여기서 기록하고 정상 반환해
 * listener 가 같은 오류를 다시 기록하지 않게 한다. 로그에는 원문 예외 메시지·stacktrace·payload 를 남기지 않는다.
 */
public class W6KafkaOrderEventPublisher implements W6OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(W6KafkaOrderEventPublisher.class);

    public static final String EVENT_TYPE = "OrderConfirmed";
    public static final int SCHEMA_VERSION = 1;

    private final KafkaTemplate<String, Object> template;
    private final String topic;

    public W6KafkaOrderEventPublisher(
        @Qualifier(W6OrderEventKafkaConfig.ORDER_EVENT_KAFKA_TEMPLATE) KafkaTemplate<String, Object> template,
        OrderEventKafkaProperties properties) {
        this.template = template;
        this.topic = properties.topic();
    }

    @Override
    public void publish(OrderConfirmed event) {
        String eventId = event.eventId();
        long orderId = event.orderId();
        CompletableFuture<SendResult<String, Object>> future;
        try {
            future = template.send(topic, Long.toString(orderId), OrderConfirmedV1.from(event));
        } catch (RuntimeException failure) {
            logFailure(eventId, orderId, "SEND_CALL", failure);
            return;
        }
        future.whenComplete((result, failure) -> {
            if (failure != null) {
                logFailure(eventId, orderId, "SEND_COMPLETION", failure);
                return;
            }
            RecordMetadata metadata = result.getRecordMetadata();
            log.info("ORDER_CONFIRMED_SEND_SUCCESS eventId={} orderId={} topic={} partition={} offset={}",
                eventId, orderId, metadata.topic(), metadata.partition(), metadata.offset());
        });
    }

    private void logFailure(String eventId, long orderId, String stage, Throwable failure) {
        log.warn("ORDER_CONFIRMED_SEND_FAILURE eventId={} orderId={} topic={} stage={} exceptionType={} causeType={}",
            eventId, orderId, topic, stage, failure.getClass().getName(),
            failure.getCause() == null ? "none" : failure.getCause().getClass().getName());
    }

    /** OrderConfirmed v1 wire 계약. 내부 사건과 분리해 외부 JSON 버전이 domain 에 결합되지 않게 한다. */
    public record OrderConfirmedV1(String eventType, int schemaVersion, String eventId, long orderId, String confirmedAt,
                            List<Item> items) {

        public static OrderConfirmedV1 from(OrderConfirmed event) {
            return new OrderConfirmedV1(EVENT_TYPE, SCHEMA_VERSION, event.eventId(), event.orderId(),
                DateTimeFormatter.ISO_INSTANT.format(event.confirmedAt().toInstant()),
                event.items().stream().map(item -> new Item(item.productId(), item.quantity())).toList());
        }

        public record Item(long productId, long quantity) {
        }
    }
}
