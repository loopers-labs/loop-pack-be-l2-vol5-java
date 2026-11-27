package com.loopers.interfaces.api.payment;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.order.OrderCreateCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.payment.PaymentFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.ProductService;
import com.loopers.support.payment.TestPayments;
import com.loopers.support.pg.PgFixture;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentCallbackApiE2ETest {

    private static final String CALLBACK = "/api/v1/payments/callback";
    private static final PgFixture PG = PgFixture.shared();
    private static final HttpClient CALLBACK_CLIENT = HttpClient.newHttpClient();
    private static final Long USER = 1L;
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

    private final TestPayments testPayments;
    private final PaymentRepository paymentRepository;
    private final OrderFacade orderFacade;
    private final ProductService productService;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final PaymentFacade paymentFacade;
    private final DatabaseCleanUp databaseCleanUp;

    @LocalServerPort
    private int port;

    private Long coat;

    @Autowired
    PaymentCallbackApiE2ETest(
        TestPayments testPayments,
        PaymentRepository paymentRepository,
        OrderFacade orderFacade,
        ProductService productService,
        BrandFacade brandFacade,
        ProductFacade productFacade,
        JdbcTemplate jdbcTemplate,
        DataSource dataSource,
        PaymentFacade paymentFacade,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testPayments = testPayments;
        this.paymentRepository = paymentRepository;
        this.orderFacade = orderFacade;
        this.productService = productService;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.paymentFacade = paymentFacade;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        PG.reset();
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        coat = productFacade.register(brandId, "코트", Price.of(3_000)).getId();
        productFacade.adjustStock(coat, Quantity.of(10));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Order place() {
        return orderFacade.place(new OrderCreateCommand(USER,
            List.of(new OrderCreateCommand.Line(coat, OrderQuantity.of(2)))), NOW);
    }

    private String started(Order order) {
        return testPayments.start(USER, order.getId(), NOW).getTransactionKey().value();
    }

    private int callback(String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + CALLBACK))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            return CALLBACK_CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private int callbackFor(String transactionKey) {
        return callback(PG.callbackBody(transactionKey));
    }

    private Order stored(Order order) {
        return orderFacade.get(USER, order.getId());
    }

    private Payment payment(Order order) {
        return paymentRepository.findByOrderId(order.getId()).orElseThrow();
    }

    private int lookups() {
        return (int) PG.receivedRequests().stream().filter(request -> request.method().equals("GET")).count();
    }

    @DisplayName("SETTLE-002, SETTLE-010: PG 가 SUCCESS 가 된 뒤 온 callback 은 조회로 확인해 확정한다. 고객 식별 없이 받는다")
    @Test
    void confirmsOnSuccess() {
        Order order = place();
        String key = started(order);
        PG.changeStatus(key, PgFixture.Status.SUCCESS);

        int response = callbackFor(key);

        assertAll(
            () -> assertThat(response).isEqualTo(200),
            () -> assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.SUCCESS),
            () -> assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8))
        );
    }

    @DisplayName("SETTLE-002: 본문만 SUCCESS 인 꾸민 callback 은 확정하지 않는다. PG 는 아직 PENDING 이다")
    @Test
    void ignoresForgedBody() {
        Order order = place();
        String key = started(order);

        int response = callback("""
            {"transactionKey":"%s","orderId":"%s","amount":6000,"status":"SUCCESS"}
            """.formatted(key, payment(order).getProviderOrderId().value()));

        assertAll(
            () -> assertThat(response).isEqualTo(200),
            () -> assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.PENDING),
            () -> assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.PENDING)
        );
    }

    @DisplayName("원문 5장, SETTLE-002: 내 주문 번호에 남의 주문의 진짜 거래 키를 붙인 callback 은 그 키를 조회하지 않는다. 주문 번호로만 조회하므로 아무 결제도 바꾸지 않고 불일치도 없다")
    @Test
    void foreignTransactionKeyChangesNothing() {
        Order victim = place();
        started(victim);
        Order other = place();
        String otherKey = started(other);
        PG.changeStatus(otherKey, PgFixture.Status.SUCCESS);

        int response = callback("""
            {"transactionKey":"%s","orderId":"%s","amount":6000,"status":"SUCCESS"}
            """.formatted(otherKey, payment(victim).getProviderOrderId().value()));

        assertAll(
            () -> assertThat(response).isEqualTo(200),
            () -> assertThat(payment(victim).getStatus()).isEqualTo(PaymentStatus.PENDING),
            () -> assertThat(stored(victim).getStatus()).isEqualTo(OrderStatus.PENDING),
            () -> assertThat(payment(other).getStatus()).isEqualTo(PaymentStatus.PENDING),
            () -> assertThat(jdbcTemplate.queryForObject(
                "select count(*) from payment_discrepancy", Integer.class)).isZero(),
            () -> assertThat(PG.receivedRequests()).filteredOn(request -> request.method().equals("GET"))
                .extracting(PgFixture.ReceivedRequest::path).containsOnly("/api/v1/payments")
        );
    }

    @DisplayName("SETTLE-007, SETTLE-009: PG 성공 뒤 반영이 잠금 대기로 실패하면 503 이고 그대로다. 잠금이 풀린 뒤 reconcile 이 같은 거래로 한 번 확정하고 create 는 늘지 않는다")
    @Test
    void recoversAfterInternalSaveFailure() throws Exception {
        Order order = place();
        String key = started(order);
        PG.changeStatus(key, PgFixture.Status.SUCCESS);
        int creates = PG.createCount();

        int response;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM orders WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, order.getId());
                lock.executeQuery().close();
            }
            response = callbackFor(key);
            assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.PENDING);
            holder.rollback();
        }

        Payment reconciled = paymentFacade.reconcile(USER, order.getId(), NOW).payment();
        paymentFacade.reconcile(USER, order.getId(), NOW);

        assertAll(
            () -> assertThat(response).isEqualTo(503),
            () -> assertThat(reconciled.getStatus()).isEqualTo(PaymentStatus.SUCCESS),
            () -> assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8)),
            () -> assertThat(PG.createCount()).isEqualTo(creates)
        );
    }

    @DisplayName("SETTLE-009: 모르는 주문 번호의 callback 은 200 이고 PG 를 조회하지 않는다. 우리가 보낸 적이 없다")
    @Test
    void acceptsUnknownOrder() {
        int before = lookups();

        int response = callback("""
            {"transactionKey":"TEST-TX-9999","orderId":"LP-ORD-0000099999","amount":6000,"status":"SUCCESS"}
            """);

        assertAll(
            () -> assertThat(response).isEqualTo(200),
            () -> assertThat(lookups()).isEqualTo(before)
        );
    }

    @DisplayName("SETTLE-009: 본문을 해석할 수 없으면 400 이다")
    @Test
    void rejectsMalformedBody() {
        assertAll(
            () -> assertThat(callback("{}")).isEqualTo(400),
            () -> assertThat(callback("""
                {"transactionKey":"TEST-TX-0001","orderId":"LP"}
                """)).isEqualTo(400),
            () -> assertThat(callback("not json")).isEqualTo(400)
        );
    }

    @DisplayName("SETTLE-009, PAY-008: 확인 조회가 안 되면 503 이고 아무것도 바꾸지 않는다. PG 가 다시 보낸다")
    @Test
    void unavailableWhenLookupFails() {
        Order order = place();
        String key = started(order);
        PG.changeStatus(key, PgFixture.Status.SUCCESS);
        PG.respondNextLookupWith(500, "{}");

        int response = callbackFor(key);

        assertAll(
            () -> assertThat(response).isEqualTo(503),
            () -> assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.PENDING),
            () -> assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.PENDING)
        );
    }

    @DisplayName("SETTLE-003, SETTLE-005, SETTLE-006: 생성 응답보다 callback 이 먼저 와도 같은 키로 확정되고, 늦은 202 는 아무것도 바꾸지 않는다")
    @Test
    void callbackBeforeCreateResponse() throws Exception {
        Order order = place();
        PgFixture.HeldResponse held = PG.holdNextCreateResponse();
        CompletableFuture<Payment> confirm = CompletableFuture.supplyAsync(() -> testPayments.start(USER, order.getId(), NOW));
        held.awaitStored();
        String key = PG.transactionsOf(payment(order).getProviderOrderId().value()).stream()
            .map(PgFixture.Transaction::transactionKey).max(String::compareTo).orElseThrow();
        PG.changeStatus(key, PgFixture.Status.SUCCESS);

        int response = callbackFor(key);
        held.release();
        Payment returned = confirm.get(10, TimeUnit.SECONDS);

        assertAll(
            () -> assertThat(response).isEqualTo(200),
            () -> assertThat(returned.getStatus()).isEqualTo(PaymentStatus.SUCCESS),
            () -> assertThat(payment(order).getTransactionKey().value()).isEqualTo(key),
            () -> assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8))
        );
    }
}
