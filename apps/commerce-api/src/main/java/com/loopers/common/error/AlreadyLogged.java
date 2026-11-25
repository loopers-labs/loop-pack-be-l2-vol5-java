package com.loopers.common.error;

public final class AlreadyLogged extends RuntimeException {
    public AlreadyLogged() {
        super(null, null, false, false);
    }
}
