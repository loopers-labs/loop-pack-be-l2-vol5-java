package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.PaymentGateway;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "pg-lookup-experiment"})
class OrderPaymentCircuitBreakerE2ETest {
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

    @DisplayName("회로 차단 중 예약과 조회 불가 응답을 유지하고 회복 뒤 기존 거래를 확정한다.")
    @Test
    void keepsReservationsWhenCircuitBlocksAndConfirmsExistingTransactionsAfterRecovery() throws Exception {
        TestPgControl.lookupDelay(0);
        breaker.reset();
        assertThat(gateway).isInstanceOf(CircuitBreakingPaymentGateway.class);
        List<String> transitions = new CopyOnWriteArrayList<>();
        breaker.getEventPublisher().onStateTransition(event -> transitions.add(event.getStateTransition().name()));
        Fixture first = request();
        String firstKey = attempts.findByOrderId(first.orderId()).orElseThrow().getTransactionKey();
        List<Map<String, Object>> calls = new ArrayList<>();
        TestPgControl.lookupDelay(1500);
        for (int index = 0; index < 5; index++) {
            assertThat(reconcile(first, "FAULT", calls).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        for (int index = 0; index < 3; index++) {
            ResponseEntity<String> blocked = reconcile(first, "BLOCKED", calls);
            assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(json.readTree(blocked.getBody()).path("data").path("lookupUnavailable").booleanValue()).isTrue();
        }
        assertThat(TestPgControl.stats(first.providerOrderId()).lookupRequests()).isEqualTo(5);
        assertPending(first);

        // 같은 조회 회로는 주문 간 공유한다. OPEN이 생성 POST를 차단하거나 재시도하게 만들지는 않는다.
        Fixture second = request();
        String secondKey = attempts.findByOrderId(second.orderId()).orElseThrow().getTransactionKey();
        assertPending(second);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        TestPgControl.complete(first.providerOrderId());
        TestPgControl.complete(second.providerOrderId());
        TestPgControl.lookupDelay(0);
        long waitStarted = System.nanoTime();
        await().pollInterval(Duration.ofMillis(25)).atMost(Duration.ofSeconds(4))
            .until(() -> System.nanoTime() - waitStarted >= Duration.ofSeconds(2).toNanos());

        assertThat(reconcile(first, "RECOVERED_PROBE", calls).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(reconcile(second, "RECOVERED_PROBE", calls).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(reconcile(first, "TERMINAL_REPLAY", calls).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reconcile(second, "TERMINAL_REPLAY", calls).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertConfirmed(first, firstKey, 6);
        assertConfirmed(second, secondKey, 1);
        assertThat(transitions).containsExactly("CLOSED_TO_OPEN", "OPEN_TO_HALF_OPEN", "HALF_OPEN_TO_CLOSED");

        System.out.println("PG_LOOKUP_BUSINESS " + json.writeValueAsString(Map.of(
            "recordedAt", Instant.now().toString(), "scope", "real HTTP reconcile + real MySQL + separate test-pg container",
            "transitions", transitions, "calls", calls, "firstPgLookupRequests", 6, "secondPgLookupRequests", 1,
            "ordersConfirmed", 2, "stockPerProduct", 8, "pointsPerUser", 100000,
            "creationRequestsPerOrder", 1)));
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
    }

    private void assertBalances(Fixture fixture) {
        assertThat(products.findById(fixture.productId()).orElseThrow().getStock().amount()).isEqualTo(8);
        assertThat(points.findByUserId(fixture.userId()).orElseThrow().getBalance().amount()).isEqualTo(100000);
    }

    private record Fixture(Long userId, Long orderId, Long productId, String providerOrderId) {}
}
