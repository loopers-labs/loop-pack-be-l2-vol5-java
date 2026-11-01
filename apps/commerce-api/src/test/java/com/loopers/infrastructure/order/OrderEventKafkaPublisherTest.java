package com.loopers.infrastructure.order;

import com.loopers.application.order.dto.OrderConfirmedInfo;
import com.loopers.contract.order.OrderConfirmed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderEventKafkaPublisherTest {

    @DisplayName("확정 사실을 orderId key 와 계약 스키마로 디코드되는 값으로 보낸다.")
    @Test
    void sendsContractMessage_withOrderIdKey() throws Exception {
        // arrange
        @SuppressWarnings("unchecked")
        KafkaTemplate<Object, Object> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(String.class), any(), any())).thenReturn(new CompletableFuture<>());
        OrderEventKafkaPublisher publisher = new OrderEventKafkaPublisher(kafkaTemplate, "order-events");
        ZonedDateTime confirmedAt = ZonedDateTime.of(2026, 9, 7, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));
        OrderConfirmedInfo event = new OrderConfirmedInfo(
            "order-confirmed:v1:42", 42L, confirmedAt,
            List.of(new OrderConfirmedInfo.Item(7L, 2), new OrderConfirmedInfo.Item(8L, 1))
        );

        // act
        publisher.publish(event);

        // assert
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("order-events"), eq("42"), value.capture());
        OrderConfirmed decoded = OrderConfirmed.getDecoder().decode((byte[]) value.getValue());
        assertThat(decoded.getEventId()).isEqualTo("order-confirmed:v1:42");
        assertThat(decoded.getOrderId()).isEqualTo(42L);
        assertThat(decoded.getConfirmedAt()).isEqualTo(confirmedAt.toInstant());
        assertThat(decoded.getItems())
            .extracting(item -> item.getProductId() + "x" + item.getQuantity())
            .containsExactly("7x2", "8x1");
    }
}
