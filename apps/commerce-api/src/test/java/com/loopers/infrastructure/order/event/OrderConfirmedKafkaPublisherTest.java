package com.loopers.infrastructure.order.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.application.order.event.OrderConfirmedEvent;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.order.PaymentResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderConfirmedKafkaPublisherTest {

    private static final String TOPIC = "order-events-publisher-test";

    @Mock
    private KafkaTemplate<Object, Object> kafkaTemplate;

    private final Logger logger = (Logger) LoggerFactory.getLogger(OrderConfirmedKafkaPublisher.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private OrderConfirmedKafkaPublisher publisher;

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        publisher = new OrderConfirmedKafkaPublisher(kafkaTemplate, TOPIC);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @DisplayName("send 호출 자체가 실패하면 확정 이벤트의 식별자와 원인을 error로 남기고 전파하지 않는다.")
    @Test
    void exposesSynchronousFailureWithoutPropagating() {
        IllegalStateException failure = new IllegalStateException("통제된 동기 전송 오류");
        when(kafkaTemplate.send(eq(TOPIC), eq("42"), any())).thenThrow(failure);

        assertThatCode(() -> publisher.onConfirmed(event())).doesNotThrowAnyException();

        assertFailure(failure);
    }

    @DisplayName("비동기 전송이 실패하면 완료 콜백에서 확정 이벤트의 식별자와 원인을 error로 남긴다.")
    @Test
    void exposesAsynchronousFailureFromCompletionCallback() {
        IllegalStateException failure = new IllegalStateException("통제된 비동기 전송 오류");
        when(kafkaTemplate.send(eq(TOPIC), eq("42"), any()))
            .thenReturn(CompletableFuture.failedFuture(failure));

        publisher.onConfirmed(event());

        assertFailure(failure);
    }

    private void assertFailure(Exception failure) {
        assertThat(logs.list).singleElement().satisfies(entry -> {
            assertThat(entry.getLevel()).isEqualTo(Level.ERROR);
            assertThat(entry.getFormattedMessage()).contains("eventId=order-confirmed:v1:42", "orderId=42");
            assertThat(entry.getThrowableProxy().getMessage()).isEqualTo(failure.getMessage());
        });
    }

    private OrderConfirmedEvent event() {
        return OrderConfirmedEvent.from(Order.reconstitute(42L, 1L, OrderStatus.CONFIRMED, 7_000L, 7_000L,
            PaymentResult.SUCCESS, PaymentMethod.PG, ZonedDateTime.parse("2026-09-07T01:00:00Z"),
            List.of(OrderItem.create(7L, "상품", 3_500L, 2))));
    }
}
