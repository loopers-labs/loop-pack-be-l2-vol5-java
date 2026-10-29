package com.loopers.application.order;

import com.loopers.domain.common.InvalidValueException;

// 카드 정보는 PG create에만 쓰고 저장하지 않는다. toString·예외 메시지에 카드 번호를 남기지 않는다(설계 21절).
public record ConfirmCommand(String attemptKey, String cardType, String cardNo) {
    public ConfirmCommand {
        if (attemptKey == null || attemptKey.isBlank()) {
            throw new InvalidValueException("결제 시도 키가 필요합니다.");
        }
        if (cardType == null || cardType.isBlank() || cardNo == null || cardNo.isBlank()) {
            throw new InvalidValueException("카드 종류와 카드 번호가 필요합니다.");
        }
    }

    @Override
    public String toString() {
        return "ConfirmCommand[attemptKey=" + attemptKey + ", cardType=" + cardType + ", cardNo=****]";
    }
}
