package com.loopers.fixture.pg;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 다음 실제 요청 하나에 적용할 응답 plan(5.7). 아래 팩토리로만 만들 수 있어 reply 방식과 맞지 않는 필드 조합은 생기지 않는다.
 * 값의 범위와 대상 operation 과의 조합은 {@link PgFixtureServer#enqueuePlan} 이 검사해 IllegalArgumentException 으로 거절한다.
 * createOutcome 이 null 이면 PENDING 이다.
 */
public final class ResponsePlan {

    static final int MAX_DELAY_MS = 10_000;

    private final CreateOutcome createOutcome;
    private final ReplyMode reply;
    private final int delayMs;
    private final String gateId;
    private final int httpStatus;
    private final String body;
    private final Map<String, String> headers;

    private ResponsePlan(CreateOutcome createOutcome, ReplyMode reply, int delayMs, String gateId, int httpStatus,
                         String body, Map<String, String> headers) {
        this.createOutcome = createOutcome;
        this.reply = reply;
        this.delayMs = delayMs;
        this.gateId = gateId;
        this.httpStatus = httpStatus;
        this.body = body;
        this.headers = headers == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    public static ResponsePlan normal() {
        return new ResponsePlan(null, ReplyMode.NORMAL, 0, null, 0, null, null);
    }

    /** CREATE 전용. 생성 결과만 정하고 응답은 그대로 보낸다. */
    public static ResponsePlan outcome(CreateOutcome createOutcome) {
        return new ResponsePlan(createOutcome, ReplyMode.NORMAL, 0, null, 0, null, null);
    }

    /** header 전에 실제로 기다린다(0~10000ms). 업무 코드에 sleep 을 넣는 것이 아니다. */
    public static ResponsePlan delay(int delayMs) {
        return new ResponsePlan(null, ReplyMode.DELAY, delayMs, null, 0, null, null);
    }

    /** 거래 효과 뒤 응답 전에 gate 해제를 기다린다. 최대 10초 안에 해제가 없으면 응답 없이 닫는다. */
    public static ResponsePlan gate(String gateId) {
        return new ResponsePlan(null, ReplyMode.GATE, 0, gateId, 0, null, null);
    }

    /** 전체보다 긴 Content-Length 를 선언하고 본문 일부만 보낸 뒤 연결을 닫는다. */
    public static ResponsePlan closeEarly() {
        return new ResponsePlan(null, ReplyMode.CLOSE_EARLY, 0, null, 0, null, null);
    }

    /** PG 응답 대신 이 상태·본문·header 를 보낸다. 저장소는 바꾸지 않는다. */
    public static ResponsePlan raw(int httpStatus, String body, Map<String, String> headers) {
        return new ResponsePlan(null, ReplyMode.RAW, 0, null, httpStatus, body, headers);
    }

    ReplyMode effectiveReply() {
        return reply;
    }

    CreateOutcome effectiveCreateOutcome() {
        return createOutcome == null ? CreateOutcome.PENDING : createOutcome;
    }

    int delayMs() {
        return delayMs;
    }

    String gateId() {
        return gateId;
    }

    int httpStatus() {
        return httpStatus;
    }

    String body() {
        return body;
    }

    Map<String, String> headers() {
        return headers;
    }

    void validateFor(PgOperation operation) {
        if (createOutcome != null && operation != PgOperation.CREATE) {
            throw invalid("createOutcome 은 CREATE plan 에만 허용");
        }
        switch (reply) {
            case DELAY -> {
                if (delayMs < 0 || delayMs > MAX_DELAY_MS) {
                    throw invalid("DELAY 는 0~10000ms");
                }
            }
            case GATE -> {
                if (gateId == null || gateId.isBlank()) {
                    throw invalid("GATE 는 비어 있지 않은 gateId 필수");
                }
            }
            case RAW -> validateRaw();
            default -> {
            }
        }
    }

    private void validateRaw() {
        if (httpStatus < 200 || httpStatus > 599) {
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
