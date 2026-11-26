package com.loopers.support.log;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LogEvents implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogEvents(Logger logger) {
        this.logger = logger;
        appender.start();
        logger.addAppender(appender);
    }

    public static LogEvents of(String loggerName) {
        return new LogEvents((Logger) LoggerFactory.getLogger(loggerName));
    }

    public List<ILoggingEvent> list() {
        return List.copyOf(appender.list);
    }

    public static Map<String, Object> fieldsOf(ILoggingEvent event) {
        Map<String, Object> fields = new HashMap<>();
        if (event.getKeyValuePairs() != null) {
            event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
        }
        return fields;
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
    }
}
