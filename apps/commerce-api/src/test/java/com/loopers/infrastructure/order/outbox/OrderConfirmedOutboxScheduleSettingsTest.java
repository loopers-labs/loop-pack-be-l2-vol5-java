package com.loopers.infrastructure.order.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Relay는 조건부 갱신으로 인스턴스 사이 경합을 막으므로 모든 인스턴스에서 켠다.
// 테스트는 두 스케줄을 끄고 직접 호출해 시점을 정한다.
class OrderConfirmedOutboxScheduleSettingsTest {

    private static final String RELAY_ENABLED = "order.confirmed-event.relay.enabled";
    private static final String METRICS_ENABLED = "order.confirmed-event.outbox.metrics.enabled";
    private static final String PROFILE = "spring.config.activate.on-profile";

    @DisplayName("Relay와 지표 갱신 스케줄은 기본으로 켜고, 어떤 운영 프로필도 끄지 않으며, test 프로필만 끈다.")
    @Test
    void enablesRelayAndMetricsEverywhereExceptTestProfile() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"));
        PropertySource<?> defaults = documents.getFirst();
        PropertySource<?> test = documents.stream()
            .filter(document -> "test".equals(String.valueOf(document.getProperty(PROFILE))))
            .findFirst()
            .orElseThrow();

        assertThat(value(defaults, RELAY_ENABLED)).isEqualTo("true");
        assertThat(value(defaults, METRICS_ENABLED)).isEqualTo("true");
        assertThat(value(test, RELAY_ENABLED)).isEqualTo("false");
        assertThat(value(test, METRICS_ENABLED)).isEqualTo("false");
        documents.stream()
            .filter(document -> document != test)
            .forEach(document -> {
                assertThat(value(document, RELAY_ENABLED)).as(document.getName()).isIn(null, "true");
                assertThat(value(document, METRICS_ENABLED)).as(document.getName()).isIn(null, "true");
            });
    }

    private String value(PropertySource<?> document, String name) {
        Object value = document.getProperty(name);
        return value == null ? null : value.toString();
    }
}
