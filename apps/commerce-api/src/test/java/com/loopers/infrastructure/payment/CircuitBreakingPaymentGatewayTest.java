package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.port.PaymentCreateCommand;
import com.loopers.application.payment.port.PaymentCreateResult;
import com.loopers.application.payment.port.PaymentGateway;
import com.loopers.application.payment.port.PaymentTransactionsLookup;
import com.loopers.domain.common.Money;
import com.loopers.support.pg.MutablePaymentClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakingPaymentGatewayTest {
    private TestPgServer main;
    private TestPgServer sub;
    private MutablePaymentClock clock;
    private PaymentGateway mainGateway;
    private PaymentGateway subGateway;

    @BeforeEach
    void prepare() throws Exception {
        main = new TestPgServer("135135", Duration.ofMillis(500));
        sub = new TestPgServer("135135", Duration.ofMillis(500));
        clock = new MutablePaymentClock();
        clock.set(Instant.parse("2026-10-21T00:00:00Z"));
        PaymentCircuitBreakers circuits = new PaymentCircuitBreakers(clock,
            new PaymentLookupCircuitProperties(20, 10, 50, Duration.ofSeconds(30), 3));
        mainGateway = circuits.wrap(client("main", main));
        subGateway = circuits.wrap(client("sub", sub));
    }

    @AfterEach
    void close() throws Exception {
        main.close();
        sub.close();
    }

    @Test
    @DisplayName("최소 표본 10회와 실패율 50퍼센트에서 열리고 30초 뒤 탐색 3회 성공으로 다시 닫힌다")
    void opensAtThresholdAndProbesWithInjectedClock() {
        for (int index = 0; index < 5; index++) {
            assertThat(lookup(mainGateway)).isInstanceOf(PaymentTransactionsLookup.Found.class);
        }
        main.lookupBehavior(TestPgServer.LookupBehavior.ERROR_500);
        for (int index = 0; index < 5; index++) {
            assertThat(lookup(mainGateway)).isInstanceOf(PaymentTransactionsLookup.Failed.class);
        }
        assertThat(lookup(mainGateway)).isInstanceOf(PaymentTransactionsLookup.NotCalled.class);
        assertThat(main.lookupCalls()).isEqualTo(10);
        main.lookupBehavior(TestPgServer.LookupBehavior.NORMAL);
        clock.advance(Duration.ofSeconds(31));
        for (int index = 0; index < 4; index++) {
            assertThat(lookup(mainGateway)).isInstanceOf(PaymentTransactionsLookup.Found.class);
        }
        assertThat(main.lookupCalls()).isEqualTo(14);
        assertThat(main.createCalls()).isZero();
    }

    @Test
    @DisplayName("거래 0건은 성공 표본이므로 20회 반복해도 회로가 열리지 않는다")
    void emptyTransactionsAreHealthy() {
        for (int index = 0; index < 21; index++) {
            assertThat(lookup(mainGateway)).isInstanceOf(PaymentTransactionsLookup.Found.class);
        }
        assertThat(main.lookupCalls()).isEqualTo(21);
        assertThat(main.transactions()).isEmpty();
        assertThat(main.createCalls()).isZero();
    }

    @Test
    @DisplayName("main 조회 회로가 열려도 sub 조회와 main create는 실제로 나간다")
    void isolatesProvidersAndDoesNotWrapCreate() {
        main.lookupBehavior(TestPgServer.LookupBehavior.ERROR_500);
        for (int index = 0; index < 10; index++) {
            lookup(mainGateway);
        }
        assertThat(lookup(mainGateway)).isInstanceOf(PaymentTransactionsLookup.NotCalled.class);
        assertThat(lookup(subGateway)).isInstanceOf(PaymentTransactionsLookup.Found.class);
        PaymentCreateResult created = mainGateway.create(new PaymentCreateCommand("LP-ORD-0000000042", "SAMSUNG",
            "1234-5678-9814-1451", new Money(2000)));
        assertThat(created).isInstanceOf(PaymentCreateResult.Accepted.class);
        assertThat(main.lookupCalls()).isEqualTo(10);
        assertThat(sub.lookupCalls()).isEqualTo(1);
        assertThat(main.createCalls()).isEqualTo(1);
        assertThat(main.transactions()).hasSize(1);
        assertThat(sub.createCalls()).isZero();
    }

    private PaymentTransactionsLookup lookup(PaymentGateway gateway) {
        return gateway.verifyOrder("LP-ORD-0000000042", null);
    }

    private PaymentGateway client(String providerId, TestPgServer server) {
        return new TestPgPaymentGateway(new TestPgProperties(server.baseUrl(), "135135", "http://127.0.0.1/callback",
            Duration.ofMillis(200), Duration.ofMillis(200)), new ObjectMapper()) {
            @Override
            public String providerId() {
                return providerId;
            }
        };
    }
}
