package com.loopers.application.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/**
 * 확정 요청의 결제 입력. 금액은 받지 않는다 — 저장된 주문에서 정한다(docs/week5/pg-contract.md 6번).
 * 카드 값은 PG 요청에만 쓰고 DB·로그에 남기지 않는다(과제 규칙) — 그래서 toString에서 카드 번호를 가린다.
 */
public record ConfirmPaymentCommand(String attemptKey, String cardType, String cardNo) {

    public ConfirmPaymentCommand {
        if (attemptKey == null || attemptKey.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 시도 키는 비어있을 수 없습니다.");
        }
        if (cardType == null || cardType.isBlank() || cardNo == null || cardNo.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "카드 종류와 카드 번호는 비어있을 수 없습니다.");
        }
    }

    @Override
    public String toString() {
        return "ConfirmPaymentCommand[attemptKey=" + attemptKey + ", cardType=" + cardType + ", cardNo=****]";
    }
}
