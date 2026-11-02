package com.loopers.application.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * 주문 확정 이벤트의 미발행을 한 곳에서 기록한다(docs/week6/decisions.md D-26). 모든 미발행 경로가 여기를 지난다 —
 * 로그만 남기면 아무도 보지 않을 때 조용한 손실이 되므로, Counter `order.confirmed.unpublished`(태그 `reason`)로도 센다.
 * "0이 아니다"에 알림을 걸 수 있다. 다시 채우는 일은 7주차 Outbox다.
 *
 * <p>MeterRegistry에만 의존한다. 발행 경로의 어느 빈(applier·publication·Kafka 구현)이 불러도 빈 순환이 생기지 않게
 * OrderConfirmedPublication과 떼어 둔다.
 */
@Slf4j
@Component
public class OrderConfirmedUnpublished {

    public static final String METRIC = "order.confirmed.unpublished";
    public static final String REASON_TAG = "reason";

    /** 미발행 경로(D-26). 태그 값은 소문자 snake다(예: queue_full). */
    public enum Reason {
        /** 확정 트랜잭션 안에서 payload 생성·afterCommit 등록 실패 — 확정은 commit한다. */
        REGISTER_FAILED("등록 실패"),
        /** afterCommit에서 발행 스레드에 넘기지 못함. */
        HAND_OFF_FAILED("넘기기 실패"),
        /** 발행 큐가 가득 참(D-23). */
        QUEUE_FULL("발행 큐 가득 참"),
        /** 종료 중이라 받지 못함. */
        SHUTTING_DOWN("종료 중"),
        /** 정상 종료 대기 시간 안에 끝내지 못하고 남은 작업. */
        SHUTDOWN_TIMEOUT("종료 대기 초과"),
        /** 발행 스레드에서 publish가 예외를 던짐. */
        PUBLISH_FAILED("발행 예외"),
        /** Kafka 전송 결과가 실패로 돌아옴. */
        SEND_FAILED("전송 실패");

        private final String description;

        Reason(String description) {
            this.description = description;
        }

        public String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Map<Reason, Counter> counters = new EnumMap<>(Reason.class);

    public OrderConfirmedUnpublished(MeterRegistry meterRegistry) {
        for (Reason reason : Reason.values()) {
            counters.put(reason, Counter.builder(METRIC)
                .description("발행되지 못한 주문 확정 이벤트 수 (week6 D-26)")
                .tag(REASON_TAG, reason.tagValue())
                .register(meterRegistry));
        }
    }

    public void record(Reason reason, Long orderId, String eventId) {
        record(reason, orderId, eventId, null);
    }

    public void record(Reason reason, Long orderId, String eventId, Throwable cause) {
        record(reason, orderId, eventId, "", cause);
    }

    /** 전송 단계의 미발행 — 어느 topic으로 보내다 실패했는지도 남긴다. */
    public void recordSendFailure(Long orderId, String eventId, String topic, Throwable cause) {
        record(Reason.SEND_FAILED, orderId, eventId, ", topic=" + topic, cause);
    }

    /** 경고 로그(예외가 있으면 ERROR)를 남기고 사유별 Counter를 하나 올린다. 어떤 경우에도 예외를 던지지 않는다. */
    private void record(Reason reason, Long orderId, String eventId, String detail, Throwable cause) {
        try {
            if (cause == null) {
                log.warn("주문 확정 이벤트 미발행({}): orderId={}, eventId={}{}", reason.description, orderId, eventId, detail);
            } else {
                log.error("주문 확정 이벤트 미발행({}): orderId={}, eventId={}{}", reason.description, orderId, eventId, detail, cause);
            }
            counters.get(reason).increment();
        } catch (RuntimeException e) {
            // 기록 실패가 호출자(afterCommit·발행 스레드·확정 트랜잭션)로 번지지 않게 한다.
            log.error("미발행 기록 실패: reason={}, orderId={}", reason, orderId, e);
        }
    }
}
