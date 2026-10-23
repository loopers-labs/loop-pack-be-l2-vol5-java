package com.loopers.application.payment;

import com.loopers.fixture.pg.GateStatus;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Timeout(30)
class PaymentCircuitTimingIntegrationTest extends PaymentHttpTestSupport {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void actualFailureRateOpenWaitAndTwoProbesChooseRecovery(boolean secondProbeSucceeds) {
        confirm(order);
        CircuitBreaker lookup = circuits.circuitBreaker("pgLookup");
        List<CircuitBreaker.StateTransition> transitions = new java.util.concurrent.CopyOnWriteArrayList<>();
        lookup.getEventPublisher().onStateTransition(event -> transitions.add(event.getStateTransition()));
        for (int i = 0; i < 5; i++) {
            if (i >= 2) { pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, key(order), ResponsePlan.raw(503, "", null)); }
            assertThat(reconcile(order).getStatusCode().value()).isEqualTo(202);
            assertThat(lookup.getState()).isEqualTo(i == 4 ? CircuitBreaker.State.OPEN : CircuitBreaker.State.CLOSED);
        }
        assertThat(lookup.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(2);
        assertThat(lookup.getMetrics().getNumberOfFailedCalls()).isEqualTo(3);
        assertThat(lookup.getMetrics().getNumberOfSlowCalls()).isZero();
        long gets = pg.state().counts().lookupByKeyReceived();
        assertThat(reconcile(order).getStatusCode().value()).isEqualTo(202);
        assertThat(pg.state().counts().lookupByKeyReceived()).isEqualTo(gets);
        await().pollDelay(Duration.ofMillis(5100)).atMost(Duration.ofSeconds(6))
            .untilAsserted(() -> assertThat(lookup.getState()).isEqualTo(CircuitBreaker.State.OPEN));
        assertThat(reconcile(order).getStatusCode().value()).isEqualTo(202);
        assertThat(lookup.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        if (!secondProbeSucceeds) {
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, key(order), ResponsePlan.raw(503, "", null));
        }
        assertThat(reconcile(order).getStatusCode().value()).isEqualTo(202);
        assertThat(lookup.getState()).isEqualTo(secondProbeSucceeds ? CircuitBreaker.State.CLOSED : CircuitBreaker.State.OPEN);
        assertThat(transitions).contains(CircuitBreaker.StateTransition.CLOSED_TO_OPEN,
            CircuitBreaker.StateTransition.OPEN_TO_HALF_OPEN);
        assertThat(pg.state().counts().lookupByKeyReceived()).isEqualTo(gets + 2);
        assertThat(payment(order)).containsEntry("status", "PENDING");
        assertThat(stock(product.getId())).isEqualTo(3);
        assertThat(histories(order, "ORDER_RELEASE")).isZero();
        noPointEffect();
    }

