package com.loopers.support.log;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LogEvents {

    private LogEvents() {
    }

    public static List<ILoggingEvent> during(String loggerName, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            action.run();
        } finally {
            logger.detachAppender(events);
        }
        return events.list;
    }

    public static Map<String, Object> fieldsOf(ILoggingEvent event) {
        Map<String, Object> fields = new HashMap<>();
        if (event.getKeyValuePairs() != null) {
            event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
        }
        return fields;
    }
}
