package com.loopers.week10;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.CommerceApiApplication;
import com.loopers.batch.ranking.WeeklyRankingJobConfig;
import com.loopers.order.application.OrderPaymentTransactionService;
import com.loopers.order.domain.PaymentGateway;
import com.loopers.order.infrastructure.payment.toss.TossPgFixture;
import com.loopers.product.domain.Product;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.Point;
import com.loopers.user.domain.User;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.Instant;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static com.loopers.support.http.ApiHttp.customer;
import static com.loopers.support.http.ApiHttp.success;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Local synthetic PG incident lifecycle: baseline, response loss, evidence, same-order recovery, and ranking. */
@SpringBootTest(
    classes = CommerceApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.batch.job.name=weeklyRankingJob",
        "spring.batch.job.enabled=false",
        "spring.batch.jdbc.initialize-schema=always",
        "spring.application.name=commerce-api",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "demo-kafka.listener.auto-startup=false",
        "purchase-activity.kafka.listener.auto-startup=true",
        "weekly-ranking.batch.chunk-size=2",
        "datasource.redis.connect-timeout=2s",
        "datasource.redis.command-timeout=2s",
        "management.endpoints.web.exposure.include=health,prometheus",
        "management.prometheus.metrics.export.enabled=true",
        "management.server.port=0"
    }
)
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
@Sql("/week9-ranking-schema.sql")
@ExtendWith(OutputCaptureExtension.class)
class PaymentIncidentRecoveryE2eTest {

    private static final String TOPIC = "w10-payment-incident-" + UUID.randomUUID();
    private static final String GROUP_ID = "w10-payment-incident-" + UUID.randomUUID();
    private static final OffsetDateTime PG_APPROVED_AT = OffsetDateTime.parse("2026-11-27T09:00:00+09:00");
    private static final TossPgFixture PG = new TossPgFixture();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OrderPaymentTransactionService paymentTransactions;
    @Autowired private PaymentGateway paymentGateway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JobLauncher jobLauncher;
    @Autowired @Qualifier(WeeklyRankingJobConfig.JOB_NAME) private Job rankingJob;
    @Autowired private KafkaListenerEndpointRegistry listenerRegistry;
    @LocalManagementPort private int managementPort;

    private CommerceFixture fixture;
    private MessageListenerContainer listener;
    private User buyer;
    private Product product;
    private Point point;
    private Path evidenceDirectory;
    private Path faultEvidenceFile;
    private String faultEvidenceHash;
    private int couponCountAtStart;
    private final List<IncidentOrder> baseline = new ArrayList<>();
    private final List<IncidentOrder> fault = new ArrayList<>();

    @DynamicPropertySource
    static void configureRuntime(DynamicPropertyRegistry registry) {
        ensureTopic();
        registry.add("spring.kafka.bootstrap-servers", PaymentIncidentRecoveryE2eTest::bootstrapServers);
        registry.add("purchase-activity.kafka.group-id", () -> GROUP_ID);
        registry.add("purchase-activity.kafka.topic", () -> TOPIC);
        registry.add("order-confirmed.kafka.topic", () -> TOPIC);
        registry.add("payment.toss.base-url", () -> PG.baseUrl().toString());
        registry.add("payment.toss.secret-key", () -> TossPgFixture.SECRET_KEY);
    }

    @BeforeEach
    void prepareFixture() throws Exception {
        fixture = new CommerceFixture(entityManager, transactionManager);
        buyer = fixture.userWithPoint(20_000);
        couponCountAtStart = jdbc.queryForObject("select count(*) from coupon", Integer.class);
        point = entityManager.find(Point.class, jdbc.queryForObject(
            "select id from point where user_id = ?", Long.class, buyer.getId()));
        product = fixture.product(fixture.brand("week10-" + UUID.randomUUID()), "Incident product", 100L, 25);
        evidenceDirectory = Path.of("..", "..", "docs", "week10", "evidence", "run-" + UUID.randomUUID());
        Files.createDirectories(evidenceDirectory);
        listener = listenerRegistry.getListenerContainer("order-confirmed-consumer");
        assertThat(listener).as("purchase activity consumer").isNotNull();
        await(() -> listener.isRunning() && listener.getAssignedPartitions() != null
            && listener.getAssignedPartitions().size() == partitionCount());
        listener.stop();
        assertThat(PG.responseDropEnabled()).isFalse();
    }

