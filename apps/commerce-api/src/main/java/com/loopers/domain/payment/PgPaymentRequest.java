package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/**
 * PG에 보낼 거래 생성 요청이다. 카드번호는 이 요청을 만드는 순간에만 쓰고 저장하지 않는다.
 * record의 기본 toString은 모든 필드를 찍으므로, 로그나 예외 메시지에 카드번호가 남지 않게 재정의한다.
 */
public record PgPaymentRequest(String providerOrderId, String cardType, String cardNo, long amount) {

    public PgPaymentRequest {
        if (isBlank(providerOrderId)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "PG 주문 번호는 비어있을 수 없습니다.");
        }
        if (isBlank(cardType)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "카드 종류는 비어있을 수 없습니다.");
        }
        if (isBlank(cardNo)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "카드 번호는 비어있을 수 없습니다.");
        }
        if (amount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 금액은 0보다 커야 합니다.");
        }
    }

    @Override
    public String toString() {
        return "PgPaymentRequest[providerOrderId=" + providerOrderId + ", cardType=" + cardType
            + ", cardNo=****, amount=" + amount + "]";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
