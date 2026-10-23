package com.loopers.application.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.infrastructure.payment.TestPgConfiguration;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderPaymentCoordinatorWiringTest {
    private final OrderPaymentStockService stocks = mock(OrderPaymentStockService.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(TestPgConfiguration.class, OrderPaymentCoordinator.class, PaymentConfirmationConfiguration.class)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(OrderPaymentStockService.class, () -> stocks);

    @Test
    void defaultDisabledSettingDoesNotActivateAnIncompleteCustomerPaymentFlow() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(PaymentGateway.class);
            assertThat(context).doesNotHaveBean(OrderPaymentCoordinator.class);
            verifyNoInteractions(stocks);
        });
    }

    @Test
    void enabledSettingWiresInternalCoordinatorAndRealHttpAdapterWithoutSendingOnStartup() throws Exception {
        try (LocalTestPg pg = new LocalTestPg()) {
            runner.withPropertyValues("commerce.test-pg.enabled=true",
                "commerce.test-pg.base-url=" + pg.baseUri(), "commerce.test-pg.callback-url=" + pg.callbackUri())
                .run(context -> {
                    assertThat(context).hasSingleBean(PaymentGateway.class);
                    assertThat(context).hasSingleBean(OrderPaymentCoordinator.class);
                    assertThat(pg.requests()).isEmpty();
                    verifyNoInteractions(stocks);
                });
        }
    }
}
