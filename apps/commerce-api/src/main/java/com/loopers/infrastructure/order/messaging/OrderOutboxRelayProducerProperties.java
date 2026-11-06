package com.loopers.infrastructure.order.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** relay 전용 producer 의 시간 값 (7주차 D-107). 측정 전 잠정값 */
@ConfigurationProperties("order-outbox.relay.producer")
public record OrderOutboxRelayProducerProperties(
    Duration requestTimeout,
    Duration deliveryTimeout,
    /** delivery timeout 보다 길게 둬 대부분 future 가 먼저 끝나게 함. 그래도 넘기면 수신 여부를 모름 */
    Duration sendTimeout,
    Duration maxBlock
) {}
