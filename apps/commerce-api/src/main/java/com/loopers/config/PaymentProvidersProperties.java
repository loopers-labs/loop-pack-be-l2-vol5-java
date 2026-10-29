package com.loopers.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("payment.providers")
public record PaymentProvidersProperties(List<String> priority) {
    public PaymentProvidersProperties {
        priority = priority == null ? List.of() : List.copyOf(priority);
    }
}
