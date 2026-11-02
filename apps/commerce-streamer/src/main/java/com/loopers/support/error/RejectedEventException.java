package com.loopers.support.error;

import lombok.Getter;

/** 영구 오류. 재시도하지 않고 DLT 로 보내는 기준이 됨 (6주차 D-86) */
@Getter
public class RejectedEventException extends RuntimeException {
    private final RejectReason reason;

    public RejectedEventException(RejectReason reason, String detail) {
        super(reason.getMessage() + " " + detail);
        this.reason = reason;
    }
}
