package com.loopers.application.order;

import com.loopers.domain.order.OrderConfirmed;

/**
 * commit 뒤·send 전의 누락 검증용 seam. 테스트만 대상 주문에서 대기·실패를 주입한다.
 * 공개 제어 기능이나 재발행 경로가 아니며 운영 구현은 아무것도 하지 않는다.
 */
public interface OrderPublicationProbe {

    void beforeSend(OrderConfirmed event);
}
