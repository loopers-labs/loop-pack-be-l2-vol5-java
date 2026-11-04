package com.loopers.domain.order;

/**
 * 같은 TX 안에서 만든 확정 사건 snapshot 을 기존 OrderConfirmed v1 JSON 본문으로 바꾸는 포트(설계 W7.2-5·W7.2-6).
 * DB·PG·상품 조회, Kafka send, 새 TX 시작·종료를 하지 않는다. 변환·직렬화 실패는 원문 payload·예외 메시지를 담지 않은
 * INTERNAL_ERROR 로 호출자에게 전파한다.
 */
public interface OrderConfirmedMessageEncoder {

    OrderOutboxMessage encode(OrderConfirmed event);
}
