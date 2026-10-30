package com.loopers.domain.purchaseactivity;

import lombok.Getter;

/**
 * 구매 활동 소비의 계약 오류. 메시지에는 고정 문구와 필드 이름만 쓴다. 원문 payload·key·parser 오류는 메시지와 원인에 넣지 않는다.
 */
@Getter
public class PurchaseActivityException extends RuntimeException {

    private final PurchaseActivityErrorCode code;

    public PurchaseActivityException(PurchaseActivityErrorCode code, String message) {
        super(message);
        this.code = code;
    }
}
