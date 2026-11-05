package com.loopers.batch.job.reconcile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

// 재발행 명령 v1 계약(설계 27절). batch는 OrderConfirmed 형식을 모르고 주문 ID만 넘긴다. 이벤트를 만드는 쪽은 commerce-api다.
public final class RepublishCommandSerializer {
    public static final String COMMAND_TYPE = "RepublishOrderConfirmed";
    public static final int SCHEMA_VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RepublishCommandSerializer() {
    }

    public static String serialize(long orderId, Instant requestedAt) {
        try {
            return JSON.writeValueAsString(JSON.createObjectNode()
                .put("commandType", COMMAND_TYPE)
                .put("schemaVersion", SCHEMA_VERSION)
                .put("orderId", orderId)
                .put("requestedAt", DateTimeFormatter.ISO_INSTANT.format(requestedAt)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("재발행 명령을 만들지 못했습니다. orderId=" + orderId, e);
        }
    }
}
