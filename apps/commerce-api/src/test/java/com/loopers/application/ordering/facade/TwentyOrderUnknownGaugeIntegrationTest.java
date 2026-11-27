package com.loopers.application.ordering.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.ordering.gateway.OrderEventTransport;
import com.loopers.application.ordering.query.OrderView;
import com.loopers.application.ordering.result.PaymentCallbackResult;
import com.loopers.application.ordering.service.WeeklyScenarioConfirmationTime;
import com.loopers.application.pay.gateway.PaymentProviderGateway;
import com.loopers.application.pay.gateway.PaymentProviderGateway.ExpectedPayment;
import com.loopers.application.ranking.query.WeeklyRankingView;
import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.model.StockAdjustment;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.ordering.model.OrderRecordStatus;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.domain.shopping.model.User;
import com.loopers.domain.shopping.repository.UserRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.ordering.dto.OrderApiDto;
import com.loopers.interfaces.api.ranking.dto.WeeklyRankingResponse;
import com.loopers.support.test.E2ETest;
import com.loopers.support.test.IsolatedTestPg;
import com.loopers.support.test.PurchaseActivityLab;
import com.loopers.support.test.WeeklyRankingBatchLab;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

@E2ETest
@AutoConfigureObservability(tracing = false)
@Import({TwentyOrderEvidence.Reader.class, OrderConfirmationEffects.Reader.class, WeeklyScenarioConfirmationTime.class})
@TestPropertySource(properties = {"logging.config=classpath:logback-payment-lab.xml", "spring.jpa.show-sql=false",
    "payment.metrics.refresh-enabled=true", "payment.recovery.auto-start-enabled=false", "management.server.port=0",
    "order-events.relay.enabled=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ResourceLock(Resources.SYSTEM_OUT)
class TwentyOrderUnknownGaugeIntegrationTest {
    private static final long USER_ID = 1L;
    private static final String RUN_ID = "twenty-orders-" + UUID.randomUUID();
    private static final Path RUN = Path.of("build", "week10", "timeout-logs", RUN_ID);
    private static final ParameterizedTypeReference<ApiResponse<OrderView>> ORDER_VIEW = new ParameterizedTypeReference<>() {};
    private static IsolatedTestPg pg;
    private static PurchaseActivityLab purchases;

    @Autowired private TestRestTemplate http;
    @LocalManagementPort private int managementPort;
    @Autowired private UserRepository users;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private PaymentProviderGateway gateway;
    @Autowired private TwentyOrderEvidence.Reader evidence;
    @Autowired private OrderConfirmationEffects.Reader effects;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate commerceDb;
    @Autowired private OrderEventTransport transport;

    @DynamicPropertySource
    static void startLab(DynamicPropertyRegistry properties) {
        pg = IsolatedTestPg.start(RUN);
        try {
            purchases = PurchaseActivityLab.start(RUN);
        } catch (RuntimeException failure) {
            pg.close();
            pg = null;
            throw failure;
        }
        properties.add("datasource.mysql-jpa.main.jdbc-url", purchases::jdbcUrl);
        properties.add("datasource.mysql-jpa.main.username", () -> "application");
        properties.add("datasource.mysql-jpa.main.password", () -> "application");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.kafka.bootstrap-servers", purchases::bootstrapServers);
        properties.add("order-events.topic", purchases::topic);
        properties.add("payment.lab.run-id", () -> RUN_ID);
        properties.add("pg.client.base-url", pg::baseUrl);
        properties.add("pg.client.callback-url", pg::callbackUrl);
    }

    @AfterAll
    static void stopLab() {
        try {
            if (purchases != null) { purchases.close(); }
        } finally {
            if (pg != null) { pg.close(); }
        }
    }

    @Test
    @Sql(statements = "ALTER TABLE orders AUTO_INCREMENT = 91")
    @DisplayName("20건의 주문을 복구·재전달한 뒤 구매 활동을 주간 집계하면 공개 API에서 차 11점 1위, 커피 9점 2위다")
    void recoversThreeTimedOutOrdersOnce() throws Exception {
        users.save(User.create(USER_ID));
        Brand brand = brands.save(Brand.create("음료", null));
        Product coffee = products.save(Product.create(brand.getId(), "커피", null, 1_500, 20));
        Product tea = products.save(Product.create(brand.getId(), "차", null, 1_000, 20));
        var initialResources = TwentyOrderResources.read(commerceDb, USER_ID, tea.getId(), coffee.getId());
        writeJson(RUN.resolve("resources-before.json"), initialResources);

        List<Ticket> normal = new ArrayList<>();
        createOrders(normal, coffee, 6, false);
        createOrders(normal, tea, 4, false);
        submit(normal);
        observe("normal", normal, List.of());

        List<Ticket> incident = new ArrayList<>();
        createOrders(incident, coffee, 3, false);
        createOrders(incident, tea, 4, false);
        createOrders(incident, tea, 3, true);
        List<Ticket> all = Stream.concat(normal.stream(), incident.stream()).toList();
        List<Ticket> selected = incident.stream().filter(Ticket::loseResponse).toList();
        assertThat(all).hasSize(20).extracting(Ticket::orderId).doesNotHaveDuplicates();
        assertThat(selected).hasSize(3).allMatch(ticket -> ticket.productId() == tea.getId());
        writeJson(RUN.resolve("orders.json"), new Manifest(RUN_ID, USER_ID, normal, incident));

        submit(incident);
        Phase before = observe("incident", all, selected);
        Phase saved = json.readValue(RUN.resolve("incident/phase.json").toFile(), Phase.class);
        assertThat(saved).as("첫 복구 전에 저장한 장애 증거").isEqualTo(before);
        var targets = saved.orders().stream().filter(row -> saved.unknownOrderIds().contains(row.orderId())).toList();
        assertThat(targets).extracting(TwentyOrderEvidence::orderId).containsExactly(108L, 109L, 110L);
        assertAll("각 미확인 주문의 기존 PG 승인으로 복구", targets.stream().map(row -> () -> recover(row)));
        Phase recovered = observe("recovered", all, List.of());
        assertThat(recovered.orders()).extracting(TwentyOrderEvidence::pgTransaction)
            .containsExactlyElementsOf(before.orders().stream().map(TwentyOrderEvidence::pgTransaction).toList());
        verifyReplays(recovered, targets, tea.getId());
        var purchasesAfterRedelivery = verifyPurchaseActivities(all, tea.getId());
        var finalResources = TwentyOrderResources.read(commerceDb, USER_ID, tea.getId(), coffee.getId());
        writeJson(RUN.resolve("resources-after.json"), finalResources);
        finalResources.assertAfter(initialResources);
        verifyWeeklyRanking(purchasesAfterRedelivery, tea, coffee);
    }

    private PurchaseActivityEvidence verifyPurchaseActivities(List<Ticket> orders, long teaId) throws Exception {
        var broker = new PurchaseEventBroker(commerceDb, purchases.bootstrapServers(), purchases.topic(), transport);
        var expected = orders.stream().collect(Collectors.toMap(Ticket::orderId, Ticket::productId));
        var originals = broker.awaitPublished(orders.stream().map(Ticket::orderId).toList());
        Path directory = Files.createDirectory(RUN.resolve("purchase-activity"));
        var first = observeConsumption(broker, originals, "CREATED", directory.resolve("first-delivery.json"));
        first.assertMatches(expected, originals, teaId);

        var duplicates = broker.redeliver(originals);
        assertThat(duplicates).extracting(PurchaseEventBroker.Delivery::eventId)
            .containsExactlyInAnyOrderElementsOf(originals.stream().map(PurchaseEventBroker.Delivery::eventId).toList());
        var replay = observeConsumption(broker, duplicates, "ALREADY_PROCESSED", directory.resolve("redelivery.json"));
        assertThat(replay).as("재전달 후 Inbox·구매 활동의 행 ID와 구매 수량").isEqualTo(first);
        return replay;
    }

    private void verifyWeeklyRanking(PurchaseActivityEvidence consumed, Product tea, Product coffee) throws Exception {
        var activities = consumed.activities();
        LocalDate week = activities.getFirst().confirmedAt().atZone(ZoneOffset.UTC).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant start = week.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = week.plusWeeks(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(activities).hasSize(20).allSatisfy(activity ->
            assertThat(activity.confirmedAt()).as("주문 %s의 UTC 집계 주간", activity.orderId()).isAfterOrEqualTo(start).isBefore(end));

        Path directory = Files.createDirectory(RUN.resolve("weekly-ranking"));
        var before = readPublicRanking(week, directory.resolve("before.json"));
        assertThat(before.status()).isEqualTo(WeeklyRankingResponse.Status.NOT_PUBLISHED);
        assertThat(before.items()).isEmpty();
        assertThat(before.snapshotId()).isNull();

        UUID snapshotId = UUID.randomUUID();
        WeeklyRankingBatchLab.run(directory, purchases.jdbcUrl(), week, snapshotId);
        var published = readPublicRanking(week, directory.resolve("published.json"));
        assertThat(published.status()).isEqualTo(WeeklyRankingResponse.Status.PUBLISHED);
        assertThat(published.snapshotId()).isEqualTo(snapshotId);
        assertThat(published.items()).extracting(WeeklyRankingView.Item::rank, WeeklyRankingView.Item::score,
                WeeklyRankingView.Item::productId, WeeklyRankingView.Item::name)
            .containsExactly(tuple(1L, 11L, tea.getId(), "차"), tuple(2L, 9L, coffee.getId(), "커피"));

        WeeklyRankingEvidence evidence = WeeklyRankingEvidence.read(commerceDb, snapshotId);
        writeJson(directory.resolve("batch.json"), evidence);
        evidence.assertCompleted(week, activities);
        assertThat(published.frozenAt()).isEqualTo(evidence.frozenAt());
    }

    private WeeklyRankingResponse readPublicRanking(LocalDate week, Path file) throws IOException {
        var response = http.getForEntity("/api/v1/rankings/weekly?periodStart={week}", byte[].class, week);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        Files.write(file, response.getBody());
        ApiResponse<WeeklyRankingResponse> envelope = json.readValue(response.getBody(), new TypeReference<>() {});
        assertThat(envelope.meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS);
        assertThat(envelope.data().periodStart()).isEqualTo(week);
        assertThat(envelope.data().periodEnd()).isEqualTo(week.plusWeeks(1));
        return envelope.data();
    }

    private PurchaseActivityEvidence observeConsumption(PurchaseEventBroker broker, List<PurchaseEventBroker.Delivery> deliveries,
                                                        String handlingResult, Path file) throws Exception {
        var commits = broker.awaitCommitted(purchases.group(), deliveries);
        var snapshot = PurchaseActivityEvidence.read(purchases);
        writeJson(file, new ConsumptionStage(RUN_ID, purchases.group(), commits, deliveries, snapshot));
        String streamerLog = Files.readString(RUN.resolve("streamer.log"));
        assertThat(deliveries).allSatisfy(sent -> assertThat(streamerLog).contains(
            "OrderConfirmed handled eventId=%s orderId=%d topic=%s partition=%d offset=%d result=%s"
                .formatted(sent.eventId(), sent.orderId(), sent.topic(), sent.partition(), sent.offset(), handlingResult)));
        return snapshot;
    }

    private void createOrders(List<Ticket> tickets, Product product, int count, boolean loseResponse) {
        var request = new OrderApiDto.CreateRequest(List.of(new OrderApiDto.ItemRequest(product.getId(), 1)));
        for (int i = 0; i < count; i++) {
            var response = postOrder("/api/v1/orders", request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            OrderView created = response.getBody().data();
            assertThat(created.totalAmount()).isEqualTo(product.getPrice());
            tickets.add(new Ticket(created.orderId(), product.getId(), product.getName(), created.totalAmount(),
                UUID.randomUUID().toString(), loseResponse));
        }
    }

    private ResponseEntity<ApiResponse<OrderView>> postOrder(String path, Object body, Object... uriVariables) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", Long.toString(USER_ID));
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), ORDER_VIEW, uriVariables);
    }

    private void submit(List<Ticket> tickets) throws IOException {
        var console = System.out;
        try (var file = new PrintStream(Files.newOutputStream(RUN.resolve("commerce.log"), StandardOpenOption.CREATE,
                StandardOpenOption.APPEND), true, StandardCharsets.UTF_8)) {
            System.setOut(file);
            try {
                for (Ticket ticket : tickets) { submit(ticket); }
            } finally {
                System.setOut(console);
            }
        }
    }

    private void submit(Ticket ticket) {
        var options = ticket.loseResponse() ? json.createObjectNode().put("preset", "LOST_SUCCESS") : null;
        var request = new OrderApiDto.ConfirmRequest(ticket.attemptKey(), "TEST", "0000-0000-0000-0000", options);
        var result = postOrder("/api/v1/orders/{id}/confirm", request, ticket.orderId());
        assertThat(result.getStatusCode()).as("최초 결제 요청 · 주문 %s", ticket.orderId()).isEqualTo(HttpStatus.ACCEPTED);
        PaymentAttempt attempt = attempts.findByKey(ticket.attemptKey()).orElseThrow();
        assertThat(attempt.getOrderId()).isEqualTo(ticket.orderId());
        var callback = pg.awaitCallback(attempt.getProviderOrderId());
        assertThat(callback.status()).isEqualTo("SUCCESS");
        if (ticket.loseResponse()) {
            return;
        }
        postCallback(callback);
    }

    private int postCallback(IsolatedTestPg.HeldCallback callback) {
        ResponseEntity<ApiResponse<PaymentCallbackResult>> response = http.exchange("/api/v1/payments/callback",
            HttpMethod.POST, new HttpEntity<>(callback), new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data()).isEqualTo(new PaymentCallbackResult(true, false));
        return response.getStatusCode().value();
    }

    private ReconcileReceipt recover(TwentyOrderEvidence incident) {
        var response = postOrder("/api/v1/orders/{id}/payment/reconcile", null, incident.orderId());
        assertThat(response.getStatusCode()).as("주문 %s 복구", incident.orderId()).isEqualTo(HttpStatus.OK);
        OrderView confirmed = response.getBody().data();
        assertThat(confirmed.orderId()).isEqualTo(incident.orderId());
        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.paymentStatus()).isEqualTo(OrderRecordStatus.PAID);
        assertThat(confirmed.paymentAmount()).isEqualTo(incident.amount());
        assertThat(confirmed.paymentAttempt().transactionKey()).isEqualTo(incident.pgTransaction().transactionKey());
        return new ReconcileReceipt(confirmed.orderId(), response.getStatusCode().value(), confirmed.paymentAttempt().transactionKey());
    }

    private void verifyReplays(Phase recovered, List<TwentyOrderEvidence> targets, long teaId) throws IOException {
        List<Long> orderIds = targets.stream().map(TwentyOrderEvidence::orderId).toList();
        Path directory = Files.createDirectory(RUN.resolve("replay"));
        var baseline = TwentyOrderReplayState.read(recovered.orders(), orderIds, teaId, effects, gateway, RUN.resolve("pg.log"));
        writeJson(directory.resolve("first-recovery.json"), new ReplayStage<>(RUN_ID, Instant.now(), List.of(), baseline));
        baseline.assertRecovered(orderIds, teaId, 9);

        var retries = targets.stream().map(this::recover).toList();
        assertThat(retries).extracting(ReconcileReceipt::orderId).containsExactlyElementsOf(orderIds);
        verifyReplayStage(directory.resolve("repeated-recovery.json"), retries, recovered, orderIds, teaId, baseline);

        var deliveries = targets.stream().map(this::deliverHeldCallback).toList();
        assertThat(deliveries).extracting(CallbackReceipt::orderId).containsExactlyElementsOf(orderIds);
        verifyReplayStage(directory.resolve("late-callbacks.json"), deliveries, recovered, orderIds, teaId, baseline);
    }

    private CallbackReceipt deliverHeldCallback(TwentyOrderEvidence row) {
        var callback = pg.awaitCallback(row.providerOrderId());
        var payment = row.pgTransaction();
        assertThat(callback).isEqualTo(new IsolatedTestPg.HeldCallback(payment.orderId(), payment.transactionKey(), payment.status().name()));
        var expected = new ExpectedPayment(row.providerOrderId(), row.amount(), payment.transactionKey());
        int before = lookupCount(expected);
        int httpStatus = postCallback(callback);
        int after = lookupCount(expected);
        assertThat(after).as("주문 %s의 콜백이 같은 PG 거래를 재조회한다", row.orderId()).isEqualTo(before + 1);
        return new CallbackReceipt(row.orderId(), httpStatus, callback, before, after);
    }

    private int lookupCount(ExpectedPayment expected) {
        var lookups = ArgumentCaptor.forClass(ExpectedPayment.class);
        verify(gateway, atLeastOnce()).lookup(lookups.capture());
        return Collections.frequency(lookups.getAllValues(), expected);
    }

    private void verifyReplayStage(Path file, List<?> responses, Phase recovered, List<Long> orderIds, long teaId,
                                   TwentyOrderReplayState baseline) throws IOException {
        var rows = recovered.orders().stream().map(row -> evidence.read(row.orderId())).toList();
        var state = TwentyOrderReplayState.read(rows, orderIds, teaId, effects, gateway, RUN.resolve("pg.log"));
        writeJson(file, new ReplayStage<>(RUN_ID, Instant.now(), responses, state));
        assertThat(rows).as("전체 20건의 결제 상태와 기존 PG 거래 · %s", file).containsExactlyElementsOf(recovered.orders());
        assertThat(state).as("첫 복구 직후와 비교한 결제·재고·주문별 확정 이벤트 · %s", file).isEqualTo(baseline);
    }

    private Phase observe(String stage, List<Ticket> tickets, List<Ticket> expectedUnknown) throws IOException {
        Path directory = RUN.resolve(stage);
        var sample = UnknownOrderGaugeSample.awaitAfter(http, managementPort, Instant.now(), expectedUnknown.size(), directory);
        List<TwentyOrderEvidence> rows = tickets.stream().map(ticket -> evidence.read(ticket.orderId())).toList();
        List<Long> unknownIds = rows.stream().filter(row -> row.paymentOutcome() == PaymentAttempt.Outcome.UNKNOWN)
            .map(TwentyOrderEvidence::orderId).toList();
        assertThat(unknownIds).as("DB에서 확인한 미확인 주문 ID").containsExactlyInAnyOrderElementsOf(
            expectedUnknown.stream().map(Ticket::orderId).toList());
        assertThat(rows).extracting(row -> row.pgTransaction().transactionKey()).doesNotHaveDuplicates();
        assertThat(rows).zipSatisfy(tickets, (row, ticket) -> {
            assertThat(row.attemptKey()).isEqualTo(ticket.attemptKey());
            assertThat(row.amount()).isEqualTo(ticket.amount());
            assertThat(row.stockItems()).containsExactly(new StockAdjustment.Item(ticket.productId(), 1));
            if (unknownIds.contains(row.orderId())) {
                assertThat(row.responseDeadlineAt()).isBefore(sample.observedAt());
                assertThat(row.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
                assertThat(row.paymentStatus()).isNull();
                assertThat(row.transactionKey()).isNull();
                assertThat(row.stockState()).isEqualTo(StockAdjustment.State.DEDUCTED);
                var held = pg.awaitCallback(row.providerOrderId());
                assertThat(held.transactionKey()).isEqualTo(row.pgTransaction().transactionKey());
            } else {
                assertThat(row.orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
                assertThat(row.paymentOutcome()).isEqualTo(PaymentAttempt.Outcome.SUCCESS);
                assertThat(row.paymentStatus()).isEqualTo(OrderRecordStatus.PAID);
                assertThat(row.stockState()).isEqualTo(StockAdjustment.State.CONSUMED);
            }
        });
        verify(gateway, times(tickets.size())).create(any(), any(), any());
        long pgReceived;
        try (var lines = Files.lines(RUN.resolve("pg.log"))) {
            pgReceived = lines.filter(line -> line.endsWith("operation=PG_CREATE_RECEIVED")).count();
        }
        assertThat(pgReceived).isEqualTo(tickets.size());
        long commerceCreates = assertCreateLogs(rows, tickets);
        var phase = new Phase(RUN_ID, stage, sample, unknownIds, commerceCreates, pgReceived, rows);
        writeJson(directory.resolve("phase.json"), phase);
        return phase;
    }

    private long assertCreateLogs(List<TwentyOrderEvidence> rows, List<Ticket> tickets) throws IOException {
        var lines = Files.readAllLines(RUN.resolve("commerce.log")).stream().filter(line -> line.contains("runId=" + RUN_ID + " ")).toList();
        long creates = lines.stream().filter(line -> line.contains(" operation=PG_CREATE ")).count();
        assertThat(creates).isEqualTo(tickets.size());
        var timeouts = lines.stream().filter(line -> line.contains("operation=PG_CREATE result=TIMEOUT ")).toList();
        var unknowns = lines.stream().filter(line -> line.contains("operation=DB_OBSERVATION result=COMMITTED outcome=UNKNOWN ")).toList();
        var expected = tickets.stream().filter(Ticket::loseResponse).map(Ticket::orderId).toList();
        assertThat(timeouts).hasSize(expected.size());
        assertThat(unknowns).hasSize(expected.size());
        for (var row : rows) {
            if (expected.contains(row.orderId())) {
                String fields = "orderId=" + row.orderId() + " attemptKey=" + row.attemptKey() + " providerOrderId=" + row.providerOrderId();
                assertThat(timeouts).filteredOn(line -> line.endsWith(fields)).hasSize(1);
                assertThat(unknowns).filteredOn(line -> line.endsWith(fields)).hasSize(1);
            }
        }
        return creates;
    }

    private void writeJson(Path file, Object value) throws IOException {
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
    }

    private record Ticket(long orderId, long productId, String product, long amount, String attemptKey, boolean loseResponse) {}
    private record Manifest(String runId, long userId, List<Ticket> normal, List<Ticket> incident) {}
    private record ConsumptionStage(String runId, String consumerGroup, List<PurchaseEventBroker.PartitionCommit> commits,
                                    List<PurchaseEventBroker.Delivery> deliveries, PurchaseActivityEvidence purchaseActivity) {}
    private record ReconcileReceipt(long orderId, int httpStatus, String transactionKey) {}
    private record CallbackReceipt(long orderId, int httpStatus, IsolatedTestPg.HeldCallback callback, int lookupsBefore, int lookupsAfter) {}
    private record ReplayStage<T>(String runId, Instant observedAt, List<T> responses, TwentyOrderReplayState state) {}
    private record Phase(String runId, String stage, UnknownOrderGaugeSample gauge, List<Long> unknownOrderIds,
                         long commerceCreateAttempts, long pgCreateRequestsReceived, List<TwentyOrderEvidence> orders) {}
}
