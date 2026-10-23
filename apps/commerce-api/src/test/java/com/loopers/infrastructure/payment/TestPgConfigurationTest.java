package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.PaymentConfirmationDeadline;
import com.loopers.domain.payment.PaymentCreateRequest;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResult;
import com.loopers.domain.payment.PaymentLookupNotPermittedException;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestPgConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(TestPgConfiguration.class).withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void isDisabledUntilTheLocalHttpBoundaryIsExplicitlyEnabled() {
        runner.run(context -> assertThat(context).doesNotHaveBean(PaymentGateway.class));
    }

    @Test
    void createsTheManagedAdapterWithoutCallingPgOnStartupAndClosesItWithTheContext() throws Exception {
        try (LocalTestPg pg = new LocalTestPg()) {
            AtomicReference<PaymentGateway> gateway = new AtomicReference<>();
            runner.withPropertyValues("commerce.test-pg.enabled=true",
                "commerce.test-pg.base-url=" + pg.baseUri(), "commerce.test-pg.callback-url=" + pg.callbackUri())
                .run(context -> {
                    assertThat(context).hasSingleBean(PaymentGateway.class);
                    assertThat(context.getBean(PaymentGateway.class)).isInstanceOf(CircuitBreakingPaymentGateway.class);
                    assertThat(context.getBean(TestPgProperties.class).timeouts()).isEqualTo(PgHttpTimeouts.defaults());
                    assertThat(PgHttpTimeouts.defaults().createTotal()).isEqualTo(PaymentConfirmationDeadline.CREATE_CALL_BUDGET);
                    assertThat(PgHttpTimeouts.defaults().lookupTotal()).isEqualTo(PaymentConfirmationDeadline.LOOKUP_CALL_BUDGET);
                    assertThat(pg.requests()).isEmpty();
                    gateway.set(context.getBean(PaymentGateway.class));
                    assertThat(gateway.get().create(new PaymentCreateRequest("LP-ORD-42", 7000, "TEST", "0000"))
                        .kind()).isEqualTo(PaymentGatewayResult.Kind.OBSERVED);
                });
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThatThrownBy(() -> gateway.get().findByProviderOrderId("LP-ORD-42"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        }
    }

    @Test
    void managedHttpAdapterSharesItsLookupCircuitWithoutBlockingCreates() throws Exception {
        try (LocalTestPg pg = new LocalTestPg()) {
            runner.withPropertyValues("commerce.test-pg.enabled=true",
                "commerce.test-pg.base-url=" + pg.baseUri(), "commerce.test-pg.callback-url=" + pg.callbackUri())
                .run(context -> {
                    PaymentGateway gateway = context.getBean(PaymentGateway.class);
                    for (int index = 0; index < 10; index++) {
                        pg.replyNext(index < 5 ? 503 : 200, index < 5 ? "{}" : "not-json");
                        assertThat(gateway.findByProviderOrderId("LP-ORD-0000000042").kind())
                            .isEqualTo(PaymentGatewayResult.Kind.UNKNOWN);
                    }
                    assertThat(pg.lookupCalls()).isEqualTo(10);
                    assertThatThrownBy(() -> gateway.findByTransactionKey("TEST-TX-42"))
                        .isInstanceOf(PaymentLookupNotPermittedException.class);
                    assertThat(pg.lookupCalls()).isEqualTo(10);
                    assertThat(gateway.create(new PaymentCreateRequest("LP-ORD-0000000042", 7000, "TEST", "0000"))
                        .kind()).isEqualTo(PaymentGatewayResult.Kind.OBSERVED);
                    assertThat(pg.createCalls()).isEqualTo(1);
                });
        }
    }

    @Test
    void refusesToConfigureANonLocalProvider() {
        runner.withPropertyValues("commerce.test-pg.enabled=true", "commerce.test-pg.base-url=https://example.com")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsAnUnboundedTimeoutInsteadOfSilentlyUsingInfinity() {
        runner.withPropertyValues("commerce.test-pg.enabled=true", "commerce.test-pg.lookup-response-timeout=0ms")
            .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"commerce.test-pg.create-total-timeout=65001ms", "commerce.test-pg.lookup-total-timeout=10001ms"})
    void enabledAdapterCannotExceedTheWholeCallBudgetsUsedByConfirmation(String property) {
        runner.withPropertyValues("commerce.test-pg.enabled=true", property)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shorterWholeCallTimeoutsRemainAvailableForDeterministicHttpTests() {
        runner.withPropertyValues("commerce.test-pg.enabled=true", "commerce.test-pg.create-total-timeout=250ms",
                "commerce.test-pg.lookup-total-timeout=100ms")
            .run(context -> assertThat(context).hasSingleBean(PaymentGateway.class));
    }

    @Test
    void applicationConfigurationDisablesCardBearingWireLogs() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        assertThat(yaml.getObject()).containsEntry("logging.level.org.apache.hc.client5.http.wire", "OFF");
    }
}
