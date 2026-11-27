package com.loopers.application.order;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.coupon.CouponFacade;
import com.loopers.application.coupon.CouponInfo;
import com.loopers.application.point.PointFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.payment.ResilientPaymentProvider;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.order.SpringAppProcess;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.testcontainers.KafkaTestBroker;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 10주차 D 실험. 정상 10건(BASELINE) 뒤 새 10건 중 미리 정한 3건만 생성 응답을 잃게 하고(FAULT),
 * 로그·지표·읽기 전용 PG 조회로 진단한 뒤 같은 3건만 reconcile한다(RECOVERY).
 * 이어서 E 실험으로 같은 20건을 Outbox → Kafka → 실제 commerce-streamer → 실제 commerce-batch → 랭킹 API까지 따라간다.
 * 복구 전 r1(17)을 공개하고, 복구 뒤 r2(20)를 새로 공개한다. r1은 바꾸지 않는다.
 * PG와 DB는 단계 사이에 초기화하지 않는다. 단계별 증거는 실행마다 build/incident-evidence/d-lifecycle/ 아래에 새로 만드는 run-으로 시작하는 폴더에 쓰고,
 * 실패하면 같은 폴더의 failure.txt에 단계와 예외를 남긴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0", "commerce.order-events.enabled=true", "commerce.coupon.issue-mode=DB_ONLY"})
