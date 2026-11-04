package com.loopers.infrastructure.order.support;

import com.loopers.domain.order.OrderConfirmed;

/**
 * W6 보존(설계 W7.4-1). 출처: 53f912b apps/commerce-api/src/main/java/com/loopers/domain/order/OrderEventPublisher.java.
 * 허용 변경: package·타입 이름. 동작·계약 문구는 원본 그대로다. W7 제품 경로에서는 쓰지 않는다.
 *
 * <p>최초 확정 사실을 외부에 전달하는 포트. 반환은 broker 기록이나 소비 완료를 뜻하지 않는다.
 * 전달 결과는 구현이 관측하며 Kafka 타입·Future 를 호출자에게 노출하지 않는다.
 */
public interface W6OrderEventPublisher {

    void publish(OrderConfirmed event);
}
