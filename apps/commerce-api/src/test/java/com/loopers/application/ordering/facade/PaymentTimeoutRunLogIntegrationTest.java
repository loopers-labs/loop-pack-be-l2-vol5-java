package com.loopers.application.ordering.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.ordering.command.ConfirmOrderCommand;
import com.loopers.application.ordering.query.OrderView;
import com.loopers.application.ordering.result.PaymentCallbackResult;
import com.loopers.application.ordering.usecase.ConfirmOrderUseCase;
import com.loopers.application.pay.gateway.PaymentProviderGateway;
import com.loopers.application.pay.gateway.PaymentProviderGateway.ExpectedPayment;
import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.model.OrderRecordStatus;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.model.PaymentMethod;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentScenario;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.domain.shopping.model.User;
import com.loopers.domain.shopping.repository.UserRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.test.E2ETest;
import com.loopers.support.test.IsolatedTestPg;
import com.loopers.support.test.PgPaymentTestSupport;
import com.loopers.utils.DatabaseCleanUp;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@E2ETest
@Import(OrderConfirmationEffects.Reader.class)
@TestPropertySource(properties = {"logging.config=classpath:logback-payment-lab.xml", "spring.jpa.show-sql=false",
    "payment.recovery.auto-start-enabled=false", "management.server.port=0"})
@ResourceLock(Resources.SYSTEM_OUT)
class PaymentTimeoutRunLogIntegrationTest {
    private static final String RUN_ID = "order-108-" + UUID.randomUUID();
    private static final Path RUN_DIRECTORY = Path.of("build", "week10", "timeout-logs", RUN_ID);
    private static IsolatedTestPg pg;

    @Autowired private ConfirmOrderUseCase confirm;
    @Autowired private UserRepository users;
    @Autowired private PaymentProviderGateway gateway;
    @Autowired private TestRestTemplate commerceClient;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private OrderRepository orders;
    @Autowired private OrderConfirmationEffects.Reader effects;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @Autowired @Qualifier("paymentProviderRestClient") private RestClient pgClient;
    @Autowired private ObjectMapper json;

    @DynamicPropertySource
    static void startPaymentProvider(DynamicPropertyRegistry properties) {
        pg = IsolatedTestPg.start(RUN_DIRECTORY);
        properties.add("payment.lab.run-id", () -> RUN_ID);
        properties.add("pg.client.base-url", pg::baseUrl);
        properties.add("pg.client.callback-url", pg::callbackUrl);
    }

    @AfterAll
    static void stopPaymentProvider() {
        if (pg != null) { pg.close(); }
    }

    @AfterEach
    void cleanDatabase() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @Sql(statements = "ALTER TABLE orders AUTO_INCREMENT = 108")
    @DisplayName("108번 주문의 타임아웃 증거를 저장해 복구하고, 재호출과 늦은 PG 콜백 뒤에도 결제·재고·확정은 한 번만 반영된다")
    void recoversOnceEvenAfterRepeatedRecoveryAndLatePgCallback() throws IOException {
        users.save(User.create(1L));
        Brand brand = brands.save(Brand.create("차 브랜드", null));
        int initialStock = 5;
        Product tea = products.save(Product.create(brand.getId(), "차", null, 1_000L, initialStock));
        Order order = orders.insert(Order.create(1L, List.of(OrderItem.create(tea.getId(), "차", 1_000L, 1))));
        assertThat(order.getId()).isEqualTo(108L);
        var request = PgPaymentTestSupport.command(order);
        // PG의 생성 응답은 유실되고, 승인 콜백은 테스트 수신기에서 복구 재호출 뒤까지 보관한다.
        var scenario = PaymentScenario.resolve(PaymentScenario.Preset.LOST_SUCCESS, null);
        var command = new ConfirmOrderCommand(request.userId(), request.orderId(), request.paymentAttemptKey(), request.card(), scenario);

        Path evidence = diagnoseAndSave(command);
        assertThat(products.findById(tea.getId()).orElseThrow().getStock()).as("미확인 주문에 예약된 차 1개").isEqualTo(initialStock - 1);
        Recovery first = recoverUsingSavedEvidence(command.userId(), evidence);
        verifyReplays(command.userId(), tea.getId(), initialStock, first);
    }

