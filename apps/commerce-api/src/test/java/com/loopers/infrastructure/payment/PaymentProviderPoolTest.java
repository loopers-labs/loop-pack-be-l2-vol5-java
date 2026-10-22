package com.loopers.infrastructure.payment;

import com.loopers.domain.common.Money;
import com.loopers.domain.payment.CardNumber;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.CreateObservation;
import com.loopers.domain.payment.PaymentCreation;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.support.pg.PgFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = PaymentProviderContractTest.Context.class)
@ActiveProfiles("test")
class PaymentProviderPoolTest {

    private static final PgFixture PG = PgFixture.start();

    @Autowired
    private PaymentGateway gateway;

    @DynamicPropertySource
    static void pg(DynamicPropertyRegistry registry) {
        registry.add("pg.base-url", PG::baseUrl);
        registry.add("pg.max-connections", () -> "1");
    }

    @AfterAll
    static void stopPg() {
        PG.close();
    }

    @DisplayName("PG-015: 커넥션 풀에서 연결을 얻지 못하면 아무것도 보내지 않은 NotSent 다")
    @Test
    void notSentWhenPoolIsExhausted() throws Exception {
        PgFixture.HeldResponse held = PG.holdNextCreateResponse();
        CompletableFuture<CreateObservation> first = CompletableFuture.supplyAsync(() -> gateway.create(creation(50L)));
        held.awaitStored();
        int sentBefore = PG.createCount();

        CreateObservation second = gateway.create(creation(51L));

        held.release();
        assertThat(second).isEqualTo(new CreateObservation.NotSent());
        assertThat(PG.createCount()).isEqualTo(sentBefore);
        assertThat(first.get(5, TimeUnit.SECONDS)).isInstanceOf(CreateObservation.Accepted.class);
    }

    private static PaymentCreation creation(long orderId) {
        return new PaymentCreation(
            ProviderOrderId.of(orderId),
            CardType.TEST,
            CardNumber.of("0000-0000-0000-0000"),
            Money.of(7000)
        );
    }
}
