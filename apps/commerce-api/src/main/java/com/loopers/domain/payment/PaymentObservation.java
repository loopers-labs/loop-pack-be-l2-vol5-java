package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/**
 * PG 권위 조회로 확인한 거래 한 건. 결제 시도의 결과는 이 타입으로만 정해진다 —
 * timeout·조회 실패·회로 OPEN은 관찰이 아니므로 결과를 정하지 못한다(INV-004).
 * callback 본문은 이 타입으로 만들지 않는다(본문은 조회의 계기일 뿐이다).
 */
public record PaymentObservation(String providerOrderId, String transactionKey, Status status, long amount) {

    public PaymentObservation {
        if (providerOrderId == null || providerOrderId.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "관찰한 거래의 providerOrderId는 비어있을 수 없습니다.");
        }
        if (transactionKey == null || transactionKey.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "관찰한 거래의 transactionKey는 비어있을 수 없습니다.");
        }
        if (status == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "관찰한 거래의 상태는 비어있을 수 없습니다.");
        }
    }

    /**
     * PG가 알려 주는 외부 거래 상태. 생성 수락(PENDING)과 최종 성공(SUCCESS)은 다르다.
     */
    public enum Status {
        PENDING,
        SUCCESS,
        FAILED
    }
}
