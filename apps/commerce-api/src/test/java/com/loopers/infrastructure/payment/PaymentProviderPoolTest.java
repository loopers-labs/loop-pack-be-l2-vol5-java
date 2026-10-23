package com.loopers.infrastructure.payment;

import com.loopers.domain.common.Money;
import com.loopers.domain.payment.CardNumber;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.CreateObservation;
import com.loopers.domain.payment.LookupResult;
import com.loopers.domain.payment.PaymentCreation;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.domain.payment.TransactionKey;
import com.loopers.support.pg.PgFixture;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = PaymentProviderContractTest.Context.class)
@ActiveProfiles("test")
class PaymentProviderPoolTest {

    private static final PgFixture PG = PgFixture.start();

    @Autowired
    private PaymentGateway gateway;

    @Autowired
    private CircuitBreaker lookupCircuit;

    @DynamicPropertySource
    static void pg(DynamicPropertyRegistry registry) {
        registry.add("pg.base-url", PG::baseUrl);
        registry.add("pg.create-concurrency", () -> "1");
        registry.add("pg.lookup-max-connections", () -> "1");
    }

    @BeforeEach
    void setUp() {
        PG.reset();
        lookupCircuit.reset();
    }

    @AfterAll
    static void stopPg() {
        PG.close();
    }

    @DisplayName("PG-015: 생성 풀에서 연결을 얻지 못하면 아무것도 보내지 않은 NotSent 다")
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

    @DisplayName("RESIL-010: 생성이 생성 풀을 모두 쓰고 있어도 조회는 조회 풀에서 연결을 얻는다")
    @Test
    void lookupKeepsItsOwnPool() throws Exception {
        PgFixture.HeldResponse held = PG.holdNextCreateResponse();
        CompletableFuture<CreateObservation> create = CompletableFuture.supplyAsync(() -> gateway.create(creation(52L)));
        held.awaitStored();

        LookupResult result = gateway.lookup(ProviderOrderId.of(53L));

        held.release();
        assertThat(result).isEqualTo(new LookupResult.NotFound());
        assertThat(create.get(5, TimeUnit.SECONDS)).isInstanceOf(CreateObservation.Accepted.class);
    }

    @DisplayName("RESIL-011: 조회 풀을 기다리다 실패한 조회는 조회 불가지만, 우리 쪽 포화라 회로의 실패로 세지 않는다")
    @Test
    void poolWaitIsNotCountedByCircuit() throws Exception {
        PG.delayLookups(Duration.ofMillis(800));
        int arrived = PG.lookupCount();
        CompletableFuture<LookupResult> holder = CompletableFuture.supplyAsync(
            () -> gateway.lookup(new TransactionKey("TEST-TX-HOLD")));
        awaitLookupArrived(arrived);

        LookupResult waited = gateway.lookup(new TransactionKey("TEST-TX-WAIT"));

        PG.delayLookups(Duration.ZERO);
        holder.get(5, TimeUnit.SECONDS);
        assertThat(waited).isEqualTo(new LookupResult.Unavailable());
        assertThat(PG.lookupCount()).isEqualTo(arrived + 1);
        assertThat(lookupCircuit.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(lookupCircuit.getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
    }

    private static void awaitLookupArrived(int before) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (PG.lookupCount() == before && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(PG.lookupCount()).isEqualTo(before + 1);
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
