package com.loopers.common.error;

import java.util.Arrays;

// 오류를 이미 업무 열쇠와 함께 기록했다는 표식. 원래 예외의 suppressed로 붙여 타입·메시지·응답을 바꾸지 않고 advice의 중복 기록만 막는다.
public final class AlreadyLogged extends RuntimeException {
    public AlreadyLogged() {
        super(null, null, false, false);
    }

    public static boolean isMarked(Throwable failure) {
        return Arrays.stream(failure.getSuppressed()).anyMatch(AlreadyLogged.class::isInstance);
    }
}
