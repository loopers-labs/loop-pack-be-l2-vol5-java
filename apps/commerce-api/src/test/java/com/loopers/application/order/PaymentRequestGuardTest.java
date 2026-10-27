package com.loopers.application.order;

import com.loopers.application.order.PaymentGatewayResult.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PaymentRequestGuard 의 세마포어·서킷 (Spring 없음). 서킷은 최근 4건 중 실패·느린 호출이 50% 이상이면 열리고, 테스트 동안 다시 닫히지 않는다.
 * 거절이면 request 를 실행하지 않고 NOT_SENT 인지 본다.
 */
class PaymentRequestGuardTest {
    private static final int WINDOW = 4;
    private static final Duration SLOW_CALL = Duration.ofMillis(100);

    private final AtomicInteger executed = new AtomicInteger();

    private static PaymentRequestGuard guard(int maxConcurrentCalls) {
        return new PaymentRequestGuard(new PaymentRequestGuardProperties(
            new PaymentRequestGuardProperties.Bulkhead(maxConcurrentCalls),
            new PaymentRequestGuardProperties.CircuitBreaker(WINDOW, WINDOW, 50, SLOW_CALL, 50, Duration.ofHours(1), 1)));
    }

    private static PaymentGatewayResult result(Outcome outcome) {
        return new PaymentGatewayResult(outcome, null, null);
    }

    /** request 를 실행하면 세고 outcome 을 돌려준다. */
    private PaymentGatewayResult run(PaymentRequestGuard guard, Outcome outcome) {
        return guard.run(1L, () -> {
            executed.incrementAndGet();
            return result(outcome);
        });
    }

    @DisplayName("ERROR·NOT_SENT·UNKNOWN 이 쌓여 서킷이 열리면 request 를 실행하지 않고 NOT_SENT.")
    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"ERROR", "NOT_SENT", "UNKNOWN"})
    void opens_onFailureResults(Outcome failure) {
        PaymentRequestGuard guard = guard(10);
        for (int i = 0; i < WINDOW; i++) {
            assertThat(run(guard, failure).outcome()).isEqualTo(failure);
        }

        PaymentGatewayResult result = run(guard, Outcome.SUCCESS);

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_SENT);
        assertThat(executed).hasValue(WINDOW);
    }

    @DisplayName("PG 의 거절(FAILED)·PENDING·SUCCESS 는 실패로 세지 않는다.")
    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"FAILED", "PENDING", "SUCCESS"})
    void staysClosed_onNonFailureResults(Outcome outcome) {
        PaymentRequestGuard guard = guard(10);
        for (int i = 0; i < WINDOW; i++) {
            run(guard, outcome);
        }

        assertThat(run(guard, Outcome.SUCCESS).outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(executed).hasValue(WINDOW + 1);
    }

    @DisplayName("slow-call-duration-threshold 를 넘긴 호출이 쌓이면 SUCCESS 라도 서킷이 열린다.")
    @Test
    void opens_onSlowCalls() {
        PaymentRequestGuard guard = guard(10);
        for (int i = 0; i < WINDOW; i++) {
            guard.run(1L, () -> {
                sleep(SLOW_CALL.multipliedBy(2));
                return result(Outcome.SUCCESS);
            });
        }

        assertThat(run(guard, Outcome.SUCCESS).outcome()).isEqualTo(Outcome.NOT_SENT);
        assertThat(executed).hasValue(0);
    }

    @DisplayName("permit 이 모두 잡혀 있으면 기다리지 않고 request 를 실행하지 않은 채 NOT_SENT. 이 거절은 서킷 실패로 세지 않는다.")
    @Test
    void rejectsWithoutWaiting_whenBulkheadFull_andDoesNotOpenCircuit() throws Exception {
        PaymentRequestGuard guard = guard(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<PaymentGatewayResult> holding = CompletableFuture.supplyAsync(() -> guard.run(1L, () -> {
            entered.countDown();
            await(release);
            return result(Outcome.SUCCESS);
        }));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        for (int i = 0; i < WINDOW; i++) {
            assertThat(run(guard, Outcome.SUCCESS).outcome()).isEqualTo(Outcome.NOT_SENT);
        }
        assertThat(executed).hasValue(0);

        release.countDown();
        assertThat(holding.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(run(guard, Outcome.SUCCESS).outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(executed).hasValue(1);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
