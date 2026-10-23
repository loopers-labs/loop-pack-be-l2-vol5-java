package com.loopers.fixture.pg;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 다음 실제 요청 하나에 적용할 응답 plan(5.7). 생성 시에는 값을 검사하지 않고
 * {@link PgFixtureServer#enqueuePlan} 이 대상 operation 과의 조합까지 검사해 IllegalArgumentException 으로 거절한다.
 * createOutcome·reply 가 null 이면 각각 PENDING·NORMAL 이다.
 */
public record ResponsePlan(
    CreateOutcome createOutcome,
    ReplyMode reply,
    Integer delayMs,
    String gateId,
    Integer httpStatus,
    String body,
    Map<String, String> headers
) {

    static final int MAX_DELAY_MS = 10_000;

    public ResponsePlan {
        headers = headers == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    public static ResponsePlan normal() {
        return new ResponsePlan(null, ReplyMode.NORMAL, null, null, null, null, null);
    }

    public static ResponsePlan outcome(CreateOutcome createOutcome) {
        return normal().withCreateOutcome(createOutcome);
    }

    public static ResponsePlan delay(int delayMs) {
        return new ResponsePlan(null, ReplyMode.DELAY, delayMs, null, null, null, null);
    }

    public static ResponsePlan gate(String gateId) {
        return new ResponsePlan(null, ReplyMode.GATE, null, gateId, null, null, null);
    }

    public static ResponsePlan closeEarly() {
        return new ResponsePlan(null, ReplyMode.CLOSE_EARLY, null, null, null, null, null);
    }

    public static ResponsePlan raw(int httpStatus, String body, Map<String, String> headers) {
        return new ResponsePlan(null, ReplyMode.RAW, null, null, httpStatus, body, headers);
    }

    public ResponsePlan withCreateOutcome(CreateOutcome outcome) {
        return new ResponsePlan(outcome, reply, delayMs, gateId, httpStatus, body, headers);
    }

    ReplyMode effectiveReply() {
        return reply == null ? ReplyMode.NORMAL : reply;
    }

    CreateOutcome effectiveCreateOutcome() {
        return createOutcome == null ? CreateOutcome.PENDING : createOutcome;
    }

    void validateFor(PgOperation operation) {
        ReplyMode mode = effectiveReply();
        if (createOutcome != null && operation != PgOperation.CREATE) {
            throw invalid("createOutcome 은 CREATE plan 에만 허용");
        }
        if (createOutcome == CreateOutcome.REJECT_BEFORE_CREATE && mode != ReplyMode.NORMAL) {
            throw invalid("REJECT_BEFORE_CREATE 는 reply=NORMAL 만 허용");
        }
        if (mode == ReplyMode.DELAY) {
            if (delayMs == null || delayMs < 0 || delayMs > MAX_DELAY_MS) {
                throw invalid("DELAY 는 0~10000ms delayMs 필수");
            }
        } else if (delayMs != null) {
            throw invalid("delayMs 는 DELAY 에만 허용");
        }
        if (mode == ReplyMode.GATE) {
            if (gateId == null || gateId.isBlank()) {
                throw invalid("GATE 는 비어 있지 않은 gateId 필수");
            }
        } else if (gateId != null) {
            throw invalid("gateId 는 GATE 에만 허용");
        }
        if (mode == ReplyMode.RAW) {
            validateRaw();
        } else if (httpStatus != null || body != null || headers != null) {
            throw invalid("httpStatus·body·headers 는 RAW 에만 허용");
        }
    }

    private void validateRaw() {
        if (httpStatus == null || httpStatus < 200 || httpStatus > 599) {
            throw invalid("RAW 는 200~599 httpStatus 필수");
        }
        if (body == null) {
            throw invalid("RAW 는 문자열 body 필수(빈 문자열 허용)");
        }
        if ((httpStatus == 204 || httpStatus == 304) && !body.isEmpty()) {
            throw invalid("204·304 RAW 는 빈 body 만 허용");
        }
        if (headers == null) {
            return;
        }
        headers.forEach((name, value) -> {
            if (name == null || name.isBlank() || value == null || containsLineBreak(name) || containsLineBreak(value)) {
                throw invalid("RAW header 이름·값 형식 오류");
            }
            // 본문 길이는 서버가 실제 body 로 정한다. 길이 불일치 재현은 CLOSE_EARLY 가 맡는다.
            if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Transfer-Encoding")) {
                throw invalid("RAW 는 Content-Length·Transfer-Encoding header 를 지정할 수 없음");
            }
        });
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