    @Test
    void threeSlowSuccessfulHttpReadsOutOfFiveOpenWithoutTimeout() {
        confirm(order);
        CircuitBreaker lookup = circuits.circuitBreaker("pgLookup");
        List<Long> elapsed = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            if (i >= 2) { pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, key(order), ResponsePlan.delay(900)); }
            long start = System.nanoTime();
            var response = reconcile(order);
            elapsed.add(Duration.ofNanos(System.nanoTime() - start).toMillis());
            assertThat(response.getStatusCode().value()).isEqualTo(202);
            assertThat(response.getBody().path("data").path("payment").path("verificationStatus").asText()).isEqualTo("VERIFIED");
            assertThat(lookup.getState()).isEqualTo(i == 4 ? CircuitBreaker.State.OPEN : CircuitBreaker.State.CLOSED);
        }
        System.out.println("[I5 slow successful HTTP ms] " + elapsed);
        assertThat(lookup.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(5);
        assertThat(lookup.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(lookup.getMetrics().getNumberOfSlowSuccessfulCalls()).isEqualTo(3);
        assertThat(stock(product.getId())).isEqualTo(3);
        assertThat(histories(order, "ORDER_RELEASE")).isZero();
    }

    @Test
    void actualHalfOpenAllowsTwoConcurrentHttpProbesAndBlocksThird() throws Exception {
        confirm(order);
        CircuitBreaker lookup = circuits.circuitBreaker("pgLookup");
        lookup.transitionToOpenState();
        lookup.transitionToHalfOpenState();
        pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, key(order), ResponsePlan.gate("probe-a"));
        pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, key(order), ResponsePlan.gate("probe-b"));
        try {
            var first = async(() -> reconcile(order));
            var second = async(() -> reconcile(order));
            await().pollInterval(Duration.ofMillis(2)).atMost(Duration.ofMillis(700)).until(() ->
                pg.state().gates().stream().filter(g -> g.status() == GateStatus.WAITING).count() == 2);
            long gets = pg.state().counts().lookupByKeyReceived();
            assertThat(reconcile(order).getStatusCode().value()).isEqualTo(202);
            assertThat(pg.state().counts().lookupByKeyReceived()).isEqualTo(gets);
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();
            pg.releaseGate("probe-a");
            pg.releaseGate("probe-b");
            for (var task : List.of(first, second)) {
                var response = task.get(3, TimeUnit.SECONDS);
                assertThat(response.getBody().path("data").path("payment").path("verificationStatus").asText()).isEqualTo("VERIFIED");
            }
            assertThat(lookup.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(lookup.getMetrics().getNumberOfFailedCalls()).isZero();
        } finally {
            pg.releaseGate("probe-a");
            pg.releaseGate("probe-b");
        }
    }

    @Test
    void approvedTenSecondHalfOpenExpiryHasNoAutomaticPgOrBusinessEffects() {
        confirm(order);
        CircuitBreaker lookup = circuits.circuitBreaker("pgLookup");
        assertThat(lookup.getCircuitBreakerConfig().getMaxWaitDurationInHalfOpenState()).isEqualTo(Duration.ofSeconds(10));
        assertThat(lookup.getCircuitBreakerConfig().isAutomaticTransitionFromOpenToHalfOpenEnabled()).isFalse();
        lookup.transitionToOpenState();
        await().pollDelay(Duration.ofMillis(5100)).atMost(Duration.ofSeconds(6))
            .untilAsserted(() -> assertThat(lookup.getState()).isEqualTo(CircuitBreaker.State.OPEN));
        long start = System.nanoTime();
        assertThat(reconcile(order).getStatusCode().value()).isEqualTo(202);
        assertThat(lookup.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        var before = payment(order);
        var counts = pg.state().counts();
        await().pollDelay(Duration.ofSeconds(9)).atMost(Duration.ofMillis(9500))
            .untilAsserted(() -> assertThat(lookup.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN));
        await().atMost(Duration.ofSeconds(3)).until(() -> lookup.getState() == CircuitBreaker.State.OPEN);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofSeconds(10));
        assertThat(payment(order)).isEqualTo(before);
        assertThat(pg.state().counts()).isEqualTo(counts);
        assertThat(stock(product.getId())).isEqualTo(3);
        assertThat(histories(order, "ORDER_RELEASE")).isZero();
    }

    @Test
    void expiryDoesNotCancelPreviouslyAdmittedWorkAtCircuitLayer() throws Exception {
        CircuitBreakerConfig config = CircuitBreakerConfig.from(circuits.circuitBreaker("pgLookup").getCircuitBreakerConfig())
            .maxWaitDurationInHalfOpenState(Duration.ofMillis(100)).build();
        CircuitBreaker circuit = CircuitBreaker.of("i5-non-cancel", config);
        circuit.transitionToOpenState();
        circuit.transitionToHalfOpenState();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        try {
            var running = async(() -> circuit.executeSupplier(() -> {
                entered.countDown();
                try {
                    assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
                    return "completed";
                } catch (InterruptedException failure) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(2)).until(() -> circuit.getState() == CircuitBreaker.State.OPEN);
            assertThat(running.isDone()).isFalse();
            assertThat(interrupted.get()).isFalse();
            release.countDown();
            assertThat(running.get(2, TimeUnit.SECONDS)).isEqualTo("completed");
        } finally { release.countDown(); }
    }

    @Test
    void databaseWaitBeforePostIsExcludedFromCreateSlowSample() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "beginConfirmation", order, () -> {
            entered.countDown();
            try { assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
        });
        try {
            var running = async(() -> confirm(order));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            await().pollDelay(Duration.ofMillis(900)).atMost(Duration.ofMillis(1500)).untilAsserted(() -> {
                assertThat(circuits.circuitBreaker("pgCreate").getMetrics().getNumberOfBufferedCalls()).isZero();
                assertThat(pg.state().counts().createReceived()).isZero();
            });
            release.countDown();
            assertThat(running.get(3, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(202);
            assertThat(circuits.circuitBreaker("pgCreate").getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
            assertThat(circuits.circuitBreaker("pgCreate").getMetrics().getNumberOfSlowCalls()).isZero();
        } finally { release.countDown(); }
    }
}
