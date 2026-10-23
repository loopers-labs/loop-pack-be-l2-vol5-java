package com.loopers.application.order.dto;

import com.loopers.application.payment.dto.PaymentAttemptInfo;
import com.loopers.application.payment.pg.PgPaymentRequestCommand;

/**
 * 결제 준비(트랜잭션 A)의 결과. {@code request} 는 발송 권한을 얻은 실행에만 있고, 없으면 저장된 상태를 돌려주는 것이라
 * PG 를 호출하지 않는다.
 */
public record ConfirmPreparationResult(PaymentAttemptInfo info, PgPaymentRequestCommand request) {
    public boolean isNewAttempt() {
        return request != null;
    }
}
