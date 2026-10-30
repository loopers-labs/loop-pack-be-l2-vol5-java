package com.loopers.domain.activity;

public class AlreadyHeld extends RuntimeException {

    public AlreadyHeld(Throwable cause) {
        super("같은 위치의 record 가 이미 보류돼 있다", cause);
    }
}