    @AfterEach
    void cleanOwnedState() throws Exception {
        PG.setResponseDropEnabled(false, List.of(), PG_APPROVED_AT);
        if (listener != null && listener.isRunning()) listener.stop();
        try (AdminClient admin = AdminClient.create(adminProperties())) {
            admin.deleteConsumerGroups(List.of(GROUP_ID)).all().get(10, TimeUnit.SECONDS);
            admin.deleteTopics(List.of(TOPIC)).all().get(10, TimeUnit.SECONDS);
        }
        if (fixture != null) fixture.truncateRemainingTables();
    }

    @AfterAll
    static void closeSyntheticPg() {
        PG.close();
    }

    @Test
    void preservesEvidenceAndRecoversSameOrdersWithoutAnotherPgCreate(CapturedOutput output) throws Exception {
        // Baseline: ten independently created HTTP orders each finish through the real PG adapter.
        for (int i = 0; i < 10; i++) baseline.add(createOrder("baseline-" + i));
        PG.setResponseDropEnabled(false, requestsFor(baseline), PG_APPROVED_AT);
        for (IncidentOrder order : baseline) confirm(order);
        assertThat(PG.transactions()).hasSize(10);
        assertThat(PG.responseDropEnabled()).isFalse();
        assertPhase("baseline", baseline, 10, 0, 10, 10);
        String healthBaseline = health();
        await(() -> currentMetricValue() == 0.0);
        String metricBaseline = prometheus();
        assertThat(healthBaseline).contains("\"status\":\"UP\"");
        writeEvidence("baseline.json", Map.of(
            "observedAt", Instant.now().toString(), "health", healthBaseline, "prometheus", metricBaseline,
            "orders", orderEvidence(baseline), "transactionCount", PG.transactions().size(),
            "pointBalance", point.balance(), "stockAvailable", stockQuantity()
        ));

        // Create a separate fault cohort. Seven complete normally; three have PG SUCCESS saved before reset.
        for (int i = 0; i < 10; i++) fault.add(createOrder("fault-" + i));
        List<IncidentOrder> lost = fault.subList(0, 3);
        PG.setResponseDropEnabled(false, requestsFor(fault), PG_APPROVED_AT);
        List<PaymentGateway.ApprovalRequest> droppedRequests = requestsFor(lost);
        PG.setResponseDropEnabled(true, droppedRequests, PG_APPROVED_AT);
        assertThat(PG.responseDropEnabled()).isTrue();
        for (IncidentOrder order : fault.subList(3, 10)) confirm(order);
        for (int i = 0; i < lost.size(); i++) createPgSuccessAndLoseResponse(lost.get(i));

        assertPhase("fault", fault, 7, 3, 20, 20);
        String healthFault = health();
        assertThat(healthFault).contains("\"status\":\"UP\"");
        await(() -> currentMetricValue() == 3.0);
        String metricFault = prometheus();
        assertThat(metricFault).contains("commerce_payment_unresolved");
        assertThat(metricValue(metricFault)).isEqualTo(3.0);
        assertThat(metricFault.lines().filter(line -> line.startsWith("commerce_payment_unresolved ")).count())
            .isEqualTo(1);
        for (IncidentOrder order : lost) {
            assertThat(PG.transaction(order.paymentKey())).isNotNull();
            assertThat(PG.transaction(order.paymentKey()).status()).isEqualTo("SUCCESS");
            assertThat(PG.approvalRequestCount(order.paymentKey())).isEqualTo(1);
        }
        List<String> boundaryLogs = output.getOut().lines()
            .filter(line -> line.contains("PG approval boundary"))
            .filter(line -> lost.stream().anyMatch(order -> line.contains(order.attemptKey())))
            .toList();
        assertThat(boundaryLogs).hasSize(3);
        assertThat(PG.responseDropEnabled()).isTrue();
        faultEvidenceFile = writeEvidence("fault.json", Map.of(
            "observedAt", Instant.now().toString(), "health", healthFault, "prometheus", metricFault,
            "orders", orderEvidence(fault), "transactions", lost.stream()
                .map(order -> PG.transaction(order.paymentKey())).toList(),
            "boundaryLogs", boundaryLogs,
            "hypotheses", List.of(
                Map.of("name", "PG 처리 지연", "expected", "권위 조회가 PENDING/미확정",
                    "actual", "fixture 원장에 세 건 모두 SUCCESS 거래 존재",
                    "judgment", "이 통제 실험의 예상 관찰과 다름"),
                Map.of("name", "PG SUCCESS 응답 유실",
                    "expected", "fixture SUCCESS + adapter Unknown + 내부 UNKNOWN",
                    "actual", "세 승인 요청의 응답 reset, adapter Unknown, 주문 UNKNOWN/예약 RESERVED",
                    "judgment", "세 증거가 일치해 원인으로 판정"),
                Map.of("name", "성공 결과 내부 반영 rollback",
                    "expected", "조회 SUCCESS + 적용 시도 + transaction rollback",
                    "actual", "fault 시점에 SUCCESS 적용 시도 없음; UNKNOWN 저장 transaction은 commit",
                    "judgment", "이 통제 실험의 예상 관찰과 다름")
            ),
            "observation", "세 건 모두 SUCCESS 거래 저장, approve 응답 reset, UNKNOWN/RESERVED; 내부 apply rollback 없음"
        ));
        faultEvidenceHash = sha256(faultEvidenceFile);

        // Keep fault state and transaction ledger intact until the response-drop control is released.
        PG.setResponseDropEnabled(false, droppedRequests, PG_APPROVED_AT);
        for (IncidentOrder order : lost) PG.revealTransaction(order.paymentKey());
        for (IncidentOrder order : lost) {
            mockMvc.perform(post("/api/v1/orders/{orderId}/payment/reconcile", order.orderId())
                    .with(customer(buyer)).with(csrf()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.paymentStatus").value("SUCCESS"));
        }
        for (IncidentOrder order : lost) {
            mockMvc.perform(post("/api/v1/orders/{orderId}/payment/reconcile", order.orderId())
                    .with(customer(buyer)).with(csrf()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.paymentStatus").value("SUCCESS"));
        }
        assertPhase("recovery", fault, 10, 0, 20, 20);
        assertThat(PG.responseDropEnabled()).isFalse();
        for (IncidentOrder order : lost) assertThat(PG.approvalRequestCount(order.paymentKey())).isEqualTo(1);
        await(() -> currentMetricValue() == 0.0);
        String healthRecovery = health();
        String metricRecovery = prometheus();
        assertThat(healthRecovery).contains("\"status\":\"UP\"");
        assertThat(metricValue(metricRecovery)).isEqualTo(0.0);
        assertThat(sha256(faultEvidenceFile)).isEqualTo(faultEvidenceHash);
        assertThat(point.balance()).isEqualTo(20_000);
        assertThat(stockQuantity()).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from coupon", Integer.class)).isEqualTo(couponCountAtStart);
        assertThat(totalCreateCount()).isEqualTo(20);

        // The original cohort and baseline each contribute one unit to this week's real ranking input.
        await(() -> publishedOutboxCount() == 20);
        for (IncidentOrder order : concat(baseline, fault)) {
            assertThat(jdbc.queryForObject("select count(*) from order_confirmed_outbox where order_id = ?",
                Integer.class, order.orderId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from order_confirmed_outbox "
                + "where order_id = ? and status = 'PUBLISHED'", Integer.class, order.orderId())).isEqualTo(1);
        }
        List<ConsumerRecord<String, byte[]>> records = readPublishedRecords(20);
        assertThat(records).hasSize(20);
        for (ConsumerRecord<String, byte[]> record : records) {
            var payload = mapper.readTree(record.value());
            assertThat(payload.path("eventId").asText())
                .as(new String(record.value(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("order-confirmed:" + record.key());
            assertThat(payload.path("items").size()).isEqualTo(1);
            assertThat(payload.path("items").get(0).path("quantity").asInt()).isEqualTo(1);
        }
        listener.start();
        await(() -> listener.isRunning() && listener.getAssignedPartitions() != null
            && listener.getAssignedPartitions().size() == partitionCount());
        await(() -> activityCount(baseline, fault) == 20);
        for (IncidentOrder order : concat(baseline, fault)) {
            assertThat(activityRows(order.orderId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select quantity from product_purchase_activity where order_id = ?",
                Integer.class, order.orderId())).isEqualTo(1);
        }
        LocalDate periodStart = rankingWeekStart(fault.get(0).orderId());
        String snapshotId = "w10-incident-" + UUID.randomUUID();
        var execution = jobLauncher.run(rankingJob, new JobParametersBuilder()
            .addString("periodStart", periodStart.toString(), true)
            .addString("snapshotId", snapshotId, true)
            .toJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        String rankingResponse = mockMvc.perform(get("/api/v1/rankings/weekly").param("periodStart", periodStart.toString())
                .param("limit", "10").with(customer(buyer)))
            .andExpect(success(HttpStatus.OK))
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.snapshotId").value(snapshotId))
            .andExpect(jsonPath("$.data.items[0].productId").value(product.getId()))
            .andExpect(jsonPath("$.data.items[0].score").value(20))
            .andExpect(jsonPath("$.data.items[0].rank").value(1))
            .andReturn().getResponse().getContentAsString();
        writeEvidence("recovery.json", Map.ofEntries(
            Map.entry("observedAt", Instant.now().toString()), Map.entry("orders", orderEvidence(fault)),
            Map.entry("health", healthRecovery), Map.entry("prometheus", metricRecovery),
            Map.entry("transactionCount", PG.transactions().size()),
            Map.entry("createCount", totalCreateCount()), Map.entry("activityCount", activityCount(baseline, fault)),
            Map.entry("stockAvailable", stockQuantity()), Map.entry("pointBalance", point.balance()),
            Map.entry("snapshotId", snapshotId), Map.entry("periodStart", periodStart.toString()),
            Map.entry("rankingResponse", mapper.readTree(rankingResponse)),
            Map.entry("faultEvidenceSha256", faultEvidenceHash),
            Map.entry("faultEvidenceUnchanged", sha256(faultEvidenceFile).equals(faultEvidenceHash))
        ));
        assertThat(healthBaseline).contains("\"status\":\"UP\"");
    }

    private IncidentOrder createOrder(String label) throws Exception {
        String response = mockMvc.perform(post("/api/v1/orders").with(customer(buyer)).with(csrf())
                .contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("items", List.of(Map.of(
                    "productId", product.getId(), "quantity", 1))))))
            .andReturn().getResponse().getContentAsString();
        long orderId = mapper.readTree(response).path("data").path("id").asLong();
        assertThat(orderId).isPositive();
        String suffix = UUID.randomUUID().toString();
        return new IncidentOrder(label, orderId, "w10-payment-" + suffix,
            "w10-attempt-" + suffix, 100L);
    }

    private void confirm(IncidentOrder order) throws Exception {
        mockMvc.perform(post("/api/v1/orders/{orderId}/confirm", order.orderId())
                .with(customer(buyer)).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of(
                    "paymentKey", order.paymentKey(), "paymentAttemptKey", order.attemptKey()))))
            .andExpect(success(HttpStatus.OK))
            .andExpect(jsonPath("$.data.payment.status").value("SUCCESS"));
    }

    private List<PaymentGateway.ApprovalRequest> requestsFor(List<IncidentOrder> orders) {
        return orders.stream().map(order -> new PaymentGateway.ApprovalRequest(
            order.paymentKey(), providerOrderId(order.orderId()), order.amount(), order.attemptKey())).toList();
    }

    private void createPgSuccessAndLoseResponse(IncidentOrder order) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        var plan = transaction.execute(status -> paymentTransactions.prepare(
            buyer.getId(), order.orderId(), order.paymentKey(), order.attemptKey()));
        assertThat(plan).isNotNull();
        assertThat(plan.request().providerOrderId()).isEqualTo(providerOrderId(order.orderId()));
        PaymentGateway.ApprovalResult result = paymentGateway.approve(plan.request());
        assertThat(result).isInstanceOf(PaymentGateway.Unknown.class);
        paymentTransactions.recordResult(order.orderId(), result);
        assertThat(PG.transaction(order.paymentKey())).isNotNull();
        assertThat(PG.transaction(order.paymentKey()).amount()).isEqualTo(order.amount());
    }

    private void assertPhase(String phase, List<IncidentOrder> orders, int confirmed, int unknown,
                             int successfulPgTransactions, int totalPgTransactions) {
        List<Long> ids = orders.stream().map(IncidentOrder::orderId).toList();
        assertThat(jdbc.queryForObject("select count(*) from orders where id in (" + placeholders(ids.size())
            + ") and status = ?", Integer.class,
            append(ids.toArray(), com.loopers.order.domain.OrderStatus.CONFIRMED.ordinal())))
            .as(phase + " confirmed").isEqualTo(confirmed);
        assertThat(jdbc.queryForObject("select count(*) from orders o join payments p on p.id = o.payment_id where o.id in ("
            + placeholders(ids.size()) + ") and p.status = 'UNKNOWN'", Integer.class, ids.toArray()))
            .as(phase + " unknown").isEqualTo(unknown);
        assertThat(PG.transactions()).hasSize(totalPgTransactions);
        assertThat(PG.transactions().stream().filter(tx -> "SUCCESS".equals(tx.status()))).hasSize(successfulPgTransactions);
        assertThat(jdbc.queryForObject("select count(*) from orders where id in (" + placeholders(ids.size())
            + ") and stock_reservation_status = 'CONSUMED'", Integer.class, ids.toArray()))
            .isEqualTo(confirmed);
        assertThat(jdbc.queryForObject("select count(*) from orders where id in (" + placeholders(ids.size())
            + ") and stock_reservation_status = 'RESERVED'", Integer.class, ids.toArray()))
            .isEqualTo(unknown);
        assertThat(jdbc.queryForObject("select count(*) from payments p join orders o on o.payment_id = p.id "
            + "where o.id in (" + placeholders(ids.size()) + ") and p.amount = ?", Integer.class,
            append(ids.toArray(), 100L))).isEqualTo(orders.size());
    }

    private String health() throws Exception {
        return getManagement("health");
    }

    private String prometheus() throws Exception {
        return getManagement("prometheus");
    }

    private String getManagement(String endpoint) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
            "http://127.0.0.1:" + managementPort + "/actuator/" + endpoint)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    private double metricValue(String scrape) {
        String line = scrape.lines().filter(value -> value.startsWith("commerce_payment_unresolved "))
            .findFirst().orElseThrow(() -> new AssertionError("unresolved metric missing"));
        return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
    }

    private double currentMetricValue() {
        try {
            return metricValue(prometheus());
        } catch (Exception | AssertionError missing) {
            return Double.NaN;
        }
    }

    private int activityCount(List<IncidentOrder> first, List<IncidentOrder> second) {
        List<IncidentOrder> orders = concat(first, second);
        return jdbc.queryForObject("select count(*) from product_purchase_activity where order_id in ("
            + placeholders(orders.size()) + ")", Integer.class,
            orders.stream().map(IncidentOrder::orderId).toArray());
    }

    private int publishedOutboxCount() {
        return jdbc.queryForObject("select count(*) from order_confirmed_outbox where status = 'PUBLISHED'",
            Integer.class);
    }

    private List<ConsumerRecord<String, byte[]>> readPublishedRecords(int expectedCount) throws Exception {
        Properties properties = new Properties();
        properties.putAll(adminProperties());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "w10-observer-" + UUID.randomUUID());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (KafkaConsumer<String, byte[]> observer = new KafkaConsumer<>(properties)) {
            List<TopicPartition> partitions = topicPartitions();
            observer.assign(partitions);
            observer.seekToBeginning(partitions);
            List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (records.size() < expectedCount && System.nanoTime() < deadline) {
                observer.poll(Duration.ofMillis(100)).forEach(records::add);
            }
            return records;
        }
    }

    private List<TopicPartition> topicPartitions() throws Exception {
        try (AdminClient admin = AdminClient.create(adminProperties())) {
            var description = admin.describeTopics(List.of(TOPIC)).allTopicNames()
                .get(10, TimeUnit.SECONDS).get(TOPIC);
            return description.partitions().stream()
                .map(partition -> new TopicPartition(TOPIC, partition.partition())).toList();
        }
    }

    private int activityRows(Long orderId) {
        return jdbc.queryForObject("select count(*) from product_purchase_activity where order_id = ?",
            Integer.class, orderId);
    }

    private int stockQuantity() {
        return jdbc.queryForObject("select quantity from stock where product_id = ?", Integer.class, product.getId());
    }

    private int totalCreateCount() {
        return baseline.stream().mapToInt(order -> PG.approvalRequestCount(order.paymentKey())).sum()
            + fault.stream().mapToInt(order -> PG.approvalRequestCount(order.paymentKey())).sum();
    }

    private List<Map<String, Object>> orderEvidence(List<IncidentOrder> orders) {
        return orders.stream().map(order -> jdbc.queryForMap("select o.id as order_id, o.status as order_status, "
            + "o.stock_reservation_status as reservation_status, p.payment_attempt_key, p.provider_order_id, "
            + "p.amount, p.status as payment_status, p.transaction_key from orders o "
            + "join payments p on p.id = o.payment_id where o.id = ?", order.orderId())).toList();
    }

    private Path writeEvidence(String fileName, Object content) throws Exception {
        Path path = evidenceDirectory.resolve(fileName);
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), content);
        return path;
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private LocalDate rankingWeekStart(Long orderId) {
        var confirmed = jdbc.queryForObject("select confirmed_at from orders where id = ?",
            java.sql.Timestamp.class, orderId).toInstant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        return confirmed.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private int partitionCount() {
        try (AdminClient admin = AdminClient.create(adminProperties())) {
            return admin.describeTopics(List.of(TOPIC)).allTopicNames().get(10, TimeUnit.SECONDS)
                .get(TOPIC).partitions().size();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String providerOrderId(Long orderId) {
        return "LP-ORD-%010d".formatted(orderId);
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static Object[] append(Object[] values, Object last) {
        Object[] result = java.util.Arrays.copyOf(values, values.length + 1);
        result[values.length] = last;
        return result;
    }

    private static List<IncidentOrder> concat(List<IncidentOrder> first, List<IncidentOrder> second) {
        List<IncidentOrder> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void ensureTopic() {
        try (AdminClient admin = AdminClient.create(adminProperties())) {
            if (!admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(TOPIC)) {
                admin.createTopics(List.of(new NewTopic(TOPIC, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create isolated Week 10 topic", exception);
        }
    }

    private static Map<String, Object> adminProperties() {
        return Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
    }

    private static String bootstrapServers() {
        return System.getenv().getOrDefault("BOOTSTRAP_SERVERS", "localhost:19092");
    }

    private record IncidentOrder(String label, Long orderId, String paymentKey, String attemptKey, long amount) { }
}
