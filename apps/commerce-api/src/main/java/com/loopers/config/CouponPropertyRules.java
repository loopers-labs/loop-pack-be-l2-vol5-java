package com.loopers.config;

import java.time.Duration;

/** 쿠폰 시작 설정들이 공유하는 기간 검사. 어긋나면 기동 오류로 쓰는 IllegalArgumentException 을 만든다. */
final class CouponPropertyRules {

    private CouponPropertyRules() {}

    /** null 이 아니고, 밀리초 변환이 넘치지 않으며, 1ms 이상이어야 한다. */
    static void requirePositiveMillis(String prefix, String key, Duration value) {
        if (value == null) {
            throw invalid(prefix, key, "1ms 이상의 기간", "null");
        }
        long millis;
        try {
            millis = value.toMillis();
        } catch (ArithmeticException e) {
            throw invalid(prefix, key, "밀리초로 표현 가능한 기간", value.toString());
        }
        if (millis < 1) {
            throw invalid(prefix, key, "1ms 이상의 기간", value.toString());
        }
    }

    static IllegalArgumentException invalid(String prefix, String key, String rule, String value) {
        return new IllegalArgumentException(prefix + key + " 는 " + rule + "여야 합니다. [" + key + " = " + value + "]");
    }
}
