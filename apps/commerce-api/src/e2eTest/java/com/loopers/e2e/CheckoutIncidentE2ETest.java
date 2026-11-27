package com.loopers.e2e;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.CommerceApiApplication;
import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.payment.PaymentTransactions;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponModel;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PgLookupResult;
import com.loopers.domain.payment.PgTransaction;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.infrastructure.payment.TestPgPaymentGateway;
import com.loopers.infrastructure.payment.UnresolvedPaymentMetrics;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.interfaces.consumer.OrderConfirmedListener;
import com.loopers.support.log.BoundaryLog;
import com.loopers.support.pg.PaymentTestSupport;
import com.loopers.support.pg.TestPgResetExtension;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.pg.TestPgStatus;
import com.loopers.support.pg.TestPgTransaction;
import com.loopers.testcontainers.KafkaBrokerFixture;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.filter.TypeExcludeFilters;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W10 사고 실험: health는 UP인데 PG 성공 응답을 잃어 "결제 확인 중"으로 남은 주문을, 같은 주문·같은 거래로 새 결제 없이 복구한다 (과제 1~7절).
 *
 * <p>한 테스트 메서드 안에서 같은 DB·같은 테스트 PG 거래를 유지하며 BASELINE → FAULT → 증거 보존 → 읽기 전용 진단 → RECOVERY → 후속 결과를 잇는다.
 * 단계 사이에 DB·PG를 초기화하지 않는다. commerce DB 상태는 실제 API·adapter·callback·reconcile 경로로만 바뀌고, 이 테스트의 JDBC는 읽기만 한다.
 *
 * <ul>
 *   <li>BASELINE: 정상 주문 10건. 생성 응답을 받고 PG가 SUCCESS로 끝낸 뒤 callback으로 확정한다.</li>
 *   <li>FAULT: 다른 주문 10건 중 실제 id로 고른 3건은 PG가 거래를 저장한 뒤 생성 응답을 붙잡아 adapter가 timeout(UNKNOWN)을 받는다.
 *       10건 모두 PG에서는 SUCCESS로 끝나고, 7건만 callback이 온다. 3건의 callback은 관찰 시점까지 보내지 않는다(테스트 경계의 보류).</li>
 *   <li>진단: 경쟁 가설을 먼저 적고, commerce 상태를 바꾸지 않는 PG 조회와 경계 로그·DB 상태로 판정한다.</li>
 *   <li>RECOVERY: 응답 유실 통제를 풀고, 같은 3건에 기존 W5 본인 주문 reconcile API를 호출한다. PG create는 늘지 않는다.</li>
 *   <li>후속: 같은 주문들의 Outbox를 실제 relay·Kafka·구매 활동 consumer로 처리하고, 새 snapshot으로 주간 랭킹을 공개해 API 점수를 확인한다.</li>
 * </ul>
 *
 * <p>health·Prometheus는 MockMvc가 아니라 실제 관리 포트({@link LocalManagementPort})로 받는다. 시각은 테스트 clock으로 정하고,
 * 다른 E2E와 겹치지 않는 주(2027-02-01)와 이 테스트만의 topic·group·사용자·상품을 쓴다. 증거는 {@link IncidentEvidence}가 실행마다 새 위치에 남긴다.
 */
@SpringBootTest(classes = CommerceApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "spring.batch.jdbc.initialize-schema=always",
    "management.server.port=0",
    // 테스트 PG가 응답을 붙잡는 최대 시간(5s)보다 짧아야, 거래는 생겼는데 adapter는 timeout을 받는 장면이 된다
    "payment.pg.create-read-timeout=1s"
})
@AutoConfigureMockMvc
// Spring Boot 테스트는 기본으로 Prometheus 등 메트릭 exporter를 꺼서 /actuator/prometheus가 404다. 실제 응답을 받으려고 이 테스트에서만 켠다
@AutoConfigureObservability(tracing = false)
@TypeExcludeFilters({ExcludeStreamerFromScan.class, ExcludeBatchFromScan.class})
@Import({PurchaseActivityConsumerConfig.class, WeeklyRankingJobImportConfig.class, CheckoutIncidentE2ETest.IncidentClockConfig.class})
@ExtendWith(TestPgResetExtension.class)
class CheckoutIncidentE2ETest {

