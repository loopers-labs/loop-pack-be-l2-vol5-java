package com.loopers.interfaces.scheduler;

import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.config.SchedulingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class OrderOutboxRelaySchedulerTest {

    private final OrderOutboxRelay relay = mock(OrderOutboxRelay.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(SchedulingConfig.class, OrderOutboxRelayScheduler.class)
        .withBean(OrderOutboxRelay.class, () -> relay);

    @DisplayName("scheduling-enabled가 참이면 fixed-delay마다 relay 회차를 실행한다.")
    @Test
    void runsRelayRepeatedly_whenEnabled() {
        runner.withPropertyValues("order-outbox.relay.scheduling-enabled=true", "order-outbox.relay.fixed-delay=50ms")
            .run(context -> {
                assertThat(context).hasSingleBean(OrderOutboxRelayScheduler.class);
                verify(relay, timeout(5_000).atLeast(2)).runOnce();
            });
    }

    @DisplayName("scheduling-enabled가 거짓이거나 없으면 scheduler를 만들지 않는다. 테스트 프로파일은 끈다.")
    @Test
    void doesNotSchedule_whenDisabled() {
        runner.withPropertyValues("order-outbox.relay.scheduling-enabled=false", "order-outbox.relay.fixed-delay=50ms")
            .run(context -> assertThat(context).doesNotHaveBean(OrderOutboxRelayScheduler.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(OrderOutboxRelayScheduler.class));
    }
}
