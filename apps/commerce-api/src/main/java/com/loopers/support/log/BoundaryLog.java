package com.loopers.support.log;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 업무 경계(PG 생성·조회, 결제 결과 적용, 트랜잭션 결과)의 구조화 로그 (W10 과제 2절).
 *
 * <p>사람이 읽는 메시지({@code event k=v ...})와 같은 값을 SLF4J key-value로 함께 남긴다. 운영 프로필의 JSON(logstash) 출력은
 * key-value를 필드로 내보내고, 테스트는 {@code ILoggingEvent#getKeyValuePairs()}로 같은 주문의 기록을 찾는다.
 *
 * <p>허용한 식별자·상태만 넣는다. 카드번호·인증 헤더·제공자 계정 같은 값은 호출하는 쪽에서 넘기지 않는다.
 * 식별자는 로그에만 두고 metric tag로 쓰지 않는다 (고카디널리티).
 */
public final class BoundaryLog {

    /** 경계 로그를 구분하는 key. 값은 {@code pg.create.result}처럼 점으로 나눈 사건 이름이다. */
    public static final String EVENT_KEY = "event";

    private final String event;
    private final Map<String, Object> fields = new LinkedHashMap<>();

    private BoundaryLog(String event) {
        this.event = event;
    }

    public static BoundaryLog event(String event) {
        return new BoundaryLog(event);
    }

    /** 값이 null이면 그대로 null로 남긴다. 빠진 값도 진단 근거라서 지우지 않는다. */
    public BoundaryLog with(String key, Object value) {
        fields.put(key, value);
        return this;
    }

    public void info(Logger log) {
        write(log.atInfo());
    }

    public void warn(Logger log) {
        write(log.atWarn());
    }

    private void write(LoggingEventBuilder builder) {
        StringJoiner message = new StringJoiner(" ", event + " ", "");
        builder.addKeyValue(EVENT_KEY, event);
        fields.forEach((key, value) -> {
            builder.addKeyValue(key, value);
            message.add(key + "=" + value);
        });
        builder.setMessage(message.toString()).log();
    }
}
