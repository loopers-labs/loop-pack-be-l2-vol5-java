package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.Set;

/**
 * 합성 카드 입력. 실제 카드가 아니라 로컬 테스트 PG 가 받는 약속된 값만 허용한다.
 * 형식 검사가 아니라 허용 목록 일치다. cardType 은 TEST, 카드 번호는 ALLOWED_CARD_NUMBERS 에 있어야 한다.
 * 카드 번호는 저장하지 않고 toString 에서도 가린다.
 */
public record PaymentCard(String cardType, String cardNo) {

    public static final String TEST_CARD_TYPE = "TEST";
    private static final Set<String> ALLOWED_CARD_NUMBERS = Set.of("0000-0000-0000-0000");

    public PaymentCard {
        if (!TEST_CARD_TYPE.equals(cardType)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "지원하지 않는 카드 종류입니다.");
        }
        if (cardNo == null || !ALLOWED_CARD_NUMBERS.contains(cardNo)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "허용되지 않은 카드 번호입니다.");
        }
    }

    @Override
    public String toString() {
        return "PaymentCard[cardType=" + cardType + ", cardNo=****]";
    }
}