package com.loopers.domains.order.in;

import com.loopers.domains.order.app.OrderApplicationService;
import com.loopers.domains.order.app.OrderOutboxRelay;
import com.loopers.domains.payment.app.RefundTrackingService;
import com.loopers.domains.payment.in.RefundCaseMetrics;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.doAnswer;

// 1-3 격벽: 스케줄 작업마다 전용 스레드에서 돈다. 네 작업(10주차 5단계 환불 추적 포함)을 모두 켜고 주기를 50ms로 줄인 뒤, 작업이 부르는 대상을 mock으로 바꿔
// 실행 스레드를 기록하고 필요한 작업만 래치로 붙잡는다. 스케줄 등록(ContextRefreshedEvent) 전에 stub을 끝내 실행 중 stub 경합을 피하고,
// 테스트마다 mock을 초기화하면 stub이 사라지므로 reset을 끈다.
// 스케줄러를 켠 컨텍스트가 캐시에 남지 않게 클래스가 끝나면 닫고, 닫을 때 돌던 작업이 끝날 때까지 기다린다.
@DirtiesContext
@SpringBootTest(properties = {
    "payment.lookup.scheduler.enabled=true",
    "payment.lookup.scheduler.fixed-delay=PT0.05S",
    "commerce.outbox.relay.enabled=true",
    "commerce.outbox.relay.fixed-delay=PT0.05S",
    "commerce.payment.unresolved-metric.enabled=true",
    "commerce.payment.unresolved-metric.refresh-interval=50ms",
    "payment.refund.scheduler.enabled=true",
    "payment.refund.scheduler.fixed-delay=PT0.05S",
    "spring.task.scheduling.shutdown.await-termination=true",
    "spring.task.scheduling.shutdown.await-termination-period=15s"
})
@Import(ScheduledJobIsolationTest.ProbeConfig.class)
class ScheduledJobIsolationTest {
    private static final long WAIT_SECONDS = 3;
    private static final long BLOCK_LIMIT_SECONDS = 10;
    private static final String LOOKUP = "payment-lookup";
    private static final String RELAY = "outbox-relay";
    private static final String METRIC = "unresolved-metric";
    private static final String REFUND = "payment-refund";

    @MockitoBean(reset = MockReset.NONE) private OrderApplicationService orderApplicationService;
    @MockitoBean(reset = MockReset.NONE) private OrderOutboxRelay orderOutboxRelay;
    @MockitoBean(reset = MockReset.NONE) private UnresolvedPaymentMetrics unresolvedPaymentMetrics;
    @MockitoBean(reset = MockReset.NONE) private RefundTrackingService refundTrackingService;
    @MockitoBean(reset = MockReset.NONE) private RefundCaseMetrics refundCaseMetrics;
    @Autowired private JobProbe probe;

    @AfterEach
    void releaseAll() {
        probe.reset();
    }

    @Test
    @DisplayName("결제 재조회·relay·미확인 gauge 갱신은 각자 작업 이름이 붙은 서로 다른 스레드에서 돈다")
    void eachJobRunsOnItsOwnNamedThread() throws InterruptedException {
        probe.clearThreads();

        awaitCalls(LOOKUP, () -> probe.threads(LOOKUP).size(), 1);
        awaitCalls(RELAY, () -> probe.threads(RELAY).size(), 1);
        awaitCalls(METRIC, () -> probe.threads(METRIC).size(), 1);

        assertThat(probe.threads(LOOKUP)).as("결제 재조회 스레드").allMatch(name -> name.startsWith("sched-payment-lookup-"));
        assertThat(probe.threads(RELAY)).as("relay 스레드").allMatch(name -> name.startsWith("sched-outbox-relay-"));
        assertThat(probe.threads(METRIC)).as("gauge 갱신 스레드").allMatch(name -> name.startsWith("sched-unresolved-metric-"));
        assertThat(probe.threads(LOOKUP)).doesNotContainAnyElementsOf(probe.threads(RELAY))
            .doesNotContainAnyElementsOf(probe.threads(METRIC));
        assertThat(probe.threads(RELAY)).doesNotContainAnyElementsOf(probe.threads(METRIC));
    }

