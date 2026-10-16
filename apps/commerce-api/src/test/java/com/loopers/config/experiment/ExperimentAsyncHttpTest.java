package com.loopers.config.experiment;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.LoggerContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"test", "experiment", "experiment-async"})
class ExperimentAsyncHttpTest extends ExperimentHttpTest {
    @Autowired MeterRegistry registry;

    @Test
    void isolatesTraceLoggerAndExposesBoundedNonDiscardingQueue() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var logger = context.getLogger("EXPERIMENT_TRACE");
        var appender = (AsyncAppender) logger.getAppender("EXPERIMENT_ASYNC");
        assertThat(logger.isAdditive()).isFalse();
        assertThat(appender.isStarted()).isTrue();
        assertThat(appender.getQueueSize()).isEqualTo(8192);
        assertThat(appender.getDiscardingThreshold()).isZero();
        assertThat(appender.isNeverBlock()).isFalse();
        assertThat(appender.getMaxFlushTime()).isEqualTo(30000);
        assertThat(registry.get("experiment.log.queue.capacity").gauge().value()).isEqualTo(8192);
        assertThat(registry.get("experiment.log.queue.size").gauge().value()).isGreaterThanOrEqualTo(0);
    }
}
