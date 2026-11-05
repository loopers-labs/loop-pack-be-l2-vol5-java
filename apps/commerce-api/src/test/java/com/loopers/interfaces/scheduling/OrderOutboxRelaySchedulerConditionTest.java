package com.loopers.interfaces.scheduling;

import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OrderOutboxTransactions;
import com.loopers.domain.order.OrderConfirmationPublisher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 전달 활성화와 스케줄 활성화의 네 조합에서 앱이 뜨는지, 스케줄러가 둘 다 켜졌을 때만 등록되는지 확인한다. */
class OrderOutboxRelaySchedulerConditionTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        // relay의 "2s" 같은 Duration 기본값을 실제 앱처럼 변환한다.
        .withInitializer(context -> context.getBeanFactory()
            .setConversionService(ApplicationConversionService.getSharedInstance()))
        .withBean(OrderConfirmationPublisher.class, () -> mock(OrderConfirmationPublisher.class))
        .withBean(OrderOutboxTransactions.class, () -> mock(OrderOutboxTransactions.class))
        .withUserConfiguration(OrderOutboxRelay.class, OrderOutboxRelayScheduler.class);

    @ParameterizedTest(name = "enabled={0}, scheduling-enabled={1}")
    @CsvSource({
        "false, false, false, false",
        "false, true,  false, false",
        "true,  false, true,  false",
        "true,  true,  true,  true"
    })
    void schedulerRequiresBothSwitches(boolean enabled, boolean schedulingEnabled,
                                       boolean expectRelay, boolean expectScheduler) {
        runner
            .withPropertyValues(
                "commerce.order-events.enabled=" + enabled,
                "commerce.order-events.relay.scheduling-enabled=" + schedulingEnabled)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeanNamesForType(OrderOutboxRelay.class).length > 0).isEqualTo(expectRelay);
                assertThat(context.getBeanNamesForType(OrderOutboxRelayScheduler.class).length > 0)
                    .isEqualTo(expectScheduler);
            });
    }
}
