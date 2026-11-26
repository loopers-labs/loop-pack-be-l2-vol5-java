package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.brand.BrandFacade;
import com.loopers.application.order.OrderEventRelay;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.ProductService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.kafka.TopicRecords;
import com.loopers.support.pg.PgFixture;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "management.server.port=0",
        "payment.unresolved-metrics.enabled=true",
        "payment.unresolved-metrics.interval=200ms"
    })
@AutoConfigureObservability(tracing = false)
@ExtendWith(OutputCaptureExtension.class)
class CheckoutIncidentTest {

    private static final String ORDERS = "/api/v1/orders";
    private static final Long USER = 1L;
    private static final int STOCK = 25;
    private static final String CARD_NUMBER = "0000-0000-0000-0000";
    private static final PgFixture PG = PgFixture.shared();
    private static final String TOPIC = KafkaTestContainersConfig.ORDER_EVENTS;
    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderResponse>> ORDER =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.ReconcileResponse>> RECONCILE =
        new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final OrderEventRelay relay;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final ProductService productService;
    private final PaymentRepository paymentRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DatabaseCleanUp databaseCleanUp;

    @LocalManagementPort
    private int managementPort;

    private Long coat;

    @Autowired
    CheckoutIncidentTest(TestRestTemplate testRestTemplate, OrderEventRelay relay,
                         BrandFacade brandFacade, ProductFacade productFacade,
                         ProductService productService, PaymentRepository paymentRepository,
                         JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, DatabaseCleanUp databaseCleanUp) {
        this.testRestTemplate = testRestTemplate;
        this.relay = relay;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.productService = productService;
        this.paymentRepository = paymentRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        PG.reset();
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        coat = productFacade.register(brandId, "코트", Price.of(3_000)).getId();
        productFacade.adjustStock(coat, Quantity.of(STOCK));
    }

    @AfterEach
    void tearDown() {
        PG.keepCreateResponses();
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("PG 는 성공했는데 응답이 timeout 되거나 끊겨 잃은 세 주문은 UNKNOWN 으로 남고, 같은 세 주문을 reconcile 하면 PG 생성 호출을 더 보내지 않고 확정된다")
    void lostResponsesStayUnknownUntilTheSameOrdersAreReconciled(CapturedOutput output) {
        IncidentEvidence evidence = IncidentEvidence.start();
        String[] phase = {"baseline"};
        try {
            runIncident(output, evidence, phase);
        } catch (RuntimeException | Error failure) {
            evidence.recordFailure(phase[0], failure);
            throw failure;
        }
    }

    private void runIncident(CapturedOutput output, IncidentEvidence evidence, String[] phase) {
        assertThat(PG.willDropNextCreateResponse()).isFalse();
        List<Long> baseline = placeOrders(10);
        baseline.forEach(this::confirm);
        succeedAtPg(baseline);
        baseline.forEach(this::reconcile);

        assertThat(withPaymentStatus(baseline, PaymentStatus.SUCCESS)).hasSize(10);
        assertThat(withPaymentStatus(baseline, PaymentStatus.UNKNOWN)).isEmpty();
        assertThat(confirmedOrders(baseline)).hasSize(10);
        assertThat(pgSuccessKeys(baseline)).hasSize(10);
        assertThat(PG.transactionCount()).isEqualTo(10);
        assertThat(PG.createCount()).isEqualTo(10);
        assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(STOCK - 10));
        assertThat(health()).isEqualTo("UP");
        assertThat(unresolvedUnknownSettlesAt(0)).isEqualTo(0);
        evidence.record("baseline", facts(baseline, List.of(), output.getOut()));
        evidence.recordRaw("baseline-prometheus.txt", scrape());

        phase[0] = "fault";
        int faultStart = output.getOut().length();
        List<Long> fault = placeOrders(10);
        List<Long> timedOut = List.of(fault.get(2), fault.get(8));
        List<Long> disconnected = List.of(fault.get(5));
        List<Long> lost = fault.stream()
            .filter(orderId -> timedOut.contains(orderId) || disconnected.contains(orderId)).toList();
        List<Long> answered = fault.stream().filter(orderId -> !lost.contains(orderId)).toList();
        try {
            for (Long orderId : fault) {
                if (timedOut.contains(orderId)) {
                    confirmWhilePgWithholdsTheResponse(orderId);
                } else if (disconnected.contains(orderId)) {
                    PG.dropNextCreateResponse();
                    confirm(orderId);
                } else {
                    confirm(orderId);
                }
            }
        } finally {
            PG.keepCreateResponses();
        }
        succeedAtPg(fault);
        answered.forEach(this::reconcile);

        List<String> faultKeys = pgSuccessKeys(fault);
        assertThat(withPaymentStatus(fault, PaymentStatus.SUCCESS)).containsExactlyElementsOf(answered);
        assertThat(withPaymentStatus(fault, PaymentStatus.UNKNOWN)).containsExactlyElementsOf(lost);
        assertThat(confirmedOrders(fault)).containsExactlyElementsOf(answered);
        assertThat(faultKeys).hasSize(10);
        assertThat(PG.transactionCount()).isEqualTo(20);
        assertThat(PG.createCount()).isEqualTo(20);
        assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(STOCK - 20));

