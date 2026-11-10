package com.loopers.infrastructure.order;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

public class ManualOutboxOperationInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        var environment = context.getEnvironment();
        if (!environment.acceptsProfiles(Profiles.of("local"))
                || (!environment.containsProperty("order-events.outbox.resume-event-id")
                && !environment.containsProperty("order-events.outbox.recover-order-id"))) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource("manualOutboxOperationPorts", Map.of(
                "server.address", "127.0.0.1", "server.port", "0", "management.server.port", "-1")));
    }
}
