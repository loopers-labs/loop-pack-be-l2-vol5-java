package com.loopers.application.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

// 합성 카드 입력이다. PG 생성 요청에만 전달하고 저장·로그하지 않는다.
// PG와 같은 조건(공백만이 아닌 필수 문자열)만 재고 확보 전에 검사하고, 형식·길이는 검사하지 않는다.
public record PaymentCard(String cardType, String cardNo) {

    public PaymentCard {
        if (cardType == null || cardType.isBlank() || cardNo == null || cardNo.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "카드 종류와 카드 번호는 필수입니다.");
        }
    }

    @Override
    public String toString() {
        return "PaymentCard[redacted]";
    }
}
