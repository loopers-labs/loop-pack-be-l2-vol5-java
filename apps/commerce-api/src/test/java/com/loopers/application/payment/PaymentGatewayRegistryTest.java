package com.loopers.application.payment;

import com.loopers.application.payment.port.PaymentCreateCommand;
import com.loopers.application.payment.port.PaymentCreateResult;
import com.loopers.application.payment.port.PaymentGateway;
import com.loopers.application.payment.port.PaymentTransactionsLookup;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class PaymentGatewayRegistryTest {
    private final PaymentGateway testPg = new FakeGateway("test-pg");
    private final PaymentGateway secondPg = new FakeGateway("second-pg");

    @Test
    @DisplayName("providerId로 어댑터를 찾고 설정 우선순위 순서로 목록을 돌려준다")
    void findsGatewaysByIdAndPriority() {
        PaymentGatewayRegistry registry = new PaymentGatewayRegistry(List.of(testPg, secondPg), List.of("second-pg", "test-pg"));

        assertThat(registry.get("test-pg")).isSameAs(testPg);
        assertThat(registry.get("second-pg")).isSameAs(secondPg);
        assertThat(registry.inPriorityOrder()).containsExactly(secondPg, testPg);
    }

    @Test
    @DisplayName("등록되지 않은 providerId를 찾으면 거절한다")
    void rejectsUnknownProviderId() {
        PaymentGatewayRegistry registry = new PaymentGatewayRegistry(List.of(testPg), List.of("test-pg"));

        assertThatThrownBy(() -> registry.get("toss"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("toss");
    }

    static Stream<Arguments> invalidConfigurations() {
        PaymentGateway testPg = new FakeGateway("test-pg");
        return Stream.of(
            arguments("어댑터 없음", List.of(), List.of("test-pg")),
            arguments("우선순위 비어 있음", List.of(testPg), List.of()),
            arguments("providerId 중복", List.of(testPg, new FakeGateway("test-pg")), List.of("test-pg")),
            arguments("우선순위에 없는 어댑터", List.of(testPg, new FakeGateway("second-pg")), List.of("test-pg")),
            arguments("어댑터가 없는 우선순위 항목", List.of(testPg), List.of("test-pg", "toss")),
            arguments("우선순위 항목 중복", List.of(testPg), List.of("test-pg", "test-pg"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigurations")
    @DisplayName("어댑터·우선순위 설정이 맞지 않으면 레지스트리 생성을 거절한다")
    void rejectsInvalidConfiguration(String name, List<PaymentGateway> gateways, List<String> priority) {
        assertThatThrownBy(() -> new PaymentGatewayRegistry(gateways, priority))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("레지스트리가 돌려준 우선순위 목록은 바꿀 수 없다")
    void priorityListIsUnmodifiable() {
        PaymentGatewayRegistry registry = new PaymentGatewayRegistry(List.of(testPg), List.of("test-pg"));

        assertThatThrownBy(() -> registry.inPriorityOrder().add(secondPg))
            .isInstanceOf(UnsupportedOperationException.class);
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
