package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.PaymentGateway;
import com.loopers.application.payment.PaymentLookupObservation;
import com.loopers.application.payment.PaymentRequestFacade;
import com.loopers.application.payment.ProviderOrderSuffixGenerator;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.infrastructure.payment.CircuitBreakingPaymentGateway;
import com.loopers.support.testpg.TestPgControl;
import com.loopers.utils.DatabaseCleanUp;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "pg-time-limit-experiment"})
class OrderPaymentPoliciesE2ETest {
    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper json;
    @Autowired private PaymentGateway gateway;
    @Autowired private CircuitBreaker breaker;
    @Autowired private PaymentRequestFacade requests;
    @Autowired private OrderRepository orders;
    @Autowired private ProductRepository products;
    @Autowired private BrandRepository brands;
    @Autowired private UserRepository users;
    @Autowired private PointRepository points;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoBean private ProviderOrderSuffixGenerator suffixGenerator;

    @AfterEach
    void restore() {
        TestPgControl.lookupDelay(0);
        breaker.reset();
        cleanUp.truncateAllTables();
    }

    @DisplayName("전체 조회 시간 초과와 호출 제한 중 예약을 유지하고 회복 뒤 원래 거래의 성공을 한 번만 반영한다.")
    @Test
    void preservesPendingOrderAcrossDeadlineAndRateRejectionThenAppliesOriginalSuccess() throws Exception {
        TestPgControl.lookupDelay(0);
        breaker.reset();
        assertThat(gateway).isInstanceOf(CircuitBreakingPaymentGateway.class);
        Fixture fixture = request();
        String originalKey = attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey();
        List<Map<String, Object>> calls = new ArrayList<>();
        TestPgControl.lookupDelay(1500);
        assertThat(reconcile(fixture, "DEADLINE", calls).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat((double) calls.getFirst().get("elapsedMillis")).isBetween(800.0, 1600.0);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        assertPending(fixture);
        assertThat(TestPgControl.stats(fixture.providerOrderId()).lookupRequests()).isEqualTo(2);

        TestPgControl.lookupDelay(0);
        int rejected = 0;
        for (int index = 0; index < 100; index++) {
            var observation = gateway.lookup(originalKey);
            if (observation instanceof PaymentLookupObservation.Unavailable unavailable
                && unavailable.kind() == PaymentLookupObservation.FailureKind.RATE_LIMITED) {
                rejected++;
            }
        }
        assertThat(rejected).isPositive();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(reconcile(fixture, "RATE_REJECTED", calls).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertPending(fixture);

        TestPgControl.complete(fixture.providerOrderId());
        long start = System.nanoTime();
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(25))
            .until(() -> System.nanoTime() - start >= Duration.ofMillis(1100).toNanos());
        assertThat(reconcile(fixture, "RECOVERED", calls).getStatusCode()).isEqualTo(HttpStatus.OK);
        int lookups = TestPgControl.stats(fixture.providerOrderId()).lookupRequests();
        assertThat(reconcile(fixture, "TERMINAL_REPLAY", calls).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertConfirmed(fixture, originalKey, lookups);
        System.out.println("PG_LOOKUP_POLICY_BUSINESS " + json.writeValueAsString(Map.of(
            "recordedAt", Instant.now().toString(), "scope", "real HTTP reconcile + real MySQL + separate test-pg",
            "calls", calls, "localRateRejections", rejected, "actualLookups", lookups,
            "ordersConfirmed", 1, "stock", 8, "points", 100000, "createRequests", 1,
            "originalTransactionPreserved", true)));
    }

    private Fixture request() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        when(suffixGenerator.next()).thenReturn(suffix);
        User user = users.save(User.create());
        points.save(Point.create(user.getId(), new PointBalance(100000)));
        Brand brand = brands.save(Brand.create("circuit-" + UUID.randomUUID()));
        Product product = Product.create(brand.getId(), "Air Max", 3500);
        product.changeStockTo(10);
        Product saved = products.save(product);
        Order order = orders.save(Order.create(user.getId(), List.of(OrderItem.create(saved.getId(), "Air Max", 3500, 2))));
        String providerOrderId = "LP-ORD-%010d-%s".formatted(order.getId(), suffix);
        TestPgControl.scenario(providerOrderId, "{\"scenario\":\"SKIP_CALLBACK\"}");
        requests.requestPayment(user.getId(), order.getId(), UUID.randomUUID().toString(), "TEST", "0000-0000-0000-0000");
        return new Fixture(user.getId(), order.getId(), saved.getId(), providerOrderId);
    }

    private ResponseEntity<String> reconcile(Fixture fixture, String phase, List<Map<String, Object>> calls) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", fixture.userId().toString());
        String before = breaker.getState().name();
        long started = System.nanoTime();
        ResponseEntity<String> response = rest.exchange("/api/v1/orders/" + fixture.orderId() + "/payment/reconcile",
            HttpMethod.POST, new HttpEntity<>(headers), String.class);
        calls.add(Map.of("phase", phase, "stateBefore", before, "stateAfter", breaker.getState().name(),
            "httpStatus", response.getStatusCode().value(),
            "elapsedMillis", Math.round((System.nanoTime() - started) / 1000.0) / 1000.0));
        return response;
    }

    private void assertPending(Fixture fixture) {
        assertThat(orders.findById(fixture.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(attempts.findByOrderId(fixture.orderId()).orElseThrow().getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertBalances(fixture);
        assertThat(products.sumPendingReservationQuantity(fixture.productId())).isEqualTo(BigInteger.valueOf(2));
    }

    private void assertConfirmed(Fixture fixture, String originalKey, int expectedLookups) {
        assertThat(orders.findById(fixture.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        var attempt = attempts.findByOrderId(fixture.orderId()).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(attempt.getTransactionKey()).isEqualTo(originalKey);
        assertThat(TestPgControl.stats(fixture.providerOrderId()).lookupRequests()).isEqualTo(expectedLookups);
        assertThat(TestPgControl.stats(fixture.providerOrderId()).createRequests()).isEqualTo(1);
        assertThat(TestPgControl.stats(fixture.providerOrderId()).transactions()).isEqualTo(1);
        assertBalances(fixture);
        assertThat(products.sumPendingReservationQuantity(fixture.productId())).isEqualTo(BigInteger.ZERO);
    }

    private void assertBalances(Fixture fixture) {
        assertThat(products.findById(fixture.productId()).orElseThrow().getStock().amount()).isEqualTo(8);
        assertThat(points.findByUserId(fixture.userId()).orElseThrow().getBalance().amount()).isEqualTo(100000);
    }

    private record Fixture(Long userId, Long orderId, Long productId, String providerOrderId) {}
}
