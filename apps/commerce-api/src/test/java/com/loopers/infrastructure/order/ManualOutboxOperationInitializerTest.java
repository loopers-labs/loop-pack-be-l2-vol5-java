package com.loopers.infrastructure.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class ManualOutboxOperationInitializerTest {
    @ParameterizedTest
    @CsvSource({"local, 0, -1", "test, 8080, 8081"})
    void 과거_주문_복구의_포트_분리는_로컬에서만_적용된다(
            String profile, String expectedServerPort, String expectedManagementPort) {
        var environment = new MockEnvironment()
                .withProperty("server.port", "8080")
                .withProperty("management.server.port", "8081")
                .withProperty("order-events.outbox.recover-order-id", "100");
        environment.setActiveProfiles(profile);
        try (var context = new GenericApplicationContext()) {
            context.setEnvironment(environment);

            new ManualOutboxOperationInitializer().initialize(context);

            assertThat(environment.getProperty("server.port")).isEqualTo(expectedServerPort);
            assertThat(environment.getProperty("management.server.port")).isEqualTo(expectedManagementPort);
        }
    }
}