@AutoConfigureObservability
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class PaymentIncidentExperimentTest {

    private static final FakePaymentServer PG = startPg();
    /** 전체 health는 DB·Redis 연결까지 본다. 실제 환경처럼 Redis도 띄워야 health가 앱 상태를 나타낸다. */
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0.15"))
        .withExposedPorts(6379);

    static {
        REDIS.start();
    }
    private static final KafkaTestBroker BROKER = new KafkaTestBroker();
    private static final String TOPIC = "order-events-incident-" + UUID.randomUUID();
    private static final String GROUP = "purchase-activity";
    private static final int PARTITIONS = 3;
    private static final Duration CONSUMER_START_DEADLINE = Duration.ofSeconds(120);
    private static final Duration RESULT_DEADLINE = Duration.ofSeconds(30);
    private static final Duration BATCH_DEADLINE = Duration.ofSeconds(180);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static SpringAppProcess streamer;
    private static final Path EVIDENCE_DIR = Path.of("build", "incident-evidence", "d-lifecycle");
    private static final Instant INCIDENT_TIME = Instant.parse("2020-01-08T12:00:00Z");
    @TestBean
    private Clock clock;
    static Clock clock() {
        return Clock.fixed(INCIDENT_TIME, ZoneOffset.UTC);
    }

    private static final int INITIAL_STOCK = 30;
    private static final String GAUGE = "commerce_payment_attempts_unknown";

    @DynamicPropertySource
    static void pgUrl(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", REDIS::getFirstMappedPort);
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", REDIS::getFirstMappedPort);
        registry.add("commerce.order-events.topic", () -> TOPIC);
        registry.add("spring.kafka.bootstrap-servers", BROKER::bootstrapServers);
        registry.add("spring.kafka.admin.properties.bootstrap.servers", BROKER::bootstrapServers);
    }

    @BeforeAll
    static void createTopic() throws Exception {
        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private ApplicationContext context;
    @Autowired private UserRegistrationService registration;
    @Autowired private OrderFacade orderFacade;
    @Autowired private PaymentProvider paymentProvider;
    @Autowired private ResilientPaymentProvider resilientPaymentProvider;
    @Autowired private PointFacade points;
    @Autowired private CouponFacade coupons;
    @Autowired private CouponCampaignJpaRepository campaigns;
    @Autowired private BrandFacade brands;
    @Autowired private ProductFacade products;
    @Autowired private ProductJpaRepository productJpaRepository;
    @Autowired private OrderJpaRepository orderJpaRepository;
    @Autowired private PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @Autowired private OrderOutboxRelay relay;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @MockitoSpyBean private OrderOutboxTransactions outboxTransactions;
    @LocalManagementPort private int managementPort;

    private Long ownerId;
    private Long productId;
    private final List<Long> allOrders = new ArrayList<>();
    private Path evidenceDir;
    private String currentPhase = "setup";
    private final LocalDate periodStart = LocalDate.ofInstant(INCIDENT_TIME, ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

    /** 랭킹·구매 활동 테이블은 truncateAllTables가 지우지 않는다. 같은 DB를 쓰는 다른 테스트와 섞이지 않게 지운다. */
    @AfterEach
    void tearDown() {
        for (Long orderId : allOrders) {
            jdbc.update("DELETE FROM product_purchase_activity WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM processed_order_confirmation WHERE order_id = ?", orderId);
        }
        deleteRanking();
        databaseCleanUp.truncateAllTables();
    }

    @AfterAll
    static void closeResources() throws Exception {
        try {
            if (streamer != null) {
                streamer.close();
            }
        } finally {
            PG.close();
            REDIS.stop();
            BROKER.close();
        }
    }

    @Test
    void selectedFaultsAreDetectedAndRecoveredWithoutNewPayments(CapturedOutput output) throws Exception {
        Files.createDirectories(EVIDENCE_DIR);
        evidenceDir = Files.createTempDirectory(EVIDENCE_DIR, "run-");
        try {
            runIncident(output);
        } catch (Exception | AssertionError failure) {
            write("failure", "[failure] at=%s phase=%s type=%s message=%s".formatted(
                Instant.now(), currentPhase, failure.getClass().getSimpleName(), failure.getMessage()));
            throw failure;
        }
    }

    private void runIncident(CapturedOutput output) throws Exception {
        new ResourceDatabasePopulator(new FileSystemResource("../commerce-batch/src/main/resources/schema/weekly-ranking.sql"))
            .execute(dataSource);
        deleteRanking();
        startConsumer();
        PG.reset();
        resilientPaymentProvider.lookupCircuitBreaker().reset();
        ownerId = registration.register().getId();
        Long otherUserId = registration.register().getId();
        long initialBalance = points.charge(ownerId, 5_000L).balance();
        var campaign = campaigns.save(new CouponCampaignModel("복구 검증", "테스트 쿠폰", true, 1,
            clock.instant().plus(Duration.ofDays(1))));
        CouponInfo issued = coupons.issue(ownerId, campaign.getId()).coupon();
        assertThat(issued.couponId()).isPositive();
        // 랭킹 API가 좋아요 수도 읽으므로, 카운터까지 만드는 정식 경로로 상품을 만든다.
        Long brandId = brands.create("나이키", null).id();
        productId = products.create(brandId, "양말", 1_000L, INITIAL_STOCK).id();
        write("baseline", "[setup] at=%s ownerId=%d otherUserId=%d productId=%d initialStock=%d recoveryScheduler=%s"
            .formatted(Instant.now(), ownerId, otherUserId, productId, INITIAL_STOCK,
                context.containsBean("paymentRecoveryScheduler") ? "present" : "absent"));
        assertThat(context.containsBean("paymentRecoveryScheduler")).isFalse();

        List<Long> selected = new ArrayList<>();
        PG.settleCreatedAs("SUCCESS");
        try {
            currentPhase = "baseline";
            // BASELINE: 보류 없이 10건을 결제하고 callback으로 확정한다.
            int mark = output.getOut().length();
            assertThat(PG.heldCreateOrders()).isEmpty();
            write("baseline", "[holds] at=%s held=%s".formatted(Instant.now(), PG.heldCreateOrders()));
            List<Long> baseline = createOrders(10);
            baseline.forEach(this::confirm);
            for (Long orderId : baseline) {
                assertThat(callback(orderId).getStatus()).isEqualTo(200);
            }
            baseline.forEach(orderId -> assertConfirmed(orderId));
            recordPhase("baseline", baseline, 0, output, mark);

            currentPhase = "fault";
            // FAULT: 새 10건 중 3·6·9번째만 생성 응답을 보류한다. 정상 7건만 callback으로 확정한다.
            mark = output.getOut().length();
            List<Long> fault = createOrders(10);
            selected.addAll(List.of(fault.get(2), fault.get(5), fault.get(8)));
            selected.forEach(orderId -> PG.holdCreateResponseFor(providerOrderId(orderId)));
            write("fault", "[holds] at=%s selectedOrders=%s held=%s".formatted(Instant.now(), selected, PG.heldCreateOrders()));
            assertThat(PG.heldCreateOrders()).containsExactlyInAnyOrderElementsOf(
                selected.stream().map(PaymentIncidentExperimentTest::providerOrderId).toList());
            fault.forEach(this::confirm);
            List<Long> normal = fault.stream().filter(orderId -> !selected.contains(orderId)).toList();
            for (Long orderId : normal) {
                assertThat(callback(orderId).getStatus()).isEqualTo(200);
            }
            normal.forEach(orderId -> assertConfirmed(orderId));
            for (Long orderId : selected) {
                PaymentAttempt attempt = attempt(orderId);
                OrderModel order = order(orderId);
                assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
                assertThat(attempt.getTransactionKey()).isNull();
                assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
                assertThat(order.getReservationStatus()).isEqualTo(ReservationStatus.RESERVED);
            }
            assertThat(PG.transactions()).hasSize(20)
                .allSatisfy(transaction -> assertThat(transaction.path("status").asText()).isEqualTo("SUCCESS"));
            Map<String, String> keysBefore = pgTransactionKeys(fault);
            write("fault", "[pg-keys] at=%s faultSet=%s".formatted(Instant.now(), keysBefore));
            recordPhase("fault", fault, 3, output, mark);

            currentPhase = "diagnosis";
            // 진단: 장애 3건을 주문 번호로 읽기 전용 조회하고 reconcile 근거를 남긴다.
            mark = output.getOut().length();
            for (Long orderId : selected) {
                diagnose(orderId);
            }
            write("fault", "[diagnosis-logs]" + System.lineSeparator() + boundaryLogs(output, mark));

            currentPhase = "ranking-before-recovery";
            // E-1: 복구 전 확정된 17건을 발행·소비하고 r1을 공개한다. 첫 발행은 PUBLISHED 저장 전에 멈춰 다시 보낸다.
            List<Long> confirmedBeforeRecovery = new ArrayList<>(baseline);
            confirmedBeforeRecovery.addAll(normal);
            doThrow(new IllegalStateException("test: stop before saving PUBLISHED"))
                .when(outboxTransactions).markPublished(anyLong());
            assertThatThrownBy(() -> relay.runOnce()).hasMessageContaining("test: stop");
            doCallRealMethod().when(outboxTransactions).markPublished(anyLong());
            int published = relay.runOnce();
            write("ranking", "[relay] at=%s phase=before-recovery firstRun=stopped-before-PUBLISHED secondRunPublished=%d"
                .formatted(Instant.now(), published));
            assertThat(published).isEqualTo(17);
            await("복구 전 17건의 구매 활동", () -> activityRows(confirmedBeforeRecovery) == 17 && consumerLag() == 0);
            recordActivity("before-recovery", confirmedBeforeRecovery);
            String r1 = snapshotId(1);
            runBatch(r1);
            JsonNode rankingR1 = ranking();
            write("ranking", "[ranking] at=%s phase=before-recovery body=%s".formatted(Instant.now(), rankingR1));
            assertThat(rankingR1.path("status").asText()).isEqualTo("PUBLISHED");
            assertThat(rankingR1.path("snapshotId").asText()).isEqualTo(r1);
            assertThat(items(rankingR1)).containsExactly(productId + ":17:1");

            currentPhase = "recovery";
            // RECOVERY: 보류를 끄고 같은 3건만 주문자가 reconcile한다.
            mark = output.getOut().length();
            selected.forEach(orderId -> PG.releaseCreateResponseFor(providerOrderId(orderId)));
            write("recovery", "[holds] at=%s held=%s".formatted(Instant.now(), PG.heldCreateOrders()));
            assertThat(PG.heldCreateOrders()).isEmpty();
            int createsBefore = PG.createCalls();
            int stockBefore = stock();
            assertOwnerBenefits("before-reconcile", initialBalance, issued);
            for (Long orderId : selected) {
                MockHttpServletResponse response = reconcile(ownerId, orderId);
                write("recovery", "[reconcile] at=%s orderId=%d httpStatus=%d body=%s"
                    .formatted(Instant.now(), orderId, response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8)));
                assertThat(response.getStatus()).isEqualTo(200);
            }
            fault.forEach(orderId -> assertConfirmed(orderId));
            Map<String, String> keysAfter = pgTransactionKeys(fault);
            for (Long orderId : fault) {
                assertThat(attempt(orderId).getTransactionKey()).isEqualTo(keysBefore.get(providerOrderId(orderId)));
            }
            assertThat(keysAfter).isEqualTo(keysBefore);
            assertThat(PG.createCalls()).isEqualTo(createsBefore);
            assertThat(stock()).isEqualTo(stockBefore).isEqualTo(INITIAL_STOCK - 20);
            write("recovery", "[recovery-check] at=%s pgCreatesBefore=%d pgCreatesAfter=%d stockBefore=%d stockAfter=%d keysUnchanged=%s"
                .formatted(Instant.now(), createsBefore, PG.createCalls(), stockBefore, stock(), keysAfter.equals(keysBefore)));

            // 반복 호출과 다른 사용자의 reconcile
            Long repeated = selected.getFirst();
            MockHttpServletResponse again = reconcile(ownerId, repeated);
            MockHttpServletResponse callbackAgain = callback(repeated);
            MockHttpServletResponse other = reconcile(otherUserId, selected.get(1));
            write("recovery", "[repeat] at=%s orderId=%d reconcileStatus=%d callbackStatus=%d order=%s pgCreates=%d"
                .formatted(Instant.now(), repeated, again.getStatus(), callbackAgain.getStatus(), order(repeated).getStatus(),
                    PG.createCalls()));
            write("recovery", "[other-user] at=%s userId=%d orderId=%d httpStatus=%d body=%s"
                .formatted(Instant.now(), otherUserId, selected.get(1), other.getStatus(),
                    other.getContentAsString(StandardCharsets.UTF_8)));
            assertThat(again.getStatus()).isEqualTo(200);
            assertThat(callbackAgain.getStatus()).isEqualTo(200);
            assertConfirmed(repeated);
            assertThat(other.getStatus()).isEqualTo(404);
            assertOwnerBenefits("after-reconcile", initialBalance, issued);
            assertThat(PG.createCalls()).isEqualTo(createsBefore);

            List<Long> all = new ArrayList<>(baseline);
            all.addAll(fault);
            Map<String, Integer> expected = all.stream()
                .collect(Collectors.toMap(PaymentIncidentExperimentTest::providerOrderId, orderId -> 1));
            assertThat(PG.createCallsByOrder()).containsExactlyInAnyOrderEntriesOf(expected);
            recordPhase("recovery", fault, 0, output, mark);

            currentPhase = "ranking-after-recovery";
            // E-2: 복구한 3건의 이벤트를 발행·소비한다. E-3: 새 revision r2를 공개하고 r1이 그대로인지 본다.
            int recoveredPublished = relay.runOnce();
            write("ranking", "[relay] at=%s phase=after-recovery published=%d".formatted(Instant.now(), recoveredPublished));
            assertThat(recoveredPublished).isEqualTo(3);
            await("20건의 구매 활동", () -> activityRows(all) == 20 && consumerLag() == 0);
            recordActivity("after-recovery", all);
            List<LocalDate> weeks = jdbc.queryForList(
                    "SELECT DISTINCT confirmed_at FROM product_purchase_activity WHERE order_id IN (" + idList(all) + ")",
                    LocalDateTime.class).stream()
                .map(at -> at.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))).distinct().toList();
            assertThat(weeks).as("실험의 고정 UTC 주에 확정된 활동").containsExactly(periodStart);
            String r2 = snapshotId(2);
            runBatch(r2);
            JsonNode rankingR2 = ranking();
            List<String> r1Entries = entries(r1);
            write("ranking", "[ranking] at=%s phase=after-recovery body=%s".formatted(Instant.now(), rankingR2));
            write("ranking", "[snapshots] at=%s r1=%s r1Entries=%s r1Inputs=%d r2=%s r2Entries=%s r2Inputs=%d"
                .formatted(Instant.now(), r1, r1Entries, inputRows(r1), r2, entries(r2), inputRows(r2)));
            assertThat(rankingR2.path("snapshotId").asText()).isEqualTo(r2);
            assertThat(rankingR2.path("revision").asInt()).isEqualTo(2);
            assertThat(items(rankingR2)).containsExactly(productId + ":20:1");
            assertThat(r1Entries).containsExactly(productId + ":17:1");
            assertThat(inputRows(r1)).isEqualTo(17);
            assertThat(inputRows(r2)).isEqualTo(20);
        } finally {
            selected.forEach(orderId -> PG.releaseCreateResponseFor(providerOrderId(orderId)));
            PG.settleCreatedAs(null);
        }
    }

    /** 주문마다 구매 활동과 소비 완료 기록이 정확히 한 번인지, 그리고 topic에 남은 레코드 수(발행 시도)를 남긴다. */
    private void recordActivity(String phase, List<Long> orders) throws Exception {
        List<Purchase> expected = purchases("SELECT i.order_id, i.product_id, i.quantity, o.confirmed_at "
            + "FROM order_item i JOIN orders o ON o.id = i.order_id WHERE i.order_id", orders);
        List<Purchase> actual = purchases("SELECT order_id, product_id, quantity, confirmed_at "
            + "FROM product_purchase_activity WHERE order_id", orders);
        write("ranking", "[purchases] phase=%s orderItems=%s activities=%s".formatted(phase, expected, actual));
        assertThat(expected).hasSize(orders.size());
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        Map<Long, Integer> completions = new LinkedHashMap<>();
        for (Long orderId : orders) {
            completions.put(orderId, jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_order_confirmation WHERE order_id = ?", Integer.class, orderId));
        }
        write("ranking", "[activity] at=%s phase=%s kafkaRecords=%d consumerLag=%d activityRows=%d completionsByOrder=%s"
            .formatted(Instant.now(), phase, topicRecords(), consumerLag(), actual.size(), completions));
        assertThat(completions.values()).containsOnly(1);
    }

    private record Purchase(long orderId, long productId, int quantity, LocalDateTime confirmedAt) {}

    private List<Purchase> purchases(String select, List<Long> orders) {
        return jdbc.query(select + " IN (" + idList(orders) + ") ORDER BY order_id, product_id",
            (rs, n) -> new Purchase(rs.getLong("order_id"), rs.getLong("product_id"),
                rs.getInt("quantity"), rs.getObject("confirmed_at", LocalDateTime.class)));
    }

    private void assertOwnerBenefits(String phase, long expectedBalance, CouponInfo issued) {
        long balance = points.getBalance(ownerId).balance();
        List<CouponInfo> owned = coupons.getMyCoupons(ownerId);
        write("recovery", "[benefits] at=%s phase=%s points=%d coupons=%s".formatted(Instant.now(), phase, balance, owned));
        assertThat(balance).isEqualTo(expectedBalance);
        assertThat(owned).containsExactly(issued);
    }

    private void runBatch(String snapshotId) throws Exception {
        try (SpringAppProcess batch = SpringAppProcess.batch(SpringAppProcess.Database.fromSystemProperties(),
            "weeklyRanking", List.of("periodStart=" + periodStart, "snapshotId=" + snapshotId))) {
            int exit = batch.waitForExit(BATCH_DEADLINE);
            write("ranking", "[batch] at=%s snapshotId=%s exit=%d".formatted(Instant.now(), snapshotId, exit));
            assertThat(exit).as("batch 종료 코드:\n%s", batch.logTail()).isZero();
        }
    }

    private JsonNode ranking() throws Exception {
        var response = mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", periodStart.toString())
            .param("limit", "10")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private static List<String> items(JsonNode data) {
        List<String> items = new ArrayList<>();
        data.path("items").forEach(item -> items.add(
            item.path("productId").asLong() + ":" + item.path("score").asLong() + ":" + item.path("rank").asInt()));
        return items;
    }

    private List<String> entries(String snapshotId) {
        return jdbc.query("SELECT product_id, score, ranking FROM ranking_entry WHERE snapshot_id = ? ORDER BY ranking",
            (rs, n) -> rs.getLong("product_id") + ":" + rs.getLong("score") + ":" + rs.getInt("ranking"), snapshotId);
    }

    private int inputRows(String snapshotId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ranking_snapshot_input WHERE snapshot_id = ?", Integer.class,
            snapshotId);
    }

    private int activityRows(List<Long> orders) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_purchase_activity WHERE order_id IN (" + idList(orders) + ")",
            Integer.class);
    }

    private static String idList(List<Long> orders) {
        return orders.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private String snapshotId(int revision) {
        return "weekly-" + periodStart.format(DateTimeFormatter.BASIC_ISO_DATE) + "-r" + revision;
    }

    private void deleteRanking() {
        for (String table : List.of("ranking_snapshot_input", "ranking_score_stage", "ranking_entry")) {
            jdbc.update("DELETE FROM " + table + " WHERE snapshot_id LIKE ?", snapshotId(0).replace("-r0", "-r%"));
        }
        jdbc.update("DELETE FROM ranking_snapshot WHERE period_start = ?", periodStart);
        jdbc.update("DELETE FROM ranking_publication WHERE period_start = ?", periodStart);
    }

    private void startConsumer() throws Exception {
        if (streamer == null) {
            streamer = SpringAppProcess.streamer(SpringAppProcess.Database.fromSystemProperties(), BROKER.bootstrapServers(), TOPIC);
        }
        await("실제 consumer의 partition 배정", CONSUMER_START_DEADLINE, () -> {
            try (AdminClient admin = admin()) {
                var group = admin.describeConsumerGroups(List.of(GROUP)).all().get(10, TimeUnit.SECONDS).get(GROUP);
                int assigned = group.members().stream().mapToInt(m -> m.assignment().topicPartitions().size()).sum();
                return group.state() == ConsumerGroupState.STABLE && assigned == PARTITIONS;
            }
        });
    }

    /** topic 끝 offset의 합. 재발행으로 같은 이벤트가 다시 가면 구매 활동 수보다 많아진다. */
    private long topicRecords() throws Exception {
        try (AdminClient admin = admin()) {
            var partitions = admin.describeTopics(List.of(TOPIC)).allTopicNames().get(10, TimeUnit.SECONDS).get(TOPIC)
                .partitions().stream()
                .collect(Collectors.toMap(p -> new TopicPartition(TOPIC, p.partition()),
                    p -> OffsetSpec.latest()));
            return admin.listOffsets(partitions).all().get(10, TimeUnit.SECONDS).values().stream()
                .mapToLong(info -> info.offset()).sum();
        }
    }

    private long consumerLag() throws Exception {
        try (AdminClient admin = admin()) {
            var committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            var partitions = committed.keySet().stream().filter(p -> p.topic().equals(TOPIC))
                .collect(Collectors.toMap(p -> p, p -> OffsetSpec.latest()));
            if (partitions.isEmpty()) {
                return Long.MAX_VALUE;
            }
            var ends = admin.listOffsets(partitions).all().get(10, TimeUnit.SECONDS);
            return partitions.keySet().stream().mapToLong(p -> ends.get(p).offset() - committed.get(p).offset()).sum();
        }
    }

    private void await(String step, Callable<Boolean> condition) throws Exception {
        await(step, RESULT_DEADLINE, condition);
    }

    private void await(String step, Duration limit, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + limit.toNanos();
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            assertThat(streamer.isAlive()).as("streamer 프로세스 종료:\n%s", streamer.logTail()).isTrue();
            try {
                if (condition.call()) {
                    return;
                }
                lastFailure = null;
            } catch (Exception exception) {
                lastFailure = exception;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("기한 초과: %s (마지막 오류: %s)\n%s".formatted(step, lastFailure, streamer.logTail()));
    }

    private static AdminClient admin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER.bootstrapServers()));
    }

    private void diagnose(Long orderId) throws IOException {
        String providerOrderId = providerOrderId(orderId);
        PaymentObservation lookup = paymentProvider.findByOrderId(providerOrderId);
        PaymentAttempt attempt = attempt(orderId);
        OrderModel order = order(orderId);
        List<PaymentTransaction> transactions = lookup.transactions();
        PaymentTransaction only = transactions.size() == 1 ? transactions.getFirst() : null;
        boolean proceed = lookup.kind() == PaymentObservation.Kind.OBSERVED && only != null
            && only.providerOrderId().equals(providerOrderId)
            && only.status() == PaymentTransaction.Status.SUCCESS
            && only.amount() == order.getTotalAmount()
            && attempt.getStatus() == PaymentAttemptStatus.UNKNOWN
            && order.getReservationStatus() == ReservationStatus.RESERVED;
        write("fault", ("[diagnosis] at=%s orderId=%d providerOrderId=%s lookup=%s pgTransactions=%d transaction=%s "
            + "orderAmount=%d attempt=%s reservation=%s decision=%s").formatted(Instant.now(), orderId, providerOrderId,
            lookup.kind(), transactions.size(),
            only == null ? "-" : only.transactionKey() + ":" + only.providerOrderId() + ":" + only.status() + ":" + only.amount(),
            order.getTotalAmount(), attempt.getStatus(), order.getReservationStatus(), proceed ? "PROCEED" : "STOP"));
        assertThat(proceed).as("reconcile 근거 (orderId=%d)", orderId).isTrue();
    }

    /** 단계 끝의 health·지표·DB·PG 호출 수와, 단계 동안 남은 경계 로그를 같은 파일에 쓴다. */
    private void recordPhase(String phase, List<Long> orders, long expectedUnknown, CapturedOutput output, int mark)
        throws Exception {
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            HttpResponse<String> health = management(path);
            write(phase, "[health] at=%s path=%s httpStatus=%d body=%s".formatted(Instant.now(), path, health.statusCode(),
                health.body()));
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("\"status\":\"UP\"");
        }
        long dbUnknown = paymentAttemptJpaRepository.countByStatus(PaymentAttemptStatus.UNKNOWN);
        assertThat(dbUnknown).isEqualTo(expectedUnknown);
        write(phase, "[unknown-count] at=%s count=%d".formatted(Instant.now(), dbUnknown));
        write(phase, awaitGauge(dbUnknown));
        for (Long orderId : orders) {
            PaymentAttempt attempt = attempt(orderId);
            OrderModel order = order(orderId);
            write(phase, "[order] orderId=%d providerOrderId=%s attempt=%s internalTxKey=%s order=%s reservation=%s paidAmount=%s"
                .formatted(orderId, attempt.getProviderOrderId(), attempt.getStatus(), attempt.getTransactionKey(),
                    order.getStatus(), order.getReservationStatus(), order.getPaidAmount()));
        }
        Map<String, Integer> byOrder = new LinkedHashMap<>();
        Map<String, Integer> received = PG.createCallsByOrder();
        orders.forEach(orderId -> byOrder.put(providerOrderId(orderId), received.getOrDefault(providerOrderId(orderId), 0)));
        write(phase, "[pg] at=%s createCalls=%d lookupCalls=%d transactions=%d stock=%d createCallsByOrder=%s"
            .formatted(Instant.now(), PG.createCalls(), PG.lookupCalls(), PG.transactionCount(), stock(), byOrder));
        assertThat(byOrder.values()).containsOnly(1);
        write(phase, "[boundary-logs]" + System.lineSeparator() + boundaryLogs(output, mark));
    }

    private static String boundaryLogs(CapturedOutput output, int mark) {
        return output.getOut().substring(mark).lines()
            .filter(line -> line.contains("PG 요청") || line.contains("PG 응답") || line.contains("결제 결과"))
            .collect(Collectors.joining(System.lineSeparator()));
    }

    /** endpoint 값이 DB 값과 같아질 때까지 다시 읽는다. 기본 집계 간격 5초에 여유를 둔다. */
    private String awaitGauge(long expected) throws Exception {
        long started = System.nanoTime();
        String sample = "";
        do {
            HttpResponse<String> response = management("/actuator/prometheus");
            assertThat(response.statusCode()).isEqualTo(200);
            sample = response.body().lines().filter(line -> line.startsWith(GAUGE + "{") || line.startsWith(GAUGE + " "))
                .findFirst().orElseThrow();
            if (Double.parseDouble(sample.substring(sample.lastIndexOf(' ') + 1)) == expected) {
                return "[unknown-gauge] at=%s observedAfterMs=%d sample=%s"
                    .formatted(Instant.now(), Duration.ofNanos(System.nanoTime() - started).toMillis(), sample);
            }
            Thread.sleep(100);
        } while (System.nanoTime() - started < Duration.ofSeconds(12).toNanos());
        throw new AssertionError("gauge expected " + expected + ", last sample " + sample);
    }

    private HttpResponse<String> management(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path))
            .timeout(Duration.ofSeconds(2)).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private List<Long> createOrders(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(orderFacade.create(ownerId, List.of(new OrderFacade.OrderLine(productId, 1))).id());
            allOrders.add(ids.getLast());
        }
        return ids;
    }

    private void confirm(Long orderId) {
        orderFacade.confirm(ownerId, orderId,
            new OrderFacade.PaymentCommand("attempt-" + orderId, "TEST", "0000-0000-0000-0000"));
    }

    private MockHttpServletResponse callback(Long orderId) {
        PaymentAttempt attempt = attempt(orderId);
        try {
            return mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"providerOrderId\":\"%s\",\"transactionKey\":\"%s\",\"status\":\"SUCCESS\"}"
                        .formatted(attempt.getProviderOrderId(), attempt.getTransactionKey())))
                .andReturn().getResponse();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private MockHttpServletResponse reconcile(Long userId, Long orderId) throws Exception {
        return mvc.perform(post("/api/v1/orders/{orderId}/payment/reconcile", orderId).header("X-USER-ID", userId))
            .andReturn().getResponse();
    }

    private void assertConfirmed(Long orderId) {
        PaymentAttempt attempt = attempt(orderId);
        OrderModel order = order(orderId);
        assertThat(attempt.getStatus()).as("attempt of order %d", orderId).isEqualTo(PaymentAttemptStatus.SUCCESS);
        assertThat(attempt.getTransactionKey()).isNotBlank();
        assertThat(order.getStatus()).as("order %d", orderId).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getReservationStatus()).isEqualTo(ReservationStatus.CONSUMED);
    }

    /** 가짜 PG에 저장된 거래 키. 주문 번호별로 한 건이어야 한다. */
    private Map<String, String> pgTransactionKeys(List<Long> orders) {
        Map<String, String> keys = new LinkedHashMap<>();
        for (Long orderId : orders) {
            String providerOrderId = providerOrderId(orderId);
            List<String> found = PG.transactions().stream()
                .filter(transaction -> transaction.path("orderId").asText().equals(providerOrderId))
                .map(transaction -> transaction.path("transactionKey").asText()).toList();
            assertThat(found).as("PG transactions of %s", providerOrderId).hasSize(1);
            keys.put(providerOrderId, found.getFirst());
        }
        return keys;
    }

    private PaymentAttempt attempt(Long orderId) {
        return paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
    }

    private OrderModel order(Long orderId) {
        return orderJpaRepository.findById(orderId).orElseThrow();
    }

    private int stock() {
        return productJpaRepository.findById(productId).orElseThrow().getStockQuantity();
    }

    private static String providerOrderId(Long orderId) {
        return "LP-ORD-%010d".formatted(orderId);
    }

    private void write(String phase, String line) {
        try {
            Files.writeString(evidenceDir.resolve(phase + ".txt"), line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