    @Test
    @DisplayName("결제 재조회 작업이 붙잡혀 있어도 relay와 미확인 gauge 갱신은 3초 안에 두 번 이상 더 돈다")
    void relayAndMetricRunWhileLookupIsBlocked() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch lookupEntered = probe.blockLookup(release);
            assertThat(lookupEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("결제 재조회 작업이 막힌 장면에 들어감").isTrue();
            int relayBefore = probe.relayCalls.get();
            int metricBefore = probe.metricCalls.get();

            awaitCalls(RELAY, probe.relayCalls::get, relayBefore + 2);
            awaitCalls(METRIC, probe.metricCalls::get, metricBefore + 2);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("결제 재조회와 relay가 함께 붙잡혀 있어도 미확인 gauge 갱신은 3초 안에 두 번 이상 더 돈다")
    void metricRunsWhileLookupAndRelayAreBlocked() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch lookupEntered = probe.blockLookup(release);
            CountDownLatch relayEntered = probe.blockRelay(release);
            assertThat(lookupEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("결제 재조회 작업이 막힌 장면에 들어감").isTrue();
            assertThat(relayEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("relay 작업이 막힌 장면에 들어감").isTrue();
            int metricBefore = probe.metricCalls.get();

            awaitCalls(METRIC, probe.metricCalls::get, metricBefore + 2);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("환불 추적은 sched-payment-refund- 이름의 전용 스레드에서 돌고 다른 세 작업의 스레드와 겹치지 않는다")
    void refundTrackingRunsOnItsOwnNamedThread() throws InterruptedException {
        probe.clearThreads();

        awaitCalls(REFUND, () -> probe.threads(REFUND).size(), 1);
        awaitCalls(LOOKUP, () -> probe.threads(LOOKUP).size(), 1);
        awaitCalls(RELAY, () -> probe.threads(RELAY).size(), 1);
        awaitCalls(METRIC, () -> probe.threads(METRIC).size(), 1);

        assertThat(probe.threads(REFUND)).as("환불 추적 스레드").allMatch(name -> name.startsWith("sched-payment-refund-"));
        assertThat(probe.threads(REFUND)).doesNotContainAnyElementsOf(probe.threads(LOOKUP))
            .doesNotContainAnyElementsOf(probe.threads(RELAY))
            .doesNotContainAnyElementsOf(probe.threads(METRIC));
    }

    @Test
    @DisplayName("환불 추적 작업이 붙잡혀 있어도 결제 재조회·relay·gauge 갱신은 3초 안에 두 번 이상 더 돈다")
    void otherJobsRunWhileRefundTrackingIsBlocked() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch refundEntered = probe.blockRefund(release);
            assertThat(refundEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("환불 추적 작업이 막힌 장면에 들어감").isTrue();
            int lookupBefore = probe.lookupCalls.get();
            int relayBefore = probe.relayCalls.get();
            int metricBefore = probe.metricCalls.get();

            awaitCalls(LOOKUP, probe.lookupCalls::get, lookupBefore + 2);
            awaitCalls(RELAY, probe.relayCalls::get, relayBefore + 2);
            awaitCalls(METRIC, probe.metricCalls::get, metricBefore + 2);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("결제 재조회가 붙잡혀 있어도 환불 추적은 3초 안에 두 번 이상 더 돈다")
    void refundTrackingRunsWhileLookupIsBlocked() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch lookupEntered = probe.blockLookup(release);
            assertThat(lookupEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("결제 재조회 작업이 막힌 장면에 들어감").isTrue();
            int refundBefore = probe.refundCalls.get();

            awaitCalls(REFUND, probe.refundCalls::get, refundBefore + 2);
        } finally {
            release.countDown();
        }
    }

    private void awaitCalls(String job, IntSupplier calls, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (calls.getAsInt() >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        fail("%d초 안에 %s 작업 호출 수가 %d에 닿지 않았다. 마지막 값=%d, 지금까지 실행 스레드=%s",
            WAIT_SECONDS, job, expected, calls.getAsInt(), probe.threads.keySet().stream()
                .map(key -> key + "=" + probe.threads(key)).toList());
    }

    // 작업이 부른 스레드를 기록하고, 켜진 경우에만 해제 래치를 제한 시간까지 기다린다.
    static class JobProbe {
        private final Map<String, Set<String>> threads = new ConcurrentHashMap<>();
        private final AtomicInteger lookupCalls = new AtomicInteger();
        private final AtomicInteger relayCalls = new AtomicInteger();
        private final AtomicInteger metricCalls = new AtomicInteger();
        private final AtomicInteger refundCalls = new AtomicInteger();
        private volatile Gate lookupGate;
        private volatile Gate relayGate;
        private volatile Gate refundGate;

        CountDownLatch blockLookup(CountDownLatch release) {
            Gate gate = new Gate(release);
            lookupGate = gate;
            return gate.entered;
        }

        CountDownLatch blockRelay(CountDownLatch release) {
            Gate gate = new Gate(release);
            relayGate = gate;
            return gate.entered;
        }

        CountDownLatch blockRefund(CountDownLatch release) {
            Gate gate = new Gate(release);
            refundGate = gate;
            return gate.entered;
        }

        void reset() {
            releaseGate(lookupGate);
            releaseGate(relayGate);
            releaseGate(refundGate);
            lookupGate = null;
            relayGate = null;
            refundGate = null;
        }

        void clearThreads() {
            threads.clear();
        }

        Set<String> threads(String job) {
            return threads.getOrDefault(job, Set.of());
        }

        void onLookup() throws InterruptedException {
            record(LOOKUP);
            lookupCalls.incrementAndGet();
            pass(lookupGate);
        }

        void onRelay() throws InterruptedException {
            record(RELAY);
            relayCalls.incrementAndGet();
            pass(relayGate);
        }

        void onMetric() {
            record(METRIC);
            metricCalls.incrementAndGet();
        }

        void onRefund() throws InterruptedException {
            record(REFUND);
            refundCalls.incrementAndGet();
            pass(refundGate);
        }

        private void record(String job) {
            threads.computeIfAbsent(job, key -> ConcurrentHashMap.newKeySet()).add(Thread.currentThread().getName());
        }

        private static void pass(Gate gate) throws InterruptedException {
            if (gate == null) {
                return;
            }
            gate.entered.countDown();
            gate.release.await(BLOCK_LIMIT_SECONDS, TimeUnit.SECONDS);
        }

        private static void releaseGate(Gate gate) {
            if (gate != null) {
                gate.release.countDown();
            }
        }

        private record Gate(CountDownLatch release, CountDownLatch entered) {
            Gate(CountDownLatch release) {
                this(release, new CountDownLatch(1));
            }
        }
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        JobProbe jobProbe() {
            return new JobProbe();
        }

        @Bean
        SmartInitializingSingleton scheduledJobStubs(JobProbe jobProbe, OrderApplicationService orderApplicationService,
            OrderOutboxRelay orderOutboxRelay, UnresolvedPaymentMetrics unresolvedPaymentMetrics,
            RefundTrackingService refundTrackingService) {
            return () -> {
                doAnswer(invocation -> {
                    jobProbe.onLookup();
                    return null;
                }).when(orderApplicationService).expireOverduePayments();
                doAnswer(invocation -> {
                    jobProbe.onLookup();
                    return null;
                }).when(orderApplicationService).reconcileDuePayments();
                doAnswer(invocation -> {
                    jobProbe.onRelay();
                    return null;
                }).when(orderOutboxRelay).runOnce();
                doAnswer(invocation -> {
                    jobProbe.onMetric();
                    return null;
                }).when(unresolvedPaymentMetrics).refresh();
                doAnswer(invocation -> {
                    jobProbe.onRefund();
                    return null;
                }).when(refundTrackingService).trackDueCases();
            };
        }
    }
}
