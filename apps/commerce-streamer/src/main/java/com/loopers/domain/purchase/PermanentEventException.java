package com.loopers.domain.purchase;

import lombok.Getter;

/**
 * 다시 처리해도 결과가 같은 영구 오류 (설계 D12): 깨진 JSON, 계약 위반, key ≠ orderId, 수량 ≤ 0, 같은 ID 의 다른 사실(충돌).
 * 리스너는 이 예외를 재시도하지 않고 원본 record 를 DLT 로 보낸 뒤 ack 한다. 구매 활동은 만들지 않는다.
 * 충돌이면 장부의 기존 fingerprint 와 새 이벤트의 fingerprint 를 담아 DLT 헤더로 드러낸다.
 */
@Getter
public class PermanentEventException extends RuntimeException {

    private final String existingFingerprint;
    private final String newFingerprint;

    public PermanentEventException(String message) {
        this(message, null, null);
    }

    public PermanentEventException(String message, Throwable cause) {
        super(message, cause);
        this.existingFingerprint = null;
        this.newFingerprint = null;
    }

    public PermanentEventException(String message, String existingFingerprint, String newFingerprint) {
        super(message);
        this.existingFingerprint = existingFingerprint;
        this.newFingerprint = newFingerprint;
    }

    public boolean isConflict() {
        return existingFingerprint != null;
    }
}
