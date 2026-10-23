package com.loopers.domain.payment.enums;

/** 결제 상태와 같은 전이에서만 바뀜 (D-59) */
public enum ReservationStatus {
    RESERVED,
    CONSUMED,
    RELEASED
}
