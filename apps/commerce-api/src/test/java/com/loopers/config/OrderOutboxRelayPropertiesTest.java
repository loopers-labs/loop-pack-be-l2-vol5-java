package com.loopers.config;

import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OrderOutboxRetryPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;

/** relay 설정 바인딩·기동 거절(설계 W7.3-1). 앱 YAML 의 실제 값은 OrderOutboxRelayIntegrationTest 가 본다. */
@DisplayName("relay 설정은 승인된 기본값을 갖고, 0 이하·상한<초기·한도<1 이면 기동을 거절한다.")
class OrderOutboxRelayPropertiesTest {

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
        .withUserConfiguration(Support.class, OrderOutboxRelayConfig.class);

    @Configuration
    @EnableConfigurationProperties(OrderOutboxRelayProperties.class)
    static class Support {

        /** 구현 08-1 에서 relay 설정이 scheduler 도 조립한다. 이 테스트는 바인딩만 보므로 relay 는 호출되지 않는 대역이다(기본 enabled=false). */
        @Bean
        OrderOutboxRelay orderOutboxRelay() {
            return mock(OrderOutboxRelay.class);
        }
    }

    @DisplayName("설정이 없으면 enabled=false·1초·10건·35초·5초·60초·10회이고, policy 가 같은 값으로 조립된다.")
    @Test
    void bindsDefaults() {
        RUNNER.run(context -> {
            OrderOutboxRelayProperties properties = context.getBean(OrderOutboxRelayProperties.class);
            assertAll(
                () -> assertThat(properties).isEqualTo(new OrderOutboxRelayProperties(false, Duration.ofSeconds(1), 10,
                    Duration.ofSeconds(35), Duration.ofSeconds(5), Duration.ofSeconds(60), 10)),
                () -> assertThat(context.getBean(OrderOutboxRetryPolicy.class).delayFor(5)).isEqualTo(Duration.ofSeconds(60)),
                () -> assertThat(context.getBean(OrderOutboxRetryPolicy.class).maxCycleAttempts()).isEqualTo(10)
            );
        });
    }

    @DisplayName("잘못된 값·관계는 기동 오류다.")
    @ParameterizedTest
    @ValueSource(strings = {
        "commerce.order-events.relay.fixed-delay=0s",
        "commerce.order-events.relay.batch-size=0",
        "commerce.order-events.relay.send-wait-timeout=-1s",
        "commerce.order-events.relay.initial-retry-delay=0s",
        "commerce.order-events.relay.max-retry-delay=4s",
        "commerce.order-events.relay.max-cycle-attempts=0"
    })
    void rejectsInvalid(String property) {
        RUNNER.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
}
