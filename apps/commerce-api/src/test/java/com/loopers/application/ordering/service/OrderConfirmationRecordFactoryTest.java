package com.loopers.application.ordering.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderConfirmationRecordFactoryTest {
    @Test
    @DisplayName("PG 확정 UUID와 마이크로초 시각을 신규 기록에 담는다")
    void usesFixedIdentityAndTruncatesTime() {
        var clock = Clock.fixed(Instant.parse("2026-11-04T01:00:00.123456789Z"), ZoneOffset.UTC);
        var uuid = UUID.fromString("e321d56a-59b3-4cf1-a7d0-6238451af280");
        var factory = new OrderConfirmationRecordFactory(clock, () -> uuid);

        var record = factory.create(1L, 1000L);

        assertThat(record.getConfirmationEventId()).isEqualTo(uuid);
        assertThat(record.getCreatedAt()).isEqualTo(Instant.parse("2026-11-04T01:00:00.123456Z"));
    }
}