        assertThat(health()).isEqualTo("UP");
        assertThat(unresolvedUnknownSettlesAt(3)).isEqualTo(3);
        String faultLog = output.getOut().substring(faultStart);
        assertThat(timedOut).allSatisfy(orderId -> assertThat(linesWith(faultLog, providerOrderIdOf(orderId)))
            .singleElement().asString().contains("cause=timeout"));
        assertThat(disconnected).allSatisfy(orderId -> assertThat(linesWith(faultLog, providerOrderIdOf(orderId)))
            .singleElement().asString().contains("cause=connection_lost"));
        assertThat(lost).allSatisfy(orderId ->
            assertThat(linesWith(faultLog, "orderId=" + orderId + ",", "result=unknown")).hasSize(1));
        assertThat(answered).allSatisfy(orderId -> {
            assertThat(linesWith(faultLog, providerOrderIdOf(orderId))).isEmpty();
            assertThat(linesWith(faultLog, "orderId=" + orderId + ",", "result=")).isEmpty();
        });
        assertThat(linesWith(faultLog, "error=")).isEmpty();
        assertThat(linesWith(faultLog, "from=UNKNOWN")).isEmpty();
        evidence.record("fault", facts(baseline, fault, faultLog));
        evidence.recordRaw("fault-prometheus.txt", scrape());
        String faultRecordHash = evidence.sha256("fault.json");

        phase[0] = "recovery";
        assertThat(PG.willDropNextCreateResponse()).isFalse();
        int recoveryStart = output.getOut().length();
        lost.forEach(this::reconcile);

