package com.loopers.domain.purchase;

public class FactAlreadyRecorded extends RuntimeException {

    public FactAlreadyRecorded(Throwable cause) {
        super("처리 기록의 유일 제약과 부딪혔다", cause);
    }
}
