package com.loopers.domains.order.in;

import com.loopers.domains.order.app.OrderOutboxRelay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// C2: 인스턴스 켜기 설정(commerce.outbox.relay.enabled)은 이 인스턴스에서 relay 스케줄러를 띄울지 정한다. 여러 인스턴스면 한 대만 켠다(D116).
// 실행 하나라는 전제는 설정 운영에 달려 있고 분산 잠금이 아니다. 이 테스트는 그 운영을 증명하지 못한다.
class OrderOutboxRelaySchedulerTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(OrderOutboxRelay.class, () -> mock(OrderOutboxRelay.class))
        .withUserConfiguration(OrderOutboxRelayScheduler.class);

    @Test
    @DisplayName("relay 켜기 설정이 false면 스케줄러 Bean이 없고 true면 하나 있다")
    void schedulerFollowsEnabledProperty() {
        runner.withPropertyValues("commerce.outbox.relay.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(OrderOutboxRelayScheduler.class));
        runner.withPropertyValues("commerce.outbox.relay.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(OrderOutboxRelayScheduler.class));
    }

    @Test
    @DisplayName("relay 스케줄은 fixedDelay(기본 1초)라 앞 회차가 끝난 뒤에 다음 회차를 시작한다")
    void scheduleIsFixedDelay() throws NoSuchMethodException {
        Scheduled scheduled = OrderOutboxRelayScheduler.class.getMethod("runOnce").getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString()).isEqualTo("${commerce.outbox.relay.fixed-delay:PT1S}");
        assertThat(scheduled.fixedRateString()).isEmpty();
        assertThat(scheduled.cron()).isEmpty();
    }
}