    private Path diagnoseAndSave(ConfirmOrderCommand command) throws IOException {
        Path log = RUN_DIRECTORY.resolve("commerce.log");
        confirmAndCapture(command, log);
        PaymentAttempt persisted = readAttempt(command.orderId());
        assertThat(persisted.getAttemptKey()).isEqualTo(command.paymentAttemptKey());
        assertThat(persisted.getOutcome()).isEqualTo(PaymentAttempt.Outcome.UNKNOWN);
        assertThat(persisted.getTransactionKey()).isNull();
        assertTimeoutLog(log, persisted);

        List<PgPayment> payments = await().alias("PG 주문 " + persisted.getProviderOrderId() + " 승인 완료")
            .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(200)).atMost(Duration.ofSeconds(15))
            .until(() -> lookupSinglePayment(persisted), rows -> rows.getFirst().status() == ProviderPaymentStatus.SUCCESS);
        PaymentAttempt after = readAttempt(command.orderId());
        assertThat(after.getOutcome()).isEqualTo(PaymentAttempt.Outcome.UNKNOWN);
        assertThat(after.getAttemptKey()).isEqualTo(persisted.getAttemptKey());
        assertThat(after.getTransactionKey()).isNull();
        assertThat(after.getVersion()).isEqualTo(persisted.getVersion());
        Order pending = readOrder(command.orderId());
        assertThat(pending.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        var comparison = new Comparison(RUN_ID, Instant.now(), pending.getId(), persisted.getAttemptKey(), persisted.getProviderOrderId(),
            persisted.getAmount(), persisted.getOutcome(), after.getOutcome(), payments.size(), payments, pending.getStatus(), createCounts());
        Path evidence = log.resolveSibling("pg-lookup.json");
        json.writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(), comparison);
        return evidence;
    }

    private Recovery recoverUsingSavedEvidence(long userId, Path evidence) throws IOException {
        Comparison incident = json.readValue(evidence.toFile(), Comparison.class);
        assertThat(incident.runId()).isEqualTo(RUN_ID);
        assertThat(incident.createCounts()).as("복구 전 Commerce 생성 시도와 PG 생성 요청 수신").isEqualTo(new CreateCounts(1, 1));
        PgPayment existing = incident.pgTransactions().getFirst();
        int httpStatus = reconcile(userId, incident.orderId(), existing.transactionKey());
        verify(gateway).lookup(new ExpectedPayment(incident.providerOrderId(), incident.expectedAmount(), null));

        Order order = readOrder(incident.orderId());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        var paid = order.getRecord().orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(OrderRecordStatus.PAID);
        assertThat(paid.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
        assertThat(paid.getAmount()).isEqualTo(existing.amount());
        PaymentAttempt recovered = readAttempt(incident.orderId());
        assertThat(recovered.getAttemptKey()).isEqualTo(incident.attemptKey());
        assertThat(recovered.getProviderOrderId()).isEqualTo(existing.orderId());
        assertThat(recovered.getOutcome()).isEqualTo(PaymentAttempt.Outcome.SUCCESS);
        assertThat(recovered.getTransactionKey()).isEqualTo(existing.transactionKey());
        List<PgPayment> pgAfter = lookupSinglePayment(recovered);
        assertThat(pgAfter).containsExactly(existing);
        CreateCounts after = createCounts();
        assertThat(after).as("복구 후 생성 시도와 수신 누계").isEqualTo(incident.createCounts());

        var proof = new Recovery(RUN_ID, Instant.now(), order.getId(), recovered.getAttemptKey(), recovered.getProviderOrderId(),
            httpStatus, order.getStatus(), paid.getStatus(), recovered.getOutcome(), recovered.getTransactionKey(),
            incident.createCounts(), after, pgAfter);
        json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolveSibling("recovery.json").toFile(), proof);
        return proof;
    }

    private int reconcile(long userId, long orderId, String expectedTransactionKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", Long.toString(userId));
        ResponseEntity<ApiResponse<OrderView>> response = commerceClient.exchange("/api/v1/orders/{id}/payment/reconcile",
            HttpMethod.POST, new HttpEntity<>(null, headers), new ParameterizedTypeReference<>() {}, orderId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        OrderView view = response.getBody().data();
        assertThat(view.orderId()).isEqualTo(orderId);
        assertThat(view.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(view.paymentStatus()).isEqualTo(OrderRecordStatus.PAID);
        assertThat(view.paymentMethod()).isEqualTo(PaymentMethod.PG);
        assertThat(view.paymentAttempt().transactionKey()).isEqualTo(expectedTransactionKey);
        return response.getStatusCode().value();
    }

    private void verifyReplays(long userId, long productId, int initialStock, Recovery first) throws IOException {
        PgPayment payment = first.pgTransactionsAfter().getFirst();
        var firstEffects = effects.read(first.orderId(), productId);
        firstEffects.assertConfirmed(productId, initialStock - 1, payment.amount());
        var baseline = new ReplayState(firstEffects, first.attemptKey(), first.paymentOutcome(), first.transactionKey(),
            first.createsAfter(), first.pgTransactionsAfter());

        int retryStatus = reconcile(userId, first.orderId(), payment.transactionKey());
        ReplayState afterRetry = readReplayState(first.orderId(), productId);
        assertThat(afterRetry).as("복구 API 재호출 직후").isEqualTo(baseline);

        var callback = pg.awaitCallback(payment.orderId());
        assertThat(callback).isEqualTo(new IsolatedTestPg.HeldCallback(payment.orderId(), payment.transactionKey(), payment.status().name()));
        var expected = new ExpectedPayment(payment.orderId(), payment.amount(), payment.transactionKey());
        var lookups = ArgumentCaptor.forClass(ExpectedPayment.class);
        verify(gateway, atLeastOnce()).lookup(lookups.capture());
        int previousLookups = Collections.frequency(lookups.getAllValues(), expected);
        ResponseEntity<ApiResponse<PaymentCallbackResult>> response = commerceClient.exchange("/api/v1/payments/callback",
            HttpMethod.POST, new HttpEntity<>(callback), new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(new PaymentCallbackResult(true, false));
        verify(gateway, times(previousLookups + 1)).lookup(expected);
        ReplayState afterCallback = readReplayState(first.orderId(), productId);
        assertThat(afterCallback).as("같은 거래의 늦은 PG 콜백 직후").isEqualTo(baseline);

        var evidence = new ReplayEvidence(RUN_ID, first.orderId(), productId, initialStock, callback, retryStatus, response.getStatusCode().value(),
            baseline, afterRetry, afterCallback);
        json.writerWithDefaultPrettyPrinter().writeValue(RUN_DIRECTORY.resolve("replay.json").toFile(), evidence);
    }

    private ReplayState readReplayState(long orderId, long productId) throws IOException {
        PaymentAttempt attempt = readAttempt(orderId);
        return new ReplayState(effects.read(orderId, productId), attempt.getAttemptKey(), attempt.getOutcome(), attempt.getTransactionKey(),
            createCounts(), lookupSinglePayment(attempt));
    }

    private CreateCounts createCounts() throws IOException {
        var creates = ArgumentCaptor.forClass(ExpectedPayment.class);
        verify(gateway, atLeastOnce()).create(creates.capture(), any(), any());
        try (var pgLog = Files.lines(RUN_DIRECTORY.resolve("pg.log"))) {
            long received = pgLog.filter(line -> line.endsWith("operation=PG_CREATE_RECEIVED")).count();
            return new CreateCounts(creates.getAllValues().size(), received);
        }
    }

    private Order readOrder(long orderId) {
        return new TransactionTemplate(transactions).execute(status -> orders.findById(orderId).orElseThrow());
    }

    private void confirmAndCapture(ConfirmOrderCommand command, Path log) throws IOException {
        PrintStream console = System.out;
        try (var file = new PrintStream(Files.newOutputStream(log), true, StandardCharsets.UTF_8)) {
            System.setOut(file);
            try {
                confirm.execute(command);
            } finally {
                System.setOut(console);
            }
        }
    }

    private void assertTimeoutLog(Path log, PaymentAttempt persisted) throws IOException {
        List<String> lines = Files.readAllLines(log);
        var orderId = Pattern.compile("\\borderId=108(?=\\s|$)");
        var evidence = lines.stream().filter(line -> orderId.matcher(line).find())
            .filter(line -> line.contains("operation=PG_CREATE ") || line.contains("operation=DB_OBSERVATION ")).toList();
        assertThat(evidence).as("108번 최초 PG 통신과 DB 관찰 결과 · %s", log).hasSize(2);
        assertThat(evidence.get(0)).contains("operation=PG_CREATE ", "result=TIMEOUT");
        assertThat(evidence.get(1)).contains("operation=DB_OBSERVATION ", "result=COMMITTED", "outcome=UNKNOWN");
        assertThat(evidence).allSatisfy(line -> assertThat(line).contains(
            "runId=" + RUN_ID + " ", "attemptKey=" + persisted.getAttemptKey(), "providerOrderId=" + persisted.getProviderOrderId()));
        assertThat(lines).noneMatch(line -> line.contains("0000-0000-0000-0000") || line.contains("X-USER-ID"));
    }

    private PaymentAttempt readAttempt(long orderId) {
        return new TransactionTemplate(transactions).execute(status -> attempts.findByOrderId(orderId).orElseThrow());
    }

    private List<PgPayment> lookupSinglePayment(PaymentAttempt attempt) {
        List<PgPayment> rows = pgClient.get().uri("/api/v1/payments?orderId={orderId}", attempt.getProviderOrderId())
            .header("X-USER-ID", "135135").retrieve().body(new ParameterizedTypeReference<>() {});
        assertThat(rows).as("PG 주문 %s의 거래 목록", attempt.getProviderOrderId()).singleElement().satisfies(payment -> {
            assertThat(payment.orderId()).isEqualTo(attempt.getProviderOrderId());
            assertThat(payment.amount()).isEqualTo(attempt.getAmount());
            assertThat(payment.transactionKey()).isNotBlank();
            assertThat(payment.status()).isIn(ProviderPaymentStatus.PENDING, ProviderPaymentStatus.SUCCESS);
        });
        return rows;
    }

    private record PgPayment(String transactionKey, String orderId, long amount, ProviderPaymentStatus status) {}

    private record Comparison(String runId, Instant observedAt, long orderId, String attemptKey, String providerOrderId, long expectedAmount,
                              PaymentAttempt.Outcome commerceBefore, PaymentAttempt.Outcome commerceAfter,
                              int pgTransactionCount, List<PgPayment> pgTransactions, OrderStatus orderStatus, CreateCounts createCounts) {}

    private record CreateCounts(long commerceCreateAttempts, long pgCreateRequestsReceived) {}

    private record ReplayState(OrderConfirmationEffects confirmation, String attemptKey, PaymentAttempt.Outcome paymentOutcome,
                               String transactionKey, CreateCounts creates, List<PgPayment> pgTransactions) {}

    private record ReplayEvidence(String runId, long orderId, long productId, int initialStock, IsolatedTestPg.HeldCallback heldPgCallback,
                                  int repeatedRecoveryHttpStatus, int lateCallbackHttpStatus, ReplayState firstRecovery,
                                  ReplayState afterRepeatedRecovery, ReplayState afterLateCallback) {}

    private record Recovery(String runId, Instant observedAt, long orderId, String attemptKey, String providerOrderId,
                            int httpStatus, OrderStatus orderStatus, OrderRecordStatus paymentStatus,
                            PaymentAttempt.Outcome paymentOutcome, String transactionKey, CreateCounts createsBefore,
                            CreateCounts createsAfter, List<PgPayment> pgTransactionsAfter) {}
}
