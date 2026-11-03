package com.loopers.domains.order.app.port;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// relay 조절 손잡이(D117). 사람이 SQL로 바꾸므로 범위를 벗어난 필드만 그 필드의 기본값으로 바꾸고 나머지는 그대로 쓴다.
// 사람이 끈 relay가 다른 필드의 잘못된 값 때문에 켜지면 안 되기 때문이다(필드별 대체, PL 결정).
public record RelaySettings(int batchSize, Duration maxRound, boolean enabled) {
    public static final RelaySettings DEFAULT = new RelaySettings(100, Duration.ofSeconds(10), true);

    private static final Logger log = LoggerFactory.getLogger(RelaySettings.class);
    private static final int MIN_BATCH = 1;
    private static final int MAX_BATCH = 1000;
    private static final Duration MIN_ROUND = Duration.ofSeconds(1);
    private static final Duration MAX_ROUND = Duration.ofSeconds(30);

    public static RelaySettings sanitize(RelaySettings raw) {
        if (raw == null) {
            log.warn("relay 설정 행 없음. 기본값을 쓴다. default={}", DEFAULT);
            return DEFAULT;
        }
        int batchSize = raw.batchSize();
        if (batchSize < MIN_BATCH || batchSize > MAX_BATCH) {
            log.warn("relay 설정 묶음 크기가 범위(1~1000) 밖이라 기본값 {}을 쓴다. batchSize={}", DEFAULT.batchSize(), batchSize);
            batchSize = DEFAULT.batchSize();
        }
        Duration maxRound = raw.maxRound();
        if (maxRound == null || maxRound.compareTo(MIN_ROUND) < 0 || maxRound.compareTo(MAX_ROUND) > 0) {
            log.warn("relay 설정 회차 최대 시간이 범위(1~30초) 밖이라 기본값 {}을 쓴다. maxRound={}", DEFAULT.maxRound(), maxRound);
            maxRound = DEFAULT.maxRound();
        }
        return new RelaySettings(batchSize, maxRound, raw.enabled());
    }
}
