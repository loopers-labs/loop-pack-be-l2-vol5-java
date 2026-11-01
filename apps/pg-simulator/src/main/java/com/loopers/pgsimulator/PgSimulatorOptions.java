package com.loopers.pgsimulator;

/** port 0 은 임의 포트다. 주소는 항상 127.0.0.1 이다. */
public record PgSimulatorOptions(int port, OutcomePolicy outcomePolicy, Settlement settlement) {

    public PgSimulatorOptions {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port 는 0~65535");
        }
        if (outcomePolicy == null || settlement == null) {
            throw new IllegalArgumentException("outcomePolicy·settlement 필수");
        }
    }
}
