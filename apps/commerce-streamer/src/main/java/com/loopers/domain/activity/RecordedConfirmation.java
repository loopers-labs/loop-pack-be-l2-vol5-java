package com.loopers.domain.activity;

import java.time.Instant;

public record RecordedConfirmation(String eventId, Long orderId, Instant confirmedAt, String itemsDigest) {
}
