package com.loopers.domains.order.out;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// relay 조절용 설정 한 줄(id 1). 사람이 SQL로만 바꾸고 relay는 회차마다 읽는다(D117).
@Entity
@Table(name = "outbox_relay_settings")
public class OutboxRelaySettingsJpaEntity {
    @Id
    private long id;
    @Column(nullable = false)
    private int batchSize;
    @Column(nullable = false)
    private int maxRoundMillis;
    @Column(nullable = false)
    private boolean enabled;
    @Column(nullable = false)
    private Instant updatedAt;

    protected OutboxRelaySettingsJpaEntity() {
    }

    int batchSize() {
        return batchSize;
    }

    int maxRoundMillis() {
        return maxRoundMillis;
    }

    boolean enabled() {
        return enabled;
    }
}
