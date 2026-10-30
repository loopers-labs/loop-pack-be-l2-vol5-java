package com.loopers.domain.activity;

import java.time.Instant;
import java.util.Map;

public record ReceivedRecord(RecordPosition position, String key, Map<String, String> headers, Instant timestamp,
                             byte[] payload) {
}
