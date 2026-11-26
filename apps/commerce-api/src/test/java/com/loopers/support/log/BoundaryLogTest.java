package com.loopers.support.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 경계 로그가 사람이 읽는 메시지와 같은 값을 key-value로 함께 남기는지 확인한다. */
class BoundaryLogTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger("boundary-log-test");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @DisplayName("사건 이름과 넣은 순서대로의 key=value를 메시지로 쓰고, 같은 값을 event key와 함께 key-value로 남긴다.")
    @Test
    void writesMessageAndKeyValues() {
        // act
        BoundaryLog.event("pg.create.result").with("providerOrderId", "LP-ORD-0000000001").with("outcome", "UNKNOWN").with("reason", null)
            .warn(logger);

        // assert
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).isEqualTo("pg.create.result providerOrderId=LP-ORD-0000000001 outcome=UNKNOWN reason=null");
        assertThat(keyValues(event)).containsExactly(
            Map.entry(BoundaryLog.EVENT_KEY, "pg.create.result"),
            Map.entry("providerOrderId", "LP-ORD-0000000001"),
            Map.entry("outcome", "UNKNOWN"),
            Map.entry("reason", "null")
        );
    }

    private static Map<String, String> keyValues(ILoggingEvent event) {
        Map<String, String> values = new LinkedHashMap<>();
        for (KeyValuePair pair : event.getKeyValuePairs()) {
            values.put(pair.key, String.valueOf(pair.value));
        }
        return values;
    }
}
