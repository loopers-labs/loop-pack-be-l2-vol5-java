package com.loopers.config;

import com.loopers.application.payment.PaymentGatewayRegistry;
import com.loopers.application.payment.port.PaymentCreateCommand;
import com.loopers.application.payment.port.PaymentCreateResult;
import com.loopers.application.payment.port.PaymentGateway;
import com.loopers.application.payment.port.PaymentTransactionsLookup;
import com.loopers.infrastructure.payment.PaymentCircuitBreakers;
import com.loopers.infrastructure.payment.PaymentLookupCircuitProperties;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentGatewayConfigTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PaymentGatewayConfig.class)
        .withBean(PaymentCircuitBreakers.class, () -> new PaymentCircuitBreakers(Clock.systemUTC(),
            new PaymentLookupCircuitProperties(20, 10, 50, Duration.ofSeconds(30), 3)))
        .withBean("testPg", PaymentGateway.class, () -> new FakeGateway("test-pg"));

    @Test
    @DisplayName("PG 어댑터 빈과 우선순위 설정 한 줄을 추가하면 업무 코드 변경 없이 레지스트리에서 조회된다")
    void registersAddedGatewayBySettingOnly() {
        contextRunner
            .withBean("secondPg", PaymentGateway.class, () -> new FakeGateway("second-pg"))
            .withPropertyValues("payment.providers.priority[0]=second-pg", "payment.providers.priority[1]=test-pg")
            .run(context -> {
                PaymentGatewayRegistry registry = context.getBean(PaymentGatewayRegistry.class);
                assertThat(registry.get("second-pg").providerId()).isEqualTo("second-pg");
                assertThat(registry.inPriorityOrder()).extracting(PaymentGateway::providerId)
                    .containsExactly("second-pg", "test-pg");
            });
    }

    @Test
    @DisplayName("우선순위 설정에 없는 PG 어댑터가 있으면 애플리케이션 시작에 실패한다")
    void failsWhenGatewayIsNotInPriority() {
        contextRunner
            .withBean("secondPg", PaymentGateway.class, () -> new FakeGateway("second-pg"))
            .withPropertyValues("payment.providers.priority[0]=test-pg")
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().isInstanceOf(IllegalStateException.class).hasMessageContaining("second-pg"));
    }

    @Test
    @DisplayName("providerId가 중복된 PG 어댑터가 있으면 애플리케이션 시작에 실패한다")
    void failsWhenProviderIdIsDuplicated() {
        contextRunner
            .withBean("anotherTestPg", PaymentGateway.class, () -> new FakeGateway("test-pg"))
            .withPropertyValues("payment.providers.priority[0]=test-pg")
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().isInstanceOf(IllegalStateException.class).hasMessageContaining("test-pg"));
    }

    @Test
    @DisplayName("우선순위 설정이 없으면 애플리케이션 시작에 실패한다")
    void failsWithoutPriority() {
        contextRunner
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().isInstanceOf(IllegalStateException.class));
    }

    private record FakeGateway(String providerId) implements PaymentGateway {
        @Override
        public PaymentCreateResult create(PaymentCreateCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PaymentTransactionsLookup verifyOrder(String providerOrderId, String storedTransactionKey) {
            throw new UnsupportedOperationException();
        }
    }
}
