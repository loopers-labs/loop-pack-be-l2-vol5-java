package com.loopers.support.log;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * root logger 에 붙여 이 JVM 의 로그 사건을 모은다. 경계 로그(event key-value)를 이름으로 골라 key-value·MDC 를 읽는다.
 * 쓰고 나면 {@link #close()} 로 떼어 다른 테스트에 남지 않게 한다.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture() {
        appender.start();
        root.addAppender(appender);
    }

    public static LogCapture start() {
        return new LogCapture();
    }

    /** event key 가 {@code event} 인 사건만 기록 순서대로 돌려준다. */
    public List<Captured> events(String event) {
        return appender.list.stream()
            .map(Captured::of)
            .filter(captured -> event.equals(captured.keyValues().get("event")))
            .toList();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
    }

    public record Captured(String level, String message, Map<String, String> keyValues, Map<String, String> mdc) {

        static Captured of(ILoggingEvent event) {
            List<KeyValuePair> pairs = event.getKeyValuePairs() == null ? List.of() : event.getKeyValuePairs();
            Map<String, String> keyValues = pairs.stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> String.valueOf(pair.value), (first, second) -> second));
            return new Captured(event.getLevel().toString(), event.getFormattedMessage(), keyValues, Map.copyOf(event.getMDCPropertyMap()));
        }
    }
}
