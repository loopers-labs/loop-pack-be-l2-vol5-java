package com.loopers.domain.payment.enums;

/** 외부 결과를 얼마나 아는가. 충돌은 상태가 아니라 기록으로 남김 (D-65) */
public enum PaymentStatus {
    /** 발송 권한을 기록함. 생성 응답은 아직 반영하지 못함 */
    REQUESTED(false),
    /** 생성 결과를 확인하지 못함 (timeout · 연결 유실 · 해석 불가 응답) */
    UNKNOWN(false),
    /** 거래가 있고 PG 가 처리 중이라고 알려 줌 */
    PENDING(false),
    SUCCESS(true),
    FAILED(true),
    /** PG 가 거래를 만들지 않았다고 확정해 거절함 (효과 0건, D-67) */
    REJECTED(true),
    /** 0원 주문이라 PG 를 부르지 않고 확정함 (D-68) */
    NOT_REQUIRED(true);

    private final boolean terminal;

    PaymentStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
