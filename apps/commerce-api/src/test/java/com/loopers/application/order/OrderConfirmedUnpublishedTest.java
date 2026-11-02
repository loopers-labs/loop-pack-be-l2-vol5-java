package com.loopers.application.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미발행은 로그와 함께 사유별 Counter `order.confirmed.unpublished{reason}`로 센다(docs/week6/decisions.md D-26).
 */
class OrderConfirmedUnpublishedTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final OrderConfirmedUnpublished unpublished = new OrderConfirmedUnpublished(meterRegistry);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(OrderConfirmedUnpublished.class);

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private double count(String tagValue) {
        return meterRegistry.get(OrderConfirmedUnpublished.METRIC)
            .tag(OrderConfirmedUnpublished.REASON_TAG, tagValue)
            .counter().count();
    }

    @DisplayName("사유 태그는 일곱 가지이고 소문자 snake다. 처음에는 모두 0으로 보인다.")
    @Test
    void registersAllReasonsAtZero() {
        // assert
        assertThat(Arrays.stream(OrderConfirmedUnpublished.Reason.values())
            .map(OrderConfirmedUnpublished.Reason::tagValue)
            .collect(Collectors.toList()))
            .containsExactly("register_failed", "hand_off_failed", "queue_full", "shutting_down",
                "shutdown_timeout", "publish_failed", "send_failed");
        for (OrderConfirmedUnpublished.Reason reason : OrderConfirmedUnpublished.Reason.values()) {
            assertThat(count(reason.tagValue())).isZero();
        }
    }

    @DisplayName("기록하면 그 사유의 Counter만 하나 오른다.")
    @Test
    void incrementsOnlyThatReason() {
        // act
        for (OrderConfirmedUnpublished.Reason reason : OrderConfirmedUnpublished.Reason.values()) {
            for (int i = 0; i <= reason.ordinal(); i++) {
                unpublished.record(reason, 1L, "order-confirmed:v1:1");
            }
        }

        // assert — 사유마다 다른 횟수로 올려, 서로 섞이지 않았음을 본다
        for (OrderConfirmedUnpublished.Reason reason : OrderConfirmedUnpublished.Reason.values()) {
            assertThat(count(reason.tagValue())).as(reason.tagValue()).isEqualTo(reason.ordinal() + 1.0);
        }
    }

    @DisplayName("원인 예외가 없으면 WARN, 있으면 ERROR로 \"미발행\"과 orderId·eventId를 남긴다.")
    @Test
    void logsWithOrderIdAndEventId() {
        // act
        unpublished.record(OrderConfirmedUnpublished.Reason.QUEUE_FULL, 7L, "order-confirmed:v1:7");
        unpublished.record(OrderConfirmedUnpublished.Reason.SEND_FAILED, 8L, "order-confirmed:v1:8", new IllegalStateException("x"));

        // assert
        assertThat(logs.list).hasSize(2);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(logs.list.get(0).getFormattedMessage()).contains("미발행", "orderId=7", "eventId=order-confirmed:v1:7");
        assertThat(logs.list.get(1).getLevel()).isEqualTo(Level.ERROR);
        assertThat(logs.list.get(1).getFormattedMessage()).contains("미발행", "orderId=8", "eventId=order-confirmed:v1:8");
        assertThat(logs.list.get(1).getThrowableProxy()).isNotNull();
    }
}
