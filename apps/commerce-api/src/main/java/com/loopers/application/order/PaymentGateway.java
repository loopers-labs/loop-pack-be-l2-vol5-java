package com.loopers.application.order;

/**
 * PG 카드 결제 포트. 구현은 infrastructure 가 가진다 (HTTP·PG DTO·PG 주문 ID 형식은 그쪽에서만).
 * 트랜잭션 밖(주문 AWAITING_PAYMENT 커밋 이후)에서 부른다. 예외를 던지지 않고 결과로 돌려준다.
 */
public interface PaymentGateway {

    /** POST /api/v1/payments. userId 는 X-USER-ID 헤더, callbackUrl 은 구현이 설정에서 채운다. */
    PaymentGatewayResult request(Long userId, Long orderId, String cardType, String cardNo, Long amount);

    /**
     * GET /api/v1/payments?orderId={PG 주문 ID}. 새 결제 요청을 만들지 않고 기존 거래를 조회한다.
     * 조회가 닿지 않았거나 실패하면 UNKNOWN (결제가 안 됐다는 뜻이 아니므로 NOT_SENT·FAILED 로 바꾸지 않는다).
     */
    PaymentGatewayResult find(Long userId, Long orderId);
}
