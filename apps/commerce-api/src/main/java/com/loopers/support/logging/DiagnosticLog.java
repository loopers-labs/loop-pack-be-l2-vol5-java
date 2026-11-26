package com.loopers.support.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 진단 사건 한 줄을 {@code EVENT {"event":..,"at":..,...}} 형태로 남긴다(checkout-incident §4.1).
 * 호출자는 허용된 scalar 필드만 넘긴다. 로그 출력 실패가 업무 결과·예외를 바꾸지 않도록 RuntimeException 만 여기서 삼킨다.
 */
public final class DiagnosticLog {

    public static final String INVALID = "INVALID";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PROVIDER_ORDER_ID = Pattern.compile("LP-ORD-[0-9]{10,19}");
    private static final Pattern TRANSACTION_KEY = Pattern.compile("[A-Za-z0-9_-]{1,255}");

    private DiagnosticLog() {
    }

    public static void info(Logger log, String event, Map<String, ?> fields) {
        try {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("event", event);
            line.put("at", Instant.now().toString());
            line.putAll(fields);
            log.info("{} {}", event, MAPPER.writeValueAsString(line));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            reportFailure(log, event, e);
        }
    }

    public static String providerOrderId(String value) {
        return value == null ? null : PROVIDER_ORDER_ID.matcher(value).matches() ? value : INVALID;
    }

    public static String transactionKey(String value) {
        return value == null ? null : TRANSACTION_KEY.matcher(value).matches() ? value : INVALID;
    }

    private static void reportFailure(Logger log, String event, Exception failure) {
        try {
            log.warn("DIAGNOSTIC_LOG_FAILED event={} exception={}", event, failure.getClass().getName());
        } catch (RuntimeException ignored) {
            // 보고 자체도 실패하면 더 할 수 있는 일이 없다.
        }
    }
}
