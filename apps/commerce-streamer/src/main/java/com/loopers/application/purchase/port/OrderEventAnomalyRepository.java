package com.loopers.application.purchase.port;

import com.loopers.application.purchase.EventSource;
import java.time.Instant;

public interface OrderEventAnomalyRepository {

    enum Kind {
        CONFLICT_SAME_EVENT_ID, CONFLICT_SAME_ORDER, INVALID_PAYLOAD, KEY_MISMATCH
    }

    /**
     * 같은 record 위치(topic, partition, offset)의 이상 기록이 이미 있으면 새로 남기지 않는다.
     */
    void record(Kind kind, String eventId, Long orderId, EventSource source, String detail, Instant detectedAt);
}
