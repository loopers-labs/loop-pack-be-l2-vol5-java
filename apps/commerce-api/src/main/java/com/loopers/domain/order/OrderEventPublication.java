package com.loopers.domain.order;

/**
 * 발행 시도의 관찰 결과. 성공은 broker가 알려 준 실제 위치이고, 실패는 이유다.
 * "보냈다"와 "broker가 받았다"를 구분하려고 성공은 topic·partition·offset을 함께 담는다.
 */
public sealed interface OrderEventPublication {

    record Sent(String topic, int partition, long offset) implements OrderEventPublication {
    }

    record Failed(String reason) implements OrderEventPublication {
    }
}
