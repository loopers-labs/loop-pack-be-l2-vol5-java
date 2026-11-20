package com.loopers.crossapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderCommand;
import com.loopers.application.order.OrderConfirmedOutboxRelay;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OutboxMessageSender;
import com.loopers.application.order.OutboxRelayProperties;
import com.loopers.application.order.OutboxRelayResult;
import com.loopers.application.payment.PaymentFacade;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.domain.order.OrderConfirmedOutbox;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.point.Point;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.ranking.ScoreProcessingHook;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.payment.FakePaymentGateway;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;

/**
 * 9주차 과제 §7 연결 (설계 8절·D15·7절 #7). 기존 주문 API + 가짜 PG + 실제 MySQL·Kafka + 실제 streamer consumer 로 구매 활동을 만들고,
 * 그 실제 행을 commerce-batch 의 weeklyRanking Job 이 고정·계산·공개한 뒤 commerce-api 의 랭킹 API 로 읽는다.
 * 재발행 무증가, 늦은 구매의 새 revision, 공개 전/후 전환, 경계 제외, 삭제 상품 제외를 한 흐름에서 확인한다.
 * 모든 테이블은 두 jar 의 엔티티를 @EntityScan("com.loopers") 이 잡아 ddl-auto 로 만들어진다. 실패 지점은 hook 만 쓰고 JobParameters 에는 넣지 않는다.
 */
@SpringBootTest(
    // classes 를 명시하면 중첩 @TestConfiguration 이 자동 등록되지 않아 함께 넘긴다
    classes = {CrossAppTestConfig.class, WeeklyRankingFlowE2ETest.HookConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "order-events.topic=" + WeeklyRankingFlowE2ETest.TOPIC,
        "purchase-activity.topic=" + WeeklyRankingFlowE2ETest.TOPIC,
        "purchase-activity.group-id=" + WeeklyRankingFlowE2ETest.GROUP,
        "purchase-activity.fetch-min-bytes=1",
        "purchase-activity.fetch-max-wait-ms=100",
        "purchase-activity.retry.max-retries=2",
        "purchase-activity.retry.initial-backoff-ms=100",
        "purchase-activity.retry.multiplier=2",
        "purchase-activity.dead-letter.send-timeout-ms=1000",
        // commerce-api 의 application.yml 에는 batch 설정이 없다. 기본 initialize-schema=embedded 는 MySQL 에 BATCH_ 테이블을 만들지 않는다 (조사 9-3).
        "spring.batch.job.enabled=false",
        "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
        "spring.batch.jdbc.initialize-schema=always"
    }
)
class WeeklyRankingFlowE2ETest {

    static final String TOPIC = "order-events-WeeklyRankingFlowE2ETest";
    static final String GROUP = "purchase-activity-WeeklyRankingFlowE2ETest";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final String ENDPOINT = "/api/v1/rankings/weekly?periodStart=";
    private static final String CARD_TYPE = "TEST";
    private static final String CARD_NO = "0000-0000-0000-0000";
    private static final Duration CONSUME_DEADLINE = Duration.ofSeconds(30);

    static {
        KafkaTestContainersConfig.createTopic(TOPIC, 1);
    }

    /** batch 테스트의 FailOnceScoreHook 과 같은 모양. crossAppTest classpath 에 batch 테스트 소스가 없어 다시 쓴다. */
    static final class FailOnceScoreHook implements ScoreProcessingHook {
        private record Target(String snapshotId, long productId) {}
        private final AtomicReference<Target> armed = new AtomicReference<>();

        void armFailOnce(String snapshotId, long productId) {
            armed.set(new Target(snapshotId, productId));
        }

        @Override
        public void beforeProcess(String snapshotId, long productId) {
            Target target = armed.get();
            if (target != null && target.snapshotId().equals(snapshotId) && target.productId() == productId
                && armed.compareAndSet(target, null)) {
                throw new IllegalStateException("before product " + productId);
            }
        }
    }

    @TestConfiguration
    static class HookConfig {
        @Primary
        @Bean
        FailOnceScoreHook failOnceScoreHook() {
            return new FailOnceScoreHook();
        }
    }

