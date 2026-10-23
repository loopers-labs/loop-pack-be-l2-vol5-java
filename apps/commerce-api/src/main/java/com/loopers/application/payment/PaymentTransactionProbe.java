package com.loopers.application.payment;

/**
 * 4.5 TX 경계·부모 잠금 검증용 seam. 결제·충돌 요약 INSERT 직전과, 모든 쓰기를 영속성 컨텍스트에 넣은 commit 직전에 부른다.
 * 테스트가 장벽·실패 주입으로 쓰며 운영 구현은 아무것도 하지 않는다. "실제 SQL 뒤"가 필요한 테스트 구현은 스스로 flush 한다.
 */
public interface PaymentTransactionProbe {

    enum Point {
        BEFORE_PAYMENT_INSERT,
        BEFORE_CONFLICT_INSERT,
        BEFORE_COMMIT
    }

    void reach(Point point, String operation, Long orderId);
}
