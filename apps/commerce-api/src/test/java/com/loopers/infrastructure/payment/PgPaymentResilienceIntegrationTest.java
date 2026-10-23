package com.loopers.infrastructure.payment;

import com.loopers.application.payment.PaymentHttpTestSupport;
import com.loopers.application.payment.PaymentTransactionProbe;
import com.loopers.domain.payment.LocalNotDispatchedReason;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentCheckToken;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.fixture.pg.GateStatus;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import(PgPaymentResilienceIntegrationTest.ControlledGatewayConfig.class)
@Timeout(10)
class PgPaymentResilienceIntegrationTest extends PaymentHttpTestSupport {
    @Autowired private Control control;
    @Autowired private PgHttpProperties properties;
    @Autowired @Qualifier("pgHttpClient") private CloseableHttpClient client;
    @Autowired @Qualifier("pgRestClient") private RestClient rest;
    @Autowired private com.loopers.application.payment.PaymentTransactionService transactions;
    private final AtomicInteger txB = new AtomicInteger();

    @BeforeEach
    void controls() {
        control.mode = "NONE";
        control.admissionEntered = new java.util.concurrent.CountDownLatch(1);
        control.admissionRelease = new java.util.concurrent.CountDownLatch(1);
        control.entered.set(0);
        assertThat(properties.maxConnections()).isEqualTo(10);
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.poolAcquireTimeout()).isEqualTo(Duration.ofMillis(200));
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", order, txB::incrementAndGet);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN", "HALF_OPEN"})
    void postAdmissionRejectionReleasesOnceWithoutCreating(String mode) {
        control.mode = mode;
        var response = confirm(order);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(payment(order)).containsEntry("status", "FAILED").containsEntry("failure_kind", "CIRCUIT_REJECTED")
            .containsEntry("transaction_key", null).containsEntry("verification_status", "NOT_CHECKED")
            .containsEntry("verification_checked_at", null);
        assertThat(control.entered.get()).isZero();
        assertThat(txB.get()).isEqualTo(1);
        assertThat(stock(product.getId())).isEqualTo(5);
        assertThat(histories(order, "ORDER_RELEASE")).isEqualTo(1);
        assertThat(pg.state().counts().createReceived()).isZero();
        assertThat(circuits.circuitBreaker("pgCreate").getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(confirm(order).getStatusCode().value()).isEqualTo(200);
        assertThat(histories(order, "ORDER_RELEASE")).isEqualTo(1);
        noPointEffect();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BODY_REJECT", "BEFORE_LOOKUP_REJECT", "BEFORE_ERROR"})
    void programErrorsNeverInventNotDispatchedOrTxB(String mode) {
        control.mode = mode;
        assertThat(confirm(order).getStatusCode().value()).isEqualTo(500);
        assertThat(control.entered.get()).isEqualTo(mode.equals("BODY_REJECT") ? 1 : 0);
        assertThat(payment(order)).containsEntry("status", "UNKNOWN").containsEntry("failure_kind", null)
            .containsEntry("verification_status", "NOT_CHECKED").containsEntry("verification_checked_at", null);
        assertThat(detail(order).path("status").asText()).isEqualTo("PAYMENT_PENDING");
        assertThat(stock(product.getId())).isEqualTo(3);
        assertThat(histories(order, "ORDER_RELEASE")).isZero();
        assertThat(pg.state().counts().createReceived()).isZero();
        assertThat(circuits.circuitBreaker("pgCreate").getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(txB.get()).isZero();
    }

    @Test
    void validHttpResponseFollowedByProgrammingErrorDoesNotApplyUnavailable() {
        confirm(order);
        var before = payment(order);
        circuits.circuitBreaker("pgLookup").reset();
        txB.set(0);
        control.mode = "DECODE_ERROR";
        assertThat(reconcile(order).getStatusCode().value()).isEqualTo(500);
        assertThat(pg.state().counts().lookupByKeyReceived()).isEqualTo(1);
        assertThat(circuits.circuitBreaker("pgLookup").getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(txB.get()).isZero();
        assertThat(payment(order)).isEqualTo(before);
        assertThat(stock(product.getId())).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualTenLeasedConnectionsProveCreateNotSentAndLookupUnavailable(boolean rollbackRelease) throws Exception {
        Long other = draft(products.createProduct("조회 대조 상품", 2000L, 5L));
        transactions.beginConfirmation(user, other, com.loopers.fixture.PgCommands.forOrder(other));
        if (rollbackRelease) {
            probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", order, () -> {
                assertThat(stock(product.getId())).isEqualTo(5);
                assertThat(histories(order, "ORDER_RELEASE")).isEqualTo(1);
                throw new IllegalStateException("반환 SQL 후 실패");
            });
        }
        PoolingHttpClientConnectionManager pool = (PoolingHttpClientConnectionManager) ReflectionTestUtils.getField(client, "connManager");
        assertThat(pool).isNotNull();
        List<Future<Integer>> holders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String target = "LP-ORD-HELD-" + i;
            pg.enqueuePlan(PgOperation.LOOKUP_ORDER, target, ResponsePlan.gate("pool-" + i));
            holders.add(async(() -> rest.get().uri("/api/v1/payments?orderId=" + target).exchange((request, response) -> {
                response.getBody().readAllBytes();
                return response.getStatusCode().value();
            })));
        }
        try {
            await().atMost(Duration.ofMillis(700)).pollInterval(Duration.ofMillis(2)).until(() ->
                pool.getTotalStats().getLeased() == 10
                    && pg.state().gates().stream().filter(gate -> gate.status() == GateStatus.WAITING).count() == 10);
            long start = System.nanoTime();
            assertThat(reconcile(other).getStatusCode().value()).isEqualTo(202);
            assertThat(payment(other)).containsEntry("status", "UNKNOWN").containsEntry("verification_status", "UNAVAILABLE");
            assertThat(histories(other, "ORDER_RELEASE")).isZero();
            assertThat(circuits.circuitBreaker("pgLookup").getMetrics().getNumberOfBufferedCalls()).isZero();
            assertThat(confirm(order).getStatusCode().value()).isEqualTo(rollbackRelease ? 500 : 200);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(900));
            assertThat(holders).allSatisfy(task -> assertThat(task.isDone()).as("점유 요청은 아직 읽기 timeout 전").isFalse());
            assertThat(pool.getTotalStats().getLeased()).isEqualTo(10);
            assertThat(pg.state().counts().createReceived()).isZero();
            assertThat(pg.state().transactions()).isEmpty();
            assertThat(pg.state().counts().lookupByOrderReceived()).isEqualTo(10);
            assertThat(circuits.circuitBreaker("pgCreate").getMetrics().getNumberOfBufferedCalls()).isZero();
        } finally {
            for (int i = 0; i < 10; i++) { pg.releaseGate("pool-" + i); }
            for (var holder : holders) { assertThat(holder.get(3, TimeUnit.SECONDS)).isEqualTo(200); }
        }
        await().atMost(Duration.ofSeconds(3)).until(() -> pool.getTotalStats().getLeased() == 0 && pool.getTotalStats().getPending() == 0);
        probe.clear();
        if (rollbackRelease) {
            assertThat(payment(order)).containsEntry("status", "UNKNOWN").containsEntry("failure_kind", null)
                .containsEntry("verification_status", "NOT_CHECKED");
            assertThat(stock(product.getId())).isEqualTo(3);
            assertThat(histories(order, "ORDER_RELEASE")).isZero();
            assertThat(reconcile(order).getStatusCode().value()).isEqualTo(202);
            assertThat(stock(product.getId())).isEqualTo(3);
        } else {
            assertThat(payment(order)).containsEntry("status", "FAILED").containsEntry("failure_kind", "POOL_ACQUIRE_TIMEOUT")
                .containsEntry("transaction_key", null).containsEntry("verification_status", "NOT_CHECKED")
                .containsEntry("verification_checked_at", null);
            var token = new PaymentCheckToken(((Number) payment(order).get("id")).longValue(), order, ObservationSource.CREATE, false);
            transactions.applyObservation(token, new PaymentObservation.LocalNotDispatched(LocalNotDispatchedReason.POOL_ACQUIRE_TIMEOUT));
            assertThat(confirm(order).getStatusCode().value()).isEqualTo(200);
            assertThat(stock(product.getId())).isEqualTo(5);
            assertThat(histories(order, "ORDER_RELEASE")).isEqualTo(1);
        }
        assertThat(pg.state().counts().createReceived()).isZero();
        noPointEffect();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELAY", "CLOSE_EARLY"})
    void lookupIoFailureReturnsConnectionWithoutRetryOrRelease(String mode) {
        confirm(order);
        String key = key(order);
        pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, key,
            mode.equals("DELAY") ? ResponsePlan.delay(1500) : ResponsePlan.closeEarly());
        long started = System.nanoTime();
        assertThat(reconcile(order).getStatusCode().value()).isEqualTo(202);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        System.out.println("[I5 lookup I/O " + mode + "] " + elapsed.toMillis() + "ms");
        if (mode.equals("DELAY")) { assertThat(elapsed).isBetween(Duration.ofMillis(950), Duration.ofSeconds(3)); }
        assertThat(payment(order)).containsEntry("status", "PENDING").containsEntry("verification_status", "UNAVAILABLE");
        assertThat(circuits.circuitBreaker("pgLookup").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        PoolingHttpClientConnectionManager pool = (PoolingHttpClientConnectionManager) ReflectionTestUtils.getField(client, "connManager");
        assertThat(pool.getTotalStats().getLeased()).isZero();
        assertThat(pool.getTotalStats().getPending()).isZero();
        assertThat(pg.state().counts().lookupByKeyReceived()).isEqualTo(1);
        assertThat(histories(order, "ORDER_RELEASE")).isZero();
        assertThat(reconcile(order).getStatusCode().value()).isEqualTo(202);
        assertThat(payment(order)).containsEntry("verification_status", "VERIFIED");
        assertThat(pg.state().counts().lookupByKeyReceived()).isEqualTo(2);
    }

    @Test
    void circuitRejectionReturnRollbackDoesNotConvertUnknownToFailureLater() {
        control.mode = "OPEN";
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", order, () -> {
            assertThat(stock(product.getId())).isEqualTo(5);
            throw new IllegalStateException("미발송 반환 SQL 뒤 실패");
        });
        assertThat(confirm(order).getStatusCode().value()).isEqualTo(500);
        assertThat(payment(order)).containsEntry("status", "UNKNOWN").containsEntry("failure_kind", null)
            .containsEntry("verification_status", "NOT_CHECKED");
        assertThat(stock(product.getId())).isEqualTo(3);
        assertThat(histories(order, "ORDER_RELEASE")).isZero();
        probe.clear();
        assertThat(reconcile(order).getStatusCode().value()).isEqualTo(202);
        assertThat(payment(order)).containsEntry("status", "UNKNOWN").containsEntry("failure_kind", null);
        assertThat(pg.state().counts().createReceived()).isZero();
    }

    @Test
    void realConnectionRefusalKeepsClaimedAttemptAndReservation() throws Exception {
        int closedPort;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }
        var closed = java.net.URI.create("http://127.0.0.1:" + closedPort);
        var configured = new PgHttpProperties(closed, properties.callbackUrl(), properties.accountId(), properties.maxConnections(),
            properties.poolAcquireTimeout(), properties.connectTimeout(), properties.readTimeout());
        var transport = RestClient.builder().requestFactory(new org.springframework.http.client.HttpComponentsClientHttpRequestFactory(client))
            .baseUrl(closed.toString()).defaultHeader("X-USER-ID", properties.accountId()).build();
        var actualGateway = new PgHttpGateway(transport, new PgResponseDecoder(), configured, circuits);
        var facade = new com.loopers.application.order.OrderConfirmFacade(transactions, actualGateway,
            new com.loopers.application.payment.PaymentObservationApplier(transactions, new com.loopers.support.persistence.ConstraintViolationClassifier()),
            new com.loopers.support.persistence.ConstraintViolationClassifier());
        assertThat(facade.confirm(user, order, com.loopers.fixture.PgCommands.forOrder(order)).outcome())
            .isEqualTo(com.loopers.application.payment.PaymentApplyResult.Outcome.UNAVAILABLE);
        assertThat(payment(order)).containsEntry("status", "UNKNOWN").containsEntry("failure_kind", null)
            .containsEntry("verification_status", "UNAVAILABLE");
        assertThat(stock(product.getId())).isEqualTo(3);
        assertThat(histories(order, "ORDER_RELEASE")).isZero();
        assertThat(pg.state().counts().createReceived()).isZero();
        assertThat(circuits.circuitBreaker("pgCreate").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void blockedAdmissionRechecksRealConcurrentCommittedAttempt() throws Exception {
        assertThat(circuits.circuitBreaker("pgCreate").tryAcquirePermission()).isTrue();
        circuits.circuitBreaker("pgCreate").releasePermission();
        circuits.circuitBreaker("pgCreate").transitionToOpenState();
        control.mode = "PAUSE_BLOCKED";
        try {
            var running = async(() -> confirm(order));
            assertThat(control.admissionEntered.await(2, TimeUnit.SECONDS)).isTrue();
            transactions.beginConfirmation(user, order, com.loopers.fixture.PgCommands.forOrder(order));
            control.admissionRelease.countDown();
            assertThat(running.get(3, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(202);
            assertThat(payment(order)).containsEntry("status", "UNKNOWN").containsEntry("verification_status", "NOT_CHECKED");
            assertThat(histories(order, "ORDER_RESERVATION")).isEqualTo(1);
            assertThat(stock(product.getId())).isEqualTo(3);
            assertThat(pg.state().counts().createReceived()).isZero();
            assertThat(circuits.circuitBreaker("pgCreate").getMetrics().getNumberOfBufferedCalls()).isZero();
        } finally { control.admissionRelease.countDown(); }
    }

    static class Control implements PgHttpGateway.CreateCallHook {
        volatile String mode = "NONE";
        final AtomicInteger entered = new AtomicInteger();
        volatile java.util.concurrent.CountDownLatch admissionEntered;
        volatile java.util.concurrent.CountDownLatch admissionRelease;
        private final CircuitBreakerRegistry registry;
        Control(CircuitBreakerRegistry registry) { this.registry = registry; }
        @Override
        public void beforeDecorator() {
            CircuitBreaker create = registry.circuitBreaker("pgCreate");
            switch (mode) {
                case "OPEN" -> create.transitionToOpenState();
                case "HALF_OPEN" -> {
                    create.transitionToOpenState();
                    create.transitionToHalfOpenState();
                    assertThat(create.tryAcquirePermission()).isTrue();
                    assertThat(create.tryAcquirePermission()).isTrue();
                }
                case "BEFORE_LOOKUP_REJECT" -> throw CallNotPermittedException.createCallNotPermittedException(registry.circuitBreaker("pgLookup"));
                case "BEFORE_ERROR" -> throw new IllegalStateException("decorator 전 오류");
                default -> { }
            }
        }
        @Override
        public void afterEntered() {
            entered.incrementAndGet();
            if (mode.equals("BODY_REJECT")) {
                throw CallNotPermittedException.createCallNotPermittedException(registry.circuitBreaker("pgCreate"));
            }
        }
    }

    @TestConfiguration
    static class ControlledGatewayConfig {
        @Bean Control control(CircuitBreakerRegistry registry) { return new Control(registry); }
        @Bean @Primary
        PgHttpGateway controlledGateway(@Qualifier("pgRestClient") RestClient rest, PgHttpProperties properties,
                CircuitBreakerRegistry registry, Control control) {
            PgResponseDecoder decoder = new PgResponseDecoder() {
                @Override
                PgLookupResult decodeLookupByKey(PgHttpResponse response, String key) {
                    PgLookupResult result = super.decodeLookupByKey(response, key);
                    if (control.mode.equals("DECODE_ERROR")) {
                        throw new PgAdapterProgrammingException("정상 해석 뒤 오류", new IllegalStateException("대조군"));
                    }
                    return result;
                }
            };
            return new PgHttpGateway(rest, decoder, properties, registry, control) {
                @Override
                public com.loopers.domain.payment.CreateAdmission checkCreateAdmission() {
                    var admission = super.checkCreateAdmission();
                    if (control.mode.equals("PAUSE_BLOCKED")) {
                        control.admissionEntered.countDown();
                        try { assertThat(control.admissionRelease.await(3, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                    }
                    return admission;
                }
            };
        }
    }
}
