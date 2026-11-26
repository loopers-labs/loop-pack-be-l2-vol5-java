package com.loopers.support.incident;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// 이번 실행 구간의 commerce-api 로그를 받아 둔다. 경계 로그는 key-value로 찾고, 증거에는 JSONL로 남긴다.
public final class CapturedLogs implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Logger logger = (Logger) LoggerFactory.getLogger("com.loopers");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public CapturedLogs() {
        appender.start();
        logger.addAppender(appender);
    }

    public List<Entry> events(String event) {
        return entries().stream().filter(entry -> event.equals(entry.values().get("event"))).toList();
    }

    public List<Entry> events(String event, String key, Object value) {
        return events(event).stream().filter(entry -> String.valueOf(value).equals(entry.values().get(key))).toList();
    }

    public List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        List<ILoggingEvent> events;
        // appender는 다른 스레드가 쓰는 동안 잠그므로, 같은 잠금 안에서 복사한다.
        synchronized (appender) {
            events = List.copyOf(appender.list);
        }
        for (ILoggingEvent event : events) {
            Map<String, String> values = new LinkedHashMap<>();
            for (KeyValuePair pair : Objects.requireNonNullElse(event.getKeyValuePairs(), List.<KeyValuePair>of())) {
                values.put(pair.key, String.valueOf(pair.value));
            }
            entries.add(new Entry(event.getInstant(), event.getLevel().toString(), event.getLoggerName(),
                event.getFormattedMessage(), values));
        }
        return entries;
    }

    public String toJsonLines() {
        StringBuilder lines = new StringBuilder();
        for (Entry entry : entries()) {
            ObjectNode node = JSON.createObjectNode()
                .put("timestamp", entry.timestamp().toString())
                .put("level", entry.level())
                .put("logger", entry.logger())
                .put("message", entry.message());
            entry.values().forEach(node::put);
            lines.append(node).append('\n');
        }
        return lines.toString();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }

    public record Entry(Instant timestamp, String level, String logger, String message, Map<String, String> values) {
        public String citation() {
            return values.get("event") + " attemptId=" + values.get("attemptId") + " at=" + timestamp;
        }
    }
}
