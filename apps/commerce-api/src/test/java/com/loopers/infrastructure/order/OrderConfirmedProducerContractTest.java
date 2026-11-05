package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.contract.OrderEventContract;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderOutbox;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * producer 쪽 계약: Outbox에 저장하는 payload가 공유 예시(order-confirmed-v1.json)와 같은 JSON인지 확인한다.
 * relay는 이 payload를 검사하지 않고 그대로 보내므로, 메시지 모양을 확인하는 곳은 이 테스트와 consumer 계약 테스트다.
 * ObjectMapper는 앱과 같은 Jackson 자동 설정·공통 JacksonConfig로 만든다.
 */
class OrderConfirmedProducerContractTest {

    @Test
    void storedPayloadMatchesSharedContract() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withInitializer(context -> ((AnnotationConfigApplicationContext) context).scan("com.loopers.config.jackson"))
            .run(context -> {
                ObjectMapper mapper = context.getBean(ObjectMapper.class);
                OrderOutboxJpaRepository outbox = mock(OrderOutboxJpaRepository.class);
                var event = new OrderConfirmed(42L, Instant.parse("2026-10-29T01:00:00.123456Z"), List.of(
                    new OrderConfirmed.Item(8L, 3), new OrderConfirmed.Item(7L, 5)));

                new JsonOrderOutboxRecorder(outbox, mapper).record(event);

                var saved = ArgumentCaptor.forClass(OrderOutbox.class);
                verify(outbox).saveAndFlush(saved.capture());
                assertThat(mapper.readTree(saved.getValue().getPayload()))
                    .isEqualTo(mapper.readTree(OrderEventContract.ORDER_CONFIRMED_V1));
            });
    }
}