        assertThat(withPaymentStatus(fault, PaymentStatus.SUCCESS)).containsExactlyElementsOf(fault);
        assertThat(withPaymentStatus(fault, PaymentStatus.UNKNOWN)).isEmpty();
        assertThat(confirmedOrders(fault)).containsExactlyElementsOf(fault);
        assertThat(pgSuccessKeys(fault)).containsExactlyElementsOf(faultKeys);
        assertThat(PG.transactionCount()).isEqualTo(20);
        assertThat(PG.createCount()).isEqualTo(20);
        assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(STOCK - 20));

        assertThat(health()).isEqualTo("UP");
        assertThat(unresolvedUnknownSettlesAt(0)).isEqualTo(0);

        fault.forEach(this::reconcile);

        assertThat(confirmedOrders(fault)).containsExactlyElementsOf(fault);
        assertThat(fault).allSatisfy(orderId -> assertThat(outboxRows(orderId)).isEqualTo(1));
        String recoveryLog = output.getOut().substring(recoveryStart);
        assertThat(lost).allSatisfy(orderId ->
            assertThat(linesWith(recoveryLog, "orderId=" + orderId + ",", "from="))
                .singleElement().asString().contains("source=reconcile", "from=UNKNOWN", "to=SUCCESS"));
        assertThat(linesWith(recoveryLog, "from=")).hasSize(3);
        assertThat(linesWith(recoveryLog, "error=")).isEmpty();
        Map<String, Object> recovered = facts(baseline, fault, recoveryLog);
        recovered.put("faultRecordSha256", faultRecordHash);
        evidence.record("recovery", recovered);
        evidence.recordRaw("recovery-prometheus.txt", scrape());

        JsonNode faultRecord = evidence.read("fault");
        JsonNode recoveryRecord = evidence.read("recovery");
        assertThat(evidence.sha256("fault.json")).isEqualTo(faultRecordHash);
        assertThat(recoveryRecord.get("faultRecordSha256").asText()).isEqualTo(faultRecordHash);
        assertThat(recorded(faultRecord, "fault", "orderId")).isEqualTo(recorded(recoveryRecord, "fault", "orderId"))
            .containsExactlyElementsOf(fault.stream().map(String::valueOf).toList());
        assertThat(recorded(faultRecord, "fault", "transactionKeys"))
            .isEqualTo(recorded(recoveryRecord, "fault", "transactionKeys"));
        assertThat(recordedWithStatus(faultRecord, "UNKNOWN"))
            .containsExactlyElementsOf(lost.stream().map(String::valueOf).toList());
        assertThat(recordedWithStatus(recoveryRecord, "UNKNOWN")).isEmpty();
        assertThat(recordedWithStatus(recoveryRecord, "SUCCESS")).hasSize(20);
        assertThat(recoveryRecord.get("pgCreateCount").asInt()).isEqualTo(faultRecord.get("pgCreateCount").asInt())
            .isEqualTo(20);
        assertThat(faultRecord.get("unresolvedMetricLines").toString()).contains("status=\\\"unknown\\\"} 3.0");
        assertThat(recoveryRecord.get("boundaryLogLines")).hasSize(3);

        phase[0] = "follow-up";
        List<Long> confirmed = Stream.concat(baseline.stream(), fault.stream()).toList();
        assertThat(outboxStatuses(confirmed)).hasSize(20).containsOnly("PENDING");

        List<ConsumerRecord<String, byte[]>> relayed = TopicRecords.during(TOPIC, () -> relay.relayOnce(100));

        assertThat(relayed.stream().map(ConsumerRecord::key))
            .containsExactlyInAnyOrderElementsOf(confirmed.stream().map(String::valueOf).toList());
        assertThat(relayed).allSatisfy(record -> {
            JsonNode message = json(record.value());
            assertThat(message.get("eventId").asText()).isEqualTo("order-confirmed:v1:" + record.key());
            assertThat(message.get("items")).hasSize(1);
            assertThat(message.get("items").get(0).get("productId").asLong()).isEqualTo(coat);
            assertThat(message.get("items").get(0).get("quantity").asInt()).isEqualTo(1);
        });
        assertThat(outboxStatuses(confirmed)).hasSize(20).containsOnly("PUBLISHED");
        assertThat(TopicRecords.during(TOPIC, () -> relay.relayOnce(100))).isEmpty();
        Map<String, Object> followUp = new LinkedHashMap<>();
        followUp.put("relayedOrderIds", relayed.stream().map(ConsumerRecord::key).sorted().toList());
        followUp.put("relayedEventIds", relayed.stream()
            .map(record -> json(record.value()).get("eventId").asText()).sorted().toList());
        followUp.put("outboxStatuses", outboxStatuses(confirmed));
        followUp.put("notLinkedInThisRun", List.of("purchase_activity", "weekly_ranking"));
        evidence.record("follow-up", followUp);

        assertThat(evidence.contents()).allSatisfy(content -> assertThat(content).doesNotContain(CARD_NUMBER));
        assertThat(PG.createCount()).isEqualTo(20);
        assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(STOCK - 20));
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(USER));
        return headers;
    }

    private List<Long> placeOrders(int count) {
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Object body = Map.of("lines", List.of(Map.of("productId", coat, "quantity", 1)));
            orderIds.add(testRestTemplate.exchange(ORDERS, HttpMethod.POST, new HttpEntity<>(body, headers()), ORDER)
                .getBody().data().id());
        }
        return orderIds;
    }

    private void confirm(Long orderId) {
        testRestTemplate.exchange(ORDERS + "/" + orderId + "/confirm", HttpMethod.POST,
            new HttpEntity<>(Map.of("paymentAttemptKey", "attempt-" + orderId, "cardType", "TEST",
                "cardNo", CARD_NUMBER), headers()), Object.class);
    }

    private void confirmWhilePgWithholdsTheResponse(Long orderId) {
        PgFixture.HeldResponse held = PG.holdNextCreateResponse();
        try {
            confirm(orderId);
        } finally {
            held.release();
        }
    }

    private void reconcile(Long orderId) {
        testRestTemplate.exchange(ORDERS + "/" + orderId + "/payment/reconcile", HttpMethod.POST,
            new HttpEntity<>(null, headers()), RECONCILE);
    }

    private void succeedAtPg(List<Long> orderIds) {
        orderIds.forEach(orderId -> PG.transactionsOf(paymentOf(orderId).getProviderOrderId().value())
            .forEach(transaction -> PG.changeStatus(transaction.transactionKey(), PgFixture.Status.SUCCESS)));
    }

    private List<String> pgSuccessKeys(List<Long> orderIds) {
        return orderIds.stream()
            .flatMap(orderId -> PG.transactionsOf(paymentOf(orderId).getProviderOrderId().value()).stream())
            .filter(transaction -> transaction.status() == PgFixture.Status.SUCCESS)
            .map(PgFixture.Transaction::transactionKey)
            .toList();
    }

    private List<Long> withPaymentStatus(List<Long> orderIds, PaymentStatus status) {
        return orderIds.stream().filter(orderId -> paymentOf(orderId).getStatus() == status).toList();
    }

    private List<Long> confirmedOrders(List<Long> orderIds) {
        return orderIds.stream()
            .filter(orderId -> "CONFIRMED".equals(orderStatus(orderId)))
            .toList();
    }

    private Map<String, Object> facts(List<Long> baseline, List<Long> fault, String log) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("baselineOrderIds", baseline);
        facts.put("faultOrderIds", fault);
        facts.put("orders", Stream.concat(
            baseline.stream().map(orderId -> orderFacts("baseline", orderId)),
            fault.stream().map(orderId -> orderFacts("fault", orderId))).toList());
        facts.put("pgCreateCount", PG.createCount());
        facts.put("pgTransactionCount", PG.transactionCount());
        facts.put("stock", productService.getStock(coat).value());
        facts.put("health", healthBody());
        facts.put("unresolvedMetricLines", scrape().lines()
            .filter(line -> line.startsWith("payment_unresolved{")).toList());
        facts.put("boundaryLogLines", log.lines()
            .filter(line -> Stream.of("cause=", "result=", "from=", "error=").anyMatch(line::contains)).toList());
        return facts;
    }

    private Map<String, Object> orderFacts(String set, Long orderId) {
        Payment payment = paymentOf(orderId);
        List<PgFixture.Transaction> transactions = PG.transactionsOf(payment.getProviderOrderId().value());
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("set", set);
        facts.put("orderId", orderId);
        facts.put("paymentAttemptKey", payment.getAttemptKey().value());
        facts.put("providerOrderId", payment.getProviderOrderId().value());
        facts.put("amount", payment.getAmount().amount());
        facts.put("paymentStatus", payment.getStatus().name());
        facts.put("orderStatus", orderStatus(orderId));
        facts.put("transactionKeys", transactions.stream().map(PgFixture.Transaction::transactionKey).toList());
        facts.put("transactionStatuses", transactions.stream().map(transaction -> transaction.status().name()).toList());
        facts.put("transactionAmounts", transactions.stream().map(PgFixture.Transaction::amount).toList());
        return facts;
    }

    private List<String> recorded(JsonNode record, String set, String field) {
        List<String> values = new ArrayList<>();
        record.get("orders").forEach(order -> {
            if (set.equals(order.get("set").asText())) {
                values.add(order.get(field).isValueNode() ? order.get(field).asText() : order.get(field).toString());
            }
        });
        return values;
    }

    private List<String> recordedWithStatus(JsonNode record, String paymentStatus) {
        List<String> orderIds = new ArrayList<>();
        record.get("orders").forEach(order -> {
            if (paymentStatus.equals(order.get("paymentStatus").asText())) {
                orderIds.add(order.get("orderId").asText());
            }
        });
        return orderIds;
    }

    private String health() {
        try {
            return objectMapper.readTree(healthBody()).get("status").asText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String healthBody() {
        return testRestTemplate.getForObject(management("/actuator/health"), String.class);
    }

    private String scrape() {
        return testRestTemplate.getForObject(management("/actuator/prometheus"), String.class);
    }

    private String orderStatus(Long orderId) {
        return testRestTemplate.exchange(ORDERS + "/" + orderId, HttpMethod.GET, new HttpEntity<>(null, headers()), ORDER)
            .getBody().data().status();
    }

    private int unresolvedUnknownSettlesAt(int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        int observed = unresolvedUnknown();
        while (observed != expected && System.nanoTime() < deadline) {
            pause(Duration.ofMillis(100));
            observed = unresolvedUnknown();
        }
        return observed;
    }

    private int unresolvedUnknown() {
        String scrape = scrape();
        return scrape.lines()
            .filter(line -> line.startsWith("payment_unresolved{"))
            .filter(line -> line.contains("status=\"unknown\""))
            .map(line -> line.substring(line.lastIndexOf(' ') + 1))
            .map(Double::parseDouble)
            .map(Double::intValue)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Prometheus 응답에 payment_unresolved{status=\"unknown\"} 이 없다: "
                + scrape.substring(0, Math.min(scrape.length(), 300))));
    }

    private String management(String path) {
        return "http://127.0.0.1:" + managementPort + path;
    }

    private static void pause(Duration wait) {
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String providerOrderIdOf(Long orderId) {
        return "providerOrderId=" + paymentOf(orderId).getProviderOrderId().value();
    }

    private List<String> linesWith(String log, String... markers) {
        return log.lines()
            .filter(line -> Arrays.stream(markers).allMatch(line::contains))
            .toList();
    }

    private Payment paymentOf(Long orderId) {
        return paymentRepository.findByOrderId(orderId).orElseThrow();
    }

    private List<String> outboxStatuses(List<Long> orderIds) {
        return orderIds.stream()
            .map(orderId -> jdbcTemplate.queryForObject(
                "select status from order_event_outbox where order_id = ?", String.class, orderId))
            .toList();
    }

    private JsonNode json(byte[] payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int outboxRows(Long orderId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from order_event_outbox where order_id = ?", Integer.class, orderId);
    }
}
