package com.loopers.config.experiment;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.LoggerContext;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("experiment-async")
public class ExperimentAsyncLogConfiguration {
    @Bean
    MeterBinder experimentLogQueueMetrics() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var appender = (AsyncAppender) context.getLogger("EXPERIMENT_TRACE").getAppender("EXPERIMENT_ASYNC");
        if (appender == null || !appender.isStarted()) {
            throw new IllegalStateException("Experiment async appender must be started");
        }
        return registry -> {
            registry.gauge("experiment.log.queue.size", appender, AsyncAppender::getNumberOfElementsInQueue);
            registry.gauge("experiment.log.queue.remaining", appender, AsyncAppender::getRemainingCapacity);
            registry.gauge("experiment.log.queue.capacity", appender, AsyncAppender::getQueueSize);
        };
    }
}