    private static final String TOPIC = KafkaBrokerFixture.createTopic("order-events-w10-incident", 1);
    private static final String GROUP = KafkaBrokerFixture.uniqueName("purchase-activity-w10-incident");
    private static final Duration DEADLINE = Duration.ofSeconds(60);
    private static final Duration METRIC_DEADLINE = Duration.ofSeconds(10);

    private static final String PERIOD_START = "2027-02-01";
    private static final Instant WEEK_START = Instant.parse("2027-02-01T00:00:00Z");
    private static final Instant NEXT_WEEK_START = Instant.parse("2027-02-08T00:00:00Z");
    private static final String SNAPSHOT_ID = "weekly-20270201-r1";

    private static final long USER_ID = 91_001L;
    private static final long OTHER_USER_ID = 91_002L;
    private static final int ORDERS_PER_SET = 10;
    private static final int INITIAL_STOCK = 50;
    private static final long PRICE = 7_000L;
    private static final long POINT_BALANCE = 30_000L;
    // 정렬한 장애 주문 id 목록에서 2·5·8번째. 실행 순서가 아니라 실제 id 목록으로 대상을 고정한다
    private static final List<Integer> FAULT_POSITIONS = List.of(1, 4, 7);

    private static final Pattern UNRESOLVED_LINE = Pattern.compile(
        "^commerce_payment_unresolved\\{[^}]*status=\"UNKNOWN\"[^}]*}\\s+(\\S+)$", Pattern.MULTILINE);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(2)).build();

    @TestConfiguration
    static class IncidentClockConfig {
        @Bean
        @Primary
        SettableClock incidentClock() {
            return new SettableClock(WEEK_START);
        }
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KafkaBrokerFixture::bootstrapServers);
        registry.add("order-event.topic-name", () -> TOPIC);
        registry.add("order-event.consumer-group", () -> GROUP);
        registry.add("order-event.listener-auto-startup", () -> "false");
    }

    @Autowired
    private MockMvc mvc;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private SettableClock clock;

    @Autowired
    private OrderOutboxRelay relay;

    @Autowired
    private PaymentGateway paymentGateway;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job weeklyRankingJob;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private PointJpaRepository pointJpaRepository;

    @Autowired
    private CouponCampaignJpaRepository couponCampaignJpaRepository;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final TestPgServer pg = TestPgServer.shared();
    private final ListAppender<ILoggingEvent> boundaryLogs = new ListAppender<>();
    private final List<Logger> watchedLoggers = new ArrayList<>();

    private Long productId;
    private MessageListenerContainer consumer;
    private List<Long> baselineIds;
    private List<Long> faultIds;
    private List<Long> unknownIds;
    private Map<Long, OrderState> faultSnapshot;

    @BeforeEach
    void watchBoundaryLogs() {
        boundaryLogs.start();
        for (Class<?> type : List.of(PaymentTransactions.class, TestPgPaymentGateway.class)) {
            Logger logger = (Logger) LoggerFactory.getLogger(type);
            logger.addAppender(boundaryLogs);
            watchedLoggers.add(logger);
        }
    }

    @AfterEach
    void releaseOwnResources() {
        // 이 실행이 건 통제와 자신이 시작한 consumer만 정리한다. 거래·DB·증거는 초기화하지 않는다
        pg.releaseCreateHolds();
        listenerRegistry.getListenerContainer(OrderConfirmedListener.LISTENER_ID).stop();
        watchedLoggers.forEach(logger -> logger.detachAppender(boundaryLogs));
        boundaryLogs.stop();
    }

    @DisplayName("health가 UP인 채 PG 성공 응답을 잃은 3건을, 같은 DB·거래 상태에서 증거로 진단하고 create 없이 reconcile로 복구한 뒤 "
        + "구매 활동·주간 랭킹까지 이어진 것을 확인한다.")
    @Test
    void recoversLostPgResponses_withoutNewPayment() throws Exception {
        IncidentEvidence run = IncidentEvidence.newRun();
        try {
            run.stage("setup");
            prepareResources();

            run.stage("baseline");
            baseline(run);

            run.stage("fault");
            fault(run);

            run.stage("diagnosis");
            diagnose(run);

            run.stage("recovery");
            recover(run);

            run.stage("follow-up");
            followUp(run);
        } catch (Throwable failure) {
            run.recordFailure(failure);
            throw failure;
        } finally {
            pg.releaseCreateHolds();
        }
    }

    // ---- 0. 자원 ----

    private void prepareResources() {
        BrandModel brand = brandJpaRepository.save(new BrandModel("w10-incident-brand"));
        ProductModel product = productJpaRepository.save(new ProductModel(brand.getId(), "w10-incident-item", PRICE, INITIAL_STOCK));
        productId = product.getId();
        // 포인트와 쿠폰은 PG 결제와 무관하다. 복구가 이 값들을 바꾸지 않는지 보려고 시작값을 둔다
        PointModel point = new PointModel(USER_ID);
        point.charge(POINT_BALANCE);
        pointJpaRepository.save(point);
        CouponCampaignModel campaign = couponCampaignJpaRepository.save(new CouponCampaignModel("w10-incident-campaign", 10, "1,000원 할인"));
        couponJpaRepository.save(new CouponModel(campaign.getId(), USER_ID, "1,000원 할인", ZonedDateTime.now()));

        consumer = listenerRegistry.getListenerContainer(OrderConfirmedListener.LISTENER_ID);
        consumer.start();
        E2eSupport.awaitAssigned(consumer, DEADLINE);
    }

    // ---- 1. BASELINE ----

    private void baseline(IncidentEvidence run) throws Exception {
        clock.set(WEEK_START.plus(Duration.ofDays(1)));
        assertThat(pg.isCreateHoldActive()).as("baseline에서는 응답 유실 통제가 꺼져 있어야 한다").isFalse();

        baselineIds = new ArrayList<>();
        for (int i = 0; i < ORDERS_PER_SET; i++) {
            Long orderId = E2eSupport.createOrder(mvc, USER_ID, new TreeMap<>(Map.of(productId, 1)));
            E2eSupport.confirm(mvc, USER_ID, orderId).andExpect(status().isAccepted());
            PaymentTestSupport.completeAt(pg, orderId, TestPgStatus.SUCCESS);
            E2eSupport.callbackOf(mvc, PaymentTestSupport.onlyTransactionOf(pg, orderId)).andExpect(status().isOk());
            baselineIds.add(orderId);
        }

        Map<Long, OrderState> states = probe(baselineIds);
        Totals totals = Totals.of(states.values());
        assertThat(totals).isEqualTo(new Totals(10, 0, 10));
        assertThat(states.values()).allSatisfy(state -> {
            assertThat(state.reservation()).isEqualTo("CONSUMED");
            assertThat(state.pgCreates()).isEqualTo(1);
            assertThat(state.outboxRows()).isEqualTo(1);
        });
        assertThat(pg.transactionCount()).isEqualTo(10);
        assertThat(pg.createCount()).isEqualTo(10);
        assertThat(pg.duplicateCreates()).isEmpty();
        assertThat(stock()).isEqualTo(INITIAL_STOCK - 10);

        String health = actuator("/actuator/health");
        assertThat(healthStatus(health)).isEqualTo("UP");
        String prometheus = awaitUnresolved(0);

        run.writeText("baseline.health.json", health);
        run.writeText("baseline.prometheus.txt", prometheus);
        run.writeJson("baseline.json", stageRecord("BASELINE", baselineIds, states, totals, health, prometheus));
    }

    // ---- 2. FAULT ----

    private void fault(IncidentEvidence run) throws Exception {
        clock.set(WEEK_START.plus(Duration.ofDays(2)));

        // 주문을 먼저 만들고, 정렬한 실제 id 목록에서 장애 대상을 고른 뒤 그 주문 번호만 응답을 붙잡는다
        faultIds = new ArrayList<>();
        for (int i = 0; i < ORDERS_PER_SET; i++) {
            faultIds.add(E2eSupport.createOrder(mvc, USER_ID, new TreeMap<>(Map.of(productId, 1))));
        }
        List<Long> sorted = faultIds.stream().sorted().toList();
        unknownIds = FAULT_POSITIONS.stream().map(sorted::get).toList();
        pg.holdCreateResponsesFor(unknownIds.stream().map(ProviderOrderId::from).collect(Collectors.toSet()));

        for (Long orderId : faultIds) {
            E2eSupport.confirm(mvc, USER_ID, orderId).andExpect(status().isAccepted());
        }
        // PG에서는 10건 모두 성공으로 끝난다. 결과 알림은 장애 대상이 아닌 7건만 보낸다
        for (Long orderId : faultIds) {
            PaymentTestSupport.completeAt(pg, orderId, TestPgStatus.SUCCESS);
        }
        for (Long orderId : faultIds) {
            if (!unknownIds.contains(orderId)) {
                E2eSupport.callbackOf(mvc, PaymentTestSupport.onlyTransactionOf(pg, orderId)).andExpect(status().isOk());
            }
        }

        faultSnapshot = probe(faultIds);
        Totals totals = Totals.of(faultSnapshot.values());
        assertThat(totals).isEqualTo(new Totals(7, 3, 10));
        assertThat(faultSnapshot.values().stream().filter(OrderState::isUnknown).map(OrderState::orderId).toList())
            .containsExactlyInAnyOrderElementsOf(unknownIds);
        for (Long orderId : unknownIds) {
            OrderState state = faultSnapshot.get(orderId);
            assertThat(state.orderStatus()).isEqualTo("PAYMENT_PENDING");
            assertThat(state.unknownReason()).isEqualTo("TIMEOUT");
            assertThat(state.transactionKey()).as("생성 응답을 잃어 거래 키를 모른다").isNull();
            assertThat(state.reservation()).as("결과를 모르는 동안 예약을 유지한다").isEqualTo("RESERVED");
            assertThat(state.outboxRows()).isZero();
            assertThat(state.confirmedAt()).isNull();
        }
        assertThat(pg.transactionCount()).isEqualTo(20);
        assertThat(pg.createCount()).isEqualTo(20);
        assertThat(pg.duplicateCreates()).isEmpty();
        assertThat(stock()).isEqualTo(INITIAL_STOCK - 20);

        String health = actuator("/actuator/health");
        assertThat(healthStatus(health)).as("프로세스 health는 UP인데 업무는 끝나지 않았다").isEqualTo("UP");
        String prometheus = awaitUnresolved(3);

        // 경계 로그: 3건은 생성 응답 timeout을 받았고, 그 관찰을 UNKNOWN으로 commit했으며, 성공 적용 시도나 rollback은 없다
        List<Map<String, String>> logs = boundaryLogsOf(faultIds);
        for (Long orderId : unknownIds) {
            String providerOrderId = ProviderOrderId.from(orderId);
            assertThat(logs).anySatisfy(entry -> assertThat(entry).containsEntry(BoundaryLog.EVENT_KEY, "pg.create.result")
                .containsEntry("providerOrderId", providerOrderId).containsEntry("outcome", "UNKNOWN").containsEntry("reason", "TIMEOUT"));
            assertThat(logs).anySatisfy(entry -> assertThat(entry).containsEntry(BoundaryLog.EVENT_KEY, "payment.apply")
                .containsEntry("orderId", orderId.toString()).containsEntry("to", "UNKNOWN").containsEntry("effect", "NONE"));
            assertThat(logs).noneSatisfy(entry -> assertThat(entry).containsEntry("orderId", orderId.toString())
                .containsEntry("effect", "SUCCEEDED"));
            assertThat(logs).noneSatisfy(entry -> assertThat(entry).containsEntry("orderId", orderId.toString())
                .containsEntry("tx", "ROLLED_BACK"));
        }

        run.writeText("fault.health.json", health);
        run.writeText("fault.prometheus.txt", prometheus);
        run.writeText("fault.boundary-log.jsonl", jsonLines(logs));
        run.writeJson("fault.json", stageRecord("FAULT", faultIds, faultSnapshot, totals, health, prometheus));
    }

    // ---- 3. 읽기 전용 진단 ----

    private void diagnose(IncidentEvidence run) {
        // 조치 전에 가설과 예상 관찰을 먼저 남긴다
        List<Map<String, Object>> hypotheses = List.of(
            hypothesis("H1", "PG 처리 자체가 지연", "같은 주문의 권위 조회가 PENDING", "해당 주문 번호의 거래 조회"),
            hypothesis("H2", "PG 성공 응답 유실", "PG SUCCESS, 내부 UNKNOWN, unknown_reason=TIMEOUT, 성공 적용 시도 없음",
                "거래 조회 + adapter 결과(pg.create.result) + 주문·시도 상태"),
            hypothesis("H3", "성공 결과의 내부 반영 실패", "PG SUCCESS, 성공 적용 시도(payment.apply SUCCEEDED) 뒤 ROLLED_BACK",
                "payment.apply·payment.apply.tx 기록, 예약·Outbox 상태")
        );
        run.writeJson("diagnosis-hypotheses.json", Map.of("runId", run.runId(), "orders", unknownIds, "hypotheses", hypotheses));

        Map<Long, OrderState> before = probe(faultIds);
        int createsBefore = pg.createCount();
        Map<String, Object> lookups = new LinkedHashMap<>();
        for (Long orderId : unknownIds) {
            // commerce 상태를 바꾸지 않는 조회다. reconcile API는 결과를 적용하므로 진단에 쓰지 않는다
            PgLookupResult result = paymentGateway.findTransactionsByOrder(ProviderOrderId.from(orderId));
            assertThat(result).isInstanceOf(PgLookupResult.Transactions.class);
            List<PgTransaction> found = ((PgLookupResult.Transactions) result).transactions();
            assertThat(found).singleElement().satisfies(transaction -> {
                assertThat(transaction.status()).isEqualTo(PgTransactionStatus.SUCCESS);
                assertThat(transaction.amount()).isEqualTo(before.get(orderId).amount());
            });
            lookups.put(orderId.toString(), found);
        }
        assertThat(probe(faultIds)).as("진단 조회는 commerce 상태를 바꾸지 않는다").isEqualTo(before);
        assertThat(pg.createCount()).isEqualTo(createsBefore);

        run.writeJson("diagnosis.json", Map.of(
            "runId", run.runId(),
            "orders", unknownIds,
            "authoritativeLookups", lookups,
            "verdicts", List.of(
                verdict("H1", "기각", "3건 모두 권위 조회가 1건·SUCCESS·저장 금액과 같음"),
                verdict("H2", "지지", "3건 모두 pg.create.result outcome=UNKNOWN reason=TIMEOUT, payment.apply REQUESTED→UNKNOWN COMMITTED, "
                    + "unknown_reason=TIMEOUT, 거래 키 미보유"),
                verdict("H3", "기각", "3건에 payment.apply effect=SUCCEEDED와 payment.apply.tx ROLLED_BACK 기록이 없음")
            ),
            "missingEvidence", List.of(
                "분산 trace는 구현하지 않았다. 판정은 같은 주문 번호로 이은 경계 로그·DB 상태·PG 조회에 기댄다.",
                "테스트 PG fixture가 응답을 붙잡은 사실은 fixture 내부 상태라 운영 PG에서는 같은 근거를 얻을 수 없다. 운영에서는 PG 측 요청 로그가 필요하다."
            )
        ));
    }

    // ---- 4. RECOVERY ----

    private void recover(IncidentEvidence run) throws Exception {
        clock.set(WEEK_START.plus(Duration.ofDays(3)));
        pg.releaseCreateHolds();
        assertThat(pg.isCreateHoldActive()).as("recovery에서는 응답 유실 통제가 꺼져 있어야 한다").isFalse();
        int createsBefore = pg.createCount();
        long pointBefore = pointBalance();
        long couponsBefore = couponCount();

        for (Long orderId : unknownIds) {
            reconcile(USER_ID, orderId).andExpect(status().isOk());
        }

        Map<Long, OrderState> recovered = probe(faultIds);
        Totals totals = Totals.of(recovered.values());
        assertThat(totals).isEqualTo(new Totals(10, 0, 10));
        assertThat(pg.createCount()).as("복구 중 PG create 추가").isEqualTo(createsBefore);
        assertThat(pg.transactionCount()).isEqualTo(20);
        assertThat(pg.duplicateCreates()).isEmpty();
        for (Long orderId : faultIds) {
            OrderState state = recovered.get(orderId);
            assertThat(state.reservation()).isEqualTo("CONSUMED");
            assertThat(state.outboxRows()).isEqualTo(1);
            assertThat(state.pgCreates()).isEqualTo(1);
            assertThat(state.confirmedAt()).isNotNull();
        }
        for (Long orderId : unknownIds) {
            // 처음 확정된 시각은 복구한 이 시각이며, 같은 주 안이다
            assertThat(recovered.get(orderId).confirmedAt()).isEqualTo(clock.instant());
        }
        for (Long orderId : faultIds) {
            if (!unknownIds.contains(orderId)) {
                assertThat(recovered.get(orderId).confirmedAt()).isEqualTo(faultSnapshot.get(orderId).confirmedAt());
            }
        }
        assertThat(stock()).as("성공 적용 때 재고를 다시 차감하지 않는다").isEqualTo(INITIAL_STOCK - 20);
        assertThat(pointBalance()).isEqualTo(pointBefore);
        assertThat(couponCount()).isEqualTo(couponsBefore);

        // 같은 복구·늦은 알림이 반복돼도 같은 사실로 수렴한다
        clock.set(WEEK_START.plus(Duration.ofDays(3)).plus(Duration.ofHours(1)));
        for (Long orderId : unknownIds) {
            reconcile(USER_ID, orderId).andExpect(status().isOk());
            TestPgTransaction transaction = PaymentTestSupport.onlyTransactionOf(pg, orderId);
            E2eSupport.callbackOf(mvc, transaction).andExpect(status().isOk());
        }
        assertThat(probe(faultIds)).isEqualTo(recovered);
        assertThat(pg.createCount()).isEqualTo(createsBefore);

        // 다른 사용자는 남의 주문을 조회·조정할 수 없다
        reconcile(OTHER_USER_ID, unknownIds.get(0)).andExpect(status().isNotFound());
        assertThat(probe(faultIds)).isEqualTo(recovered);

        String health = actuator("/actuator/health");
        assertThat(healthStatus(health)).isEqualTo("UP");
        String prometheus = awaitUnresolved(0);
        assertThat(run.currentHash("fault.json")).as("fault 기록을 덮어쓰지 않았다").isEqualTo(run.recordedHash("fault.json"));
        assertThat(run.currentHash("fault.boundary-log.jsonl")).isEqualTo(run.recordedHash("fault.boundary-log.jsonl"));

        Map<String, Object> record = new LinkedHashMap<>(stageRecord("RECOVERY", faultIds, recovered, totals, health, prometheus));
        record.put("pgCreatesBeforeRecovery", createsBefore);
        record.put("pgCreatesAfterRecovery", pg.createCount());
        record.put("pointBalance", Map.of("before", pointBefore, "after", pointBalance()));
        record.put("couponCount", Map.of("before", couponsBefore, "after", couponCount()));
        record.put("faultHashUnchanged", true);
        run.writeText("recovery.health.json", health);
        run.writeText("recovery.prometheus.txt", prometheus);
        run.writeJson("recovery.json", record);
    }

    // ---- 5. 후속 결과: Outbox → Kafka → 구매 활동 → 주간 랭킹 ----

    private void followUp(IncidentEvidence run) throws Exception {
        List<Long> allIds = new ArrayList<>(baselineIds);
        allIds.addAll(faultIds);

        OrderOutboxRelay.Run relayRun = relay.runOnce();
        assertThat(relayRun.published()).isEqualTo(20);
        E2eSupport.awaitCommittedOffset(GROUP, TOPIC, relayRun.published(), DEADLINE,
            () -> E2eSupport.describe(GROUP, TOPIC, consumer, jdbcTemplate, allIds));

        Map<String, Object> activities = new LinkedHashMap<>();
        for (Long orderId : allIds) {
            assertThat(E2eSupport.purchaseActivities(jdbcTemplate, orderId)).as("주문 %d의 구매 활동", orderId)
                .containsExactly(org.assertj.core.api.Assertions.tuple(productId, 1));
            activities.put(orderId.toString(), Map.of(
                "activityRows", E2eSupport.purchaseActivities(jdbcTemplate, orderId).size(),
                "outboxAttempts", jdbcTemplate.queryForObject("select attempt_count from order_outbox where order_id = ?", Integer.class, orderId),
                "processedEventIds", E2eSupport.processedEventIds(jdbcTemplate, orderId)
            ));
        }

        // 복구된 주문까지 포함한 같은 주의 실제 활동을 새 snapshot으로 고정·계산·공개한다
        clock.set(WEEK_START.plus(Duration.ofDays(4)));
        JobExecution execution = jobLauncher.run(weeklyRankingJob, new JobParametersBuilder()
            .addString("periodStart", PERIOD_START, true)
            .addString("snapshotId", SNAPSHOT_ID, true)
            .toJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        String ranking = mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", PERIOD_START).param("limit", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.snapshotId").value(SNAPSHOT_ID))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].productId").value(productId))
            .andExpect(jsonPath("$.data.items[0].score").value(20))
            .andExpect(jsonPath("$.data.items[0].rank").value(1))
            .andReturn().getResponse().getContentAsString();
        assertThat(clock.instant()).isBefore(NEXT_WEEK_START);

        run.writeJson("follow-up.json", Map.of(
            "runId", run.runId(),
            "relay", Map.of("picked", relayRun.picked(), "published", relayRun.published(), "failed", relayRun.failed()),
            "committedOffset", E2eSupport.committedOffset(GROUP, TOPIC),
            "activities", activities,
            "snapshotId", SNAPSHOT_ID,
            "snapshotInputRows", jdbcTemplate.queryForObject(
                "select count(*) from ranking_snapshot_input where snapshot_id = ?", Integer.class, SNAPSHOT_ID)
        ));
        run.writeText("ranking-api.json", ranking);
    }

    // ---- 읽기 전용 관찰 ----

    /** 주문의 내부 상태·효과와 같은 주문 번호의 PG 거래를, 새 DB 조회로 읽는다. 아무것도 바꾸지 않는다. */
    private Map<Long, OrderState> probe(List<Long> orderIds) {
        Map<Long, OrderState> states = new LinkedHashMap<>();
        for (Long orderId : orderIds) {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                "select o.status as order_status, o.confirmed_at, a.status as attempt_status, a.unknown_reason, a.attempt_key, "
                    + "a.provider_order_id, a.transaction_key, a.amount, r.status as reservation_status "
                    + "from orders o join payment_attempt a on a.order_id = o.id join stock_reservation r on r.order_id = o.id where o.id = ?",
                orderId);
            String providerOrderId = (String) row.get("provider_order_id");
            List<TestPgTransaction> transactions = pg.transactionsOf(providerOrderId);
            Object confirmedAt = row.get("confirmed_at");
            states.put(orderId, new OrderState(
                orderId,
                (String) row.get("order_status"),
                (String) row.get("attempt_status"),
                (String) row.get("unknown_reason"),
                (String) row.get("attempt_key"),
                providerOrderId,
                (String) row.get("transaction_key"),
                ((Number) row.get("amount")).longValue(),
                (String) row.get("reservation_status"),
                jdbcTemplate.queryForObject("select count(*) from order_outbox where order_id = ?", Integer.class, orderId),
                confirmedAt == null ? null : toInstant(confirmedAt),
                transactions.stream().map(transaction -> transaction.status().name()).toList(),
                pg.createCountOf(providerOrderId)
            ));
        }
        return states;
    }

    private record OrderState(
        Long orderId, String orderStatus, String attemptStatus, String unknownReason, String attemptKey, String providerOrderId,
        String transactionKey, long amount, String reservation, int outboxRows, Instant confirmedAt, List<String> pgStatuses, int pgCreates
    ) {
        boolean isUnknown() {
            return "UNKNOWN".equals(attemptStatus);
        }

        boolean isConfirmed() {
            return "CONFIRMED".equals(orderStatus) && "SUCCESS".equals(attemptStatus);
        }

        boolean hasSinglePgSuccess() {
            return pgStatuses.equals(List.of("SUCCESS"));
        }
    }

    /** 과제 표의 세 수: 내부 성공 · UNKNOWN · PG SUCCESS 거래가 정확히 1건인 주문. */
    private record Totals(long confirmed, long unknown, long pgSuccess) {
        static Totals of(java.util.Collection<OrderState> states) {
            return new Totals(
                states.stream().filter(OrderState::isConfirmed).count(),
                states.stream().filter(OrderState::isUnknown).count(),
                states.stream().filter(OrderState::hasSinglePgSuccess).count()
            );
        }
    }

    private Map<String, Object> stageRecord(String stage, List<Long> orderIds, Map<Long, OrderState> states, Totals totals,
                                            String health, String prometheus) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("stage", stage);
        record.put("observedAt", Instant.now().toString());
        record.put("testClock", clock.instant().toString());
        record.put("orderIds", orderIds);
        record.put("totals", totals);
        record.put("orders", states.values());
        record.put("pg", Map.of("transactions", pg.transactionCount(), "creates", pg.createCount(), "duplicateCreates", pg.duplicateCreates()));
        record.put("stock", stock());
        record.put("healthStatus", healthStatus(health));
        record.put("unresolvedMetric", unresolvedValue(prometheus));
        return record;
    }

    private int stock() {
        return productJpaRepository.findById(productId).orElseThrow().getStock();
    }

    private long pointBalance() {
        return jdbcTemplate.queryForObject("select balance from user_point where user_id = ?", Long.class, USER_ID);
    }

    private long couponCount() {
        return jdbcTemplate.queryForObject("select count(*) from coupon where user_id = ?", Long.class, USER_ID);
    }

    private ResultActions reconcile(long userId, Long orderId) throws Exception {
        return mvc.perform(post("/api/v1/orders/" + orderId + "/payment/reconcile").header("X-USER-ID", userId));
    }

    // ---- 실제 관리 포트의 Actuator ----

    private String actuator(String path) throws Exception {
        HttpResponse<String> response = HTTP.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path)).timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s 응답", path).isEqualTo(200);
        return response.body();
    }

    private static String healthStatus(String health) {
        try {
            return JSON.readTree(health).path("status").asText();
        } catch (Exception e) {
            throw new AssertionError("health 응답이 JSON이 아니다: " + health, e);
        }
    }

    /** 실제 Prometheus 응답에서 지표 값이 {@code expected}가 될 때까지 유한하게 기다리고, 그 응답 원문을 돌려준다. */
    private String awaitUnresolved(double expected) {
        String[] last = new String[1];
        try {
            await().atMost(METRIC_DEADLINE).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                last[0] = actuator("/actuator/prometheus");
                assertThat(unresolvedValue(last[0])).isEqualTo(expected);
            });
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("시한(" + METRIC_DEADLINE + ") 안에 " + UnresolvedPaymentMetrics.METRIC_NAME + "가 " + expected
                + "가 되지 않았습니다. 마지막 값=" + (last[0] == null ? "응답 없음" : unresolvedValue(last[0])), e);
        }
        return last[0];
    }

    private static Double unresolvedValue(String prometheus) {
        Matcher matcher = UNRESOLVED_LINE.matcher(prometheus);
        return matcher.find() ? Double.valueOf(matcher.group(1)) : null;
    }

    // ---- 경계 로그 ----

    /** 이 주문들과 그 PG 주문 번호에 해당하는 경계 로그를 기록 순서대로 key-value로 돌려준다. */
    private List<Map<String, String>> boundaryLogsOf(List<Long> orderIds) {
        Set<String> orderKeys = orderIds.stream().map(String::valueOf).collect(Collectors.toSet());
        Set<String> providerKeys = orderIds.stream().map(ProviderOrderId::from).collect(Collectors.toSet());
        List<Map<String, String>> entries = new ArrayList<>();
        for (ILoggingEvent event : List.copyOf(boundaryLogs.list)) {
            List<KeyValuePair> pairs = event.getKeyValuePairs();
            if (pairs == null || pairs.isEmpty()) {
                continue;
            }
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("time", Instant.ofEpochMilli(event.getTimeStamp()).toString());
            entry.put("level", event.getLevel().toString());
            pairs.forEach(pair -> entry.put(pair.key, String.valueOf(pair.value)));
            if (orderKeys.contains(entry.get("orderId")) || providerKeys.contains(entry.get("providerOrderId"))) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static String jsonLines(List<Map<String, String>> entries) {
        StringBuilder lines = new StringBuilder();
        for (Map<String, String> entry : entries) {
            try {
                lines.append(JSON.writeValueAsString(entry)).append('\n');
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return lines.toString();
    }

    private static Map<String, Object> hypothesis(String id, String name, String expected, String check) {
        Map<String, Object> hypothesis = new LinkedHashMap<>();
        hypothesis.put("id", id);
        hypothesis.put("hypothesis", name);
        hypothesis.put("expectedObservation", expected);
        hypothesis.put("howToCheck", check);
        return hypothesis;
    }

    private static Map<String, Object> verdict(String id, String verdict, String evidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("verdict", verdict);
        result.put("evidence", evidence);
        return result;
    }

    // Hibernate가 hibernate.jdbc.time_zone=UTC로 저장하므로(modules/jpa jpa.yml), 날 JDBC 값은 UTC 벽시계 시각으로 읽는다
    private static Instant toInstant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
        }
        if (value instanceof java.time.LocalDateTime local) {
            return local.toInstant(java.time.ZoneOffset.UTC);
        }
        if (value instanceof java.time.OffsetDateTime offset) {
            return offset.toInstant();
        }
        throw new IllegalStateException("알 수 없는 시각 타입: " + value.getClass());
    }
}
