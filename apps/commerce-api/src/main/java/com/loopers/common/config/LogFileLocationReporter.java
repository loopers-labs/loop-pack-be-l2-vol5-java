package com.loopers.common.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class LogFileLocationReporter {
    @EventListener(ApplicationReadyEvent.class)
    public void report() {
    }
}
