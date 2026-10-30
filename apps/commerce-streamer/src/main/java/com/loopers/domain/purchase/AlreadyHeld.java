package com.loopers.domain.purchase;

public class AlreadyHeld extends RuntimeException {

    public AlreadyHeld(Throwable cause) {
        super("같은 위치의 record 가 이미 보류돼 있다", cause);
    }
}
