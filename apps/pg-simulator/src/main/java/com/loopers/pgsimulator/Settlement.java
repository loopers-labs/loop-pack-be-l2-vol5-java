package com.loopers.pgsimulator;

import java.time.Duration;

/**
 * 자동 처리 설정. 켜져 있으면 생성 뒤 delay 가 지나 상태를 정하고 콜백을 1회 보낸다. 테스트는 끄고 직접 조작한다.
 * delay 는 사람이 결제 대기 상태를 조회로 볼 수 있게 하려는 관찰용 값이며 정합성과는 무관하다(설계 2.10).
 */
public record Settlement(Duration delay) {

    public static final Settlement DISABLED = new Settlement(null);

    public Settlement {
        if (delay != null && delay.isNegative()) {
            throw new IllegalArgumentException("자동 처리 지연은 0 이상");
        }
    }

    public static Settlement after(Duration delay) {
        if (delay == null) {
            throw new IllegalArgumentException("자동 처리 지연 필수");
        }
        return new Settlement(delay);
    }

    public boolean enabled() {
        return delay != null;
    }
}