    @MockitoBean
    private Clock clock;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private PaymentFacade paymentFacade;
    @Autowired
    private FakePaymentGateway fakePaymentGateway;
    @Autowired
    private OutboxMessageSender realSender;
    @Autowired
    private OutboxRelayProperties relayProperties;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private OrderConfirmedOutboxRepository outboxRepository;
    @Autowired
    private UserJpaRepository userJpaRepository;
    @Autowired
    private ProductJpaRepository productJpaRepository;
    @Autowired
    private PointJpaRepository pointJpaRepository;
    @Autowired
    private PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JobLauncher jobLauncher;
    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job weeklyRankingJob;
    @Autowired
    private FailOnceScoreHook hook;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Long userId;
    private long publishedRecords;

    @BeforeEach
    void setUp() {
        fakePaymentGateway.reset();
        given(clock.getZone()).willReturn(ZoneId.of("Asia/Seoul"));
        userId = userJpaRepository.save(new User()).getId();
        Point point = new Point(userId);
        point.charge(1_000_000L);
        pointJpaRepository.save(point);
    }

    @DisplayName("실제 주문·PG·Outbox·Kafka·consumer 로 만든 구매 활동을 weeklyRanking Job 이 고정·계산·공개하고 API 가 읽는다: 미공개 → r1 → 재발행 무증가·r2 동일 → 늦은 구매는 r3 로만 → 경계 제외 → 삭제 상품 제외")
    @Test
    void weeklyRankingEndToEnd() throws Exception {
        Long x = saveProduct("상품 X");
        Long y = saveProduct("상품 Y");
        Long z = saveProduct("상품 Z");
        assertThat(x).isLessThan(y);
        assertThat(y).isLessThan(z);

        // (0) 미공개
        JsonNode notPublished = getRanking("2026-08-17");
        assertThat(notPublished.get("status").asText()).isEqualTo("NOT_PUBLISHED");
        assertThat(notPublished.get("items")).isEmpty();

        // (1) 실제 흐름으로 구매 활동 4행 (A: X×2·Y×1, B: X×3, C: Z×5)
        Long orderA = confirmOrderAt("2026-08-17T01:00:00Z", Map.of(x, 2, y, 1));
        Long orderB = confirmOrderAt("2026-08-18T01:00:00Z", Map.of(x, 3));
        confirmOrderAt("2026-08-19T01:00:00Z", Map.of(z, 5));
        assertThat(activityCount()).isEqualTo(4);

        // (2) r1
        setClock("2026-08-24T01:00:00Z");
        JobExecution r1 = run(params("2026-08-17", 1));
        assertThat(r1.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> rev1 = revision("weekly-20260817-r1");
        assertThat(rev1.get("input_count")).isEqualTo(4);
        assertThat(rev1.get("expected_product_count")).isEqualTo(3);
        JsonNode api1 = getRanking("2026-08-17");
        assertThat(api1.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(api1.get("snapshotId").asText()).isEqualTo("weekly-20260817-r1");
        assertThat(api1.get("frozenAt").asText()).isEqualTo("2026-08-24T01:00:00Z");
        assertItems(api1, List.of(x, z, y), List.of(5L, 5L, 1L), List.of(1, 2, 3));
        log("r1", r1, rev1, api1);

        // (3) 재발행 무증가 → r2 동일
        OrderConfirmedOutbox outboxA = outboxRepository.findByOrderId(orderA).orElseThrow();
        realSender.send(String.valueOf(orderA), outboxA.getPayload());
        awaitConsumed(++publishedRecords);
        assertThat(activityCount()).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_order_confirmation WHERE order_id = ?", Integer.class, orderA)).isEqualTo(1);
        JobExecution r2 = run(params("2026-08-17", 2));
        assertThat(r2.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> rev2 = revision("weekly-20260817-r2");
        assertThat(rev2.get("input_checksum")).isEqualTo(rev1.get("input_checksum"));
        assertThat(rev2.get("result_checksum")).isEqualTo(rev1.get("result_checksum"));
        JsonNode api2 = getRanking("2026-08-17");
        assertThat(api2.get("snapshotId").asText()).isEqualTo("weekly-20260817-r2");
        assertThat(api2.get("items")).isEqualTo(api1.get("items"));
        log("r2", r2, rev2, api2);

        // (4) 늦은 구매 D (Y×4, 그 주 안) → 공개본은 r2 그대로
        confirmOrderAt("2026-08-20T01:00:00Z", Map.of(y, 4));
        assertThat(activityCount()).isEqualTo(5);
        assertThat(getRanking("2026-08-17").get("snapshotId").asText()).isEqualTo("weekly-20260817-r2");

        // (5) r3: Step 2 상품 Z 직전 실패 → 공개본 여전히 r2 → 재시작 → r3 공개 (X=Y=Z=5, productId 순)
        setClock("2026-08-24T02:00:00Z");
        hook.armFailOnce("weekly-20260817-r3", z);
        JobExecution r3Failed = run(params("2026-08-17", 3));
        assertThat(r3Failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        Map<String, Object> rev3 = revision("weekly-20260817-r3");
        assertThat(rev3.get("input_count")).isEqualTo(5);
        assertThat(scores("weekly-20260817-r3")).isNotEmpty().allSatisfy(row -> assertThat(row.get("ranking")).isNull());
        assertThat(getRanking("2026-08-17").get("snapshotId").asText()).isEqualTo("weekly-20260817-r2");
        JobExecution r3 = run(params("2026-08-17", 3));
        assertThat(r3.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(r3.getJobInstance().getId()).isEqualTo(r3Failed.getJobInstance().getId());
        JsonNode api3 = getRanking("2026-08-17");
        assertThat(api3.get("snapshotId").asText()).isEqualTo("weekly-20260817-r3");
        assertItems(api3, List.of(x, y, z), List.of(5L, 5L, 5L), List.of(1, 2, 3));
        log("r3(restart)", r3, revision("weekly-20260817-r3"), api3);
        System.out.println("[flow] r3 first execution id=" + r3Failed.getId() + " status=" + r3Failed.getStatus());

        // (6) 경계: 2026-08-24T00:00:00Z 는 다음 주 → r4 는 E 제외(r3 과 같은 결과), 다음 주 r1 은 E 포함
        confirmOrderAt("2026-08-24T00:00:00Z", Map.of(x, 1));
        assertThat(activityCount()).isEqualTo(6);
        setClock("2026-08-31T01:00:00Z");
        JobExecution r4 = run(params("2026-08-17", 4));
        assertThat(r4.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> rev4 = revision("weekly-20260817-r4");
        assertThat(rev4.get("input_count")).isEqualTo(5);
        assertThat(rev4.get("result_checksum")).isEqualTo(revision("weekly-20260817-r3").get("result_checksum"));
        JobExecution next = run(params("2026-08-24", 1));
        assertThat(next.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> nextRev = revision("weekly-20260824-r1");
        assertThat(nextRev.get("input_count")).isEqualTo(1);
        JsonNode apiNext = getRanking("2026-08-24");
        assertThat(apiNext.get("status").asText()).isEqualTo("PUBLISHED");
        assertItems(apiNext, List.of(x), List.of(1L), List.of(1));
        log("r4", r4, rev4, getRanking("2026-08-17"));
        log("next-r1", next, nextRev, apiNext);

        // (7) 삭제 상품 Y → 표시 순위 rank 는 1·2, 계산 순위 calcRank 는 1·3 유지
        Product productY = productJpaRepository.findById(y).orElseThrow();
        productY.delete();
        productJpaRepository.save(productY);
        JsonNode apiDeleted = getRanking("2026-08-17");
        assertItems(apiDeleted, List.of(x, z), List.of(5L, 5L), List.of(1, 3));
        System.out.println("[flow] after delete Y: " + apiDeleted);
        System.out.println("[flow] orders A=" + orderA + " B=" + orderB + " products X=" + x + " Y=" + y + " Z=" + z);

        // 정리: 모든 확인 뒤에만 자신이 만든 업무 행을 지운다 (BATCH_ 는 건드리지 않음)
        databaseCleanUp.truncateAllTables();
    }

    // ---- helpers ----

    private Long confirmOrderAt(String confirmedAtUtc, Map<Long, Integer> quantities) {
        setClock(confirmedAtUtc);
        List<OrderCommand.Item> items = quantities.entrySet().stream()
            .map(e -> new OrderCommand.Item(e.getKey(), e.getValue())).toList();
        Long orderId = orderFacade.createOrder(userId, items).orderId();
        orderFacade.confirmOrder(userId, orderId, new OrderCommand.Confirm("flow-" + orderId, CARD_TYPE, CARD_NO));
        PaymentAttempt attempt = paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
        fakePaymentGateway.markSuccess(attempt.getTransactionKey());
        paymentFacade.applyCallback(attempt.getProviderOrderId(), attempt.getTransactionKey());

        OutboxRelayResult relayed = new OrderConfirmedOutboxRelay(outboxRepository, realSender, relayProperties, clock, transactionManager).runOnce();
        assertThat(relayed.published()).isEqualTo(1);
        awaitConsumed(++publishedRecords);
        return orderId;
    }

    private void setClock(String instant) {
        given(clock.instant()).willReturn(Instant.parse(instant));
    }

    private JobExecution run(JobParameters params) throws Exception {
        return jobLauncher.run(weeklyRankingJob, params);
    }

    private static JobParameters params(String periodStart, int revision) {
        return new JobParametersBuilder()
            .addString("periodStart", periodStart, true)
            .addString("snapshotId", "weekly-" + periodStart.replace("-", "") + "-r" + revision, true)
            .toJobParameters();
    }

    private JsonNode getRanking(String periodStart) throws Exception {
        String body = restTemplate.getForObject(ENDPOINT + periodStart, String.class);
        return objectMapper.readTree(body).get("data");
    }

    /** calcRanks 는 DB 계산 순위(간격 가능), 표시 순위 rank 는 항상 1..n 연속이어야 한다. */
    private static void assertItems(JsonNode api, List<Long> productIds, List<Long> scores, List<Integer> calcRanks) {
        JsonNode items = api.get("items");
        assertThat(items).hasSize(productIds.size());
        for (int i = 0; i < productIds.size(); i++) {
            assertThat(items.get(i).get("productId").asLong()).isEqualTo(productIds.get(i));
            assertThat(items.get(i).get("score").asLong()).isEqualTo(scores.get(i));
            assertThat(items.get(i).get("rank").asInt()).isEqualTo(i + 1);
            assertThat(items.get(i).get("calcRank").asInt()).isEqualTo(calcRanks.get(i));
        }
    }

    private int activityCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_purchase_activity", Integer.class);
    }

    private Map<String, Object> revision(String snapshotId) {
        return jdbcTemplate.queryForMap("SELECT * FROM weekly_ranking_revisions WHERE snapshot_id = ?", snapshotId);
    }

    private List<Map<String, Object>> scores(String snapshotId) {
        return jdbcTemplate.queryForList("SELECT product_id, score, ranking FROM weekly_ranking_scores WHERE snapshot_id = ? ORDER BY product_id", snapshotId);
    }

    private static void log(String label, JobExecution execution, Map<String, Object> revision, JsonNode api) {
        System.out.println("[flow:" + label + "] jobInstanceId=" + execution.getJobInstance().getId() + " jobExecutionId=" + execution.getId()
            + " status=" + execution.getStatus() + " input_count=" + revision.get("input_count")
            + " input_checksum=" + revision.get("input_checksum") + " result_checksum=" + revision.get("result_checksum")
            + " api=" + api);
    }

    private void awaitConsumed(long expectedCommittedOffset) {
        await().atMost(CONSUME_DEADLINE).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
            assertThat(committedOffset()).as("group %s committed offset", GROUP).isEqualTo(expectedCommittedOffset));
    }

    private long committedOffset() {
        try (AdminClient admin = AdminClient.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()))) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(GROUP)
                .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).get(PARTITION);
            return committed == null ? -1 : committed.offset();
        } catch (Exception e) {
            throw new IllegalStateException("committed offset 조회 실패", e);
        }
    }

    private Long saveProduct(String name) {
        Product product = new Product(1L, name, new Price(1000L));
        product.changeStock(100);
        return productJpaRepository.save(product).getId();
    }
}
