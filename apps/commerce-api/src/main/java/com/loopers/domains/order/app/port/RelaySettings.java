package com.loopers.domains.order.app.port;

import java.time.Duration;

public record RelaySettings(int batchSize, Duration maxRound, boolean enabled) {
    public static final RelaySettings DEFAULT = new RelaySettings(100, Duration.ofSeconds(10), true);

    public static RelaySettings sanitize(RelaySettings raw) {
        return raw;
    }
}
