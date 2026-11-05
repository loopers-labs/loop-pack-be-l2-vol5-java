package com.loopers.infrastructure.order.support;

import com.loopers.domain.order.OrderConfirmed;

/**
 * W6 보존(설계 W7.4-1). 출처: 53f912b apps/commerce-api/src/main/java/com/loopers/application/order/OrderPublicationProbe.java 와
 * NoOpOrderPublicationProbe.java. 허용 변경: package·타입 이름, no-op 구현을 중첩 클래스로 합치고 {@code @Component} 제거(전역 scan 방지).
 * bean 이름 noOpOrderPublicationProbe 는 W6 조립이 그대로 쓴다.
 *
 * <p>commit 뒤·send 전의 누락 검증용 seam. 테스트만 대상 주문에서 대기·실패를 주입한다.
 * 공개 제어 기능이나 재발행 경로가 아니며 운영 구현은 아무것도 하지 않는다.
 */
public interface W6OrderPublicationProbe {

    void beforeSend(OrderConfirmed event);

    final class NoOp implements W6OrderPublicationProbe {

        @Override
        public void beforeSend(OrderConfirmed event) {
        }
    }
}
