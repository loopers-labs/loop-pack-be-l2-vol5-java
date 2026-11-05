package com.loopers.domain.order;

/**
 * 저장된 Outbox 원문이 OrderConfirmed v1 계약과 메타데이터를 지키는지 검사하는 포트(설계 W7.3-6). DB·Product·PG 를 조회하지 않는다.
 * 알려진 형식·버전·사실 오류만 {@link OrderOutboxMessageException} 으로 던진다.
 */
public interface OrderOutboxMessageVerifier {

    /** 저장 원문을 엄격하게 읽어 상품 ID 오름차순·상품별 한 원소로 정규화한 확정 사건을 반환한다. */
    OrderConfirmed parseAndValidate(OrderOutboxStoredMessage stored);

    /** stored 를 같은 경계로 검증한 뒤 expected 와 identity·최초 순간·정규화 품목이 같은지 확인한다. 다르면 사실 충돌이다. */
    void requireSameFact(OrderConfirmed expected, OrderOutboxStoredMessage stored);
}
