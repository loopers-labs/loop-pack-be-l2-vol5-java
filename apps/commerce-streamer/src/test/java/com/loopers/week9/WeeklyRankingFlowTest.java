package com.loopers.week9;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.CommerceStreamerApplication;
import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OutboxKafkaSender;
import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.domain.SnapshotId;
import com.loopers.batch.job.ranking.infrastructure.RankingPublicationStore;
import com.loopers.batch.job.ranking.infrastructure.RankingSnapshotStore;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.support.payment.AdjustableClock;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RepositorySqlScripts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(classes = CommerceStreamerApplication.class, properties = {
        "payment-provider.connect-timeout=500ms", "payment-provider.read-timeout=2s",
        "payment-provider.lookup-retry.max-attempts=2", "payment-provider.lookup-retry.wait-duration=200ms",
        "payment-provider.lookup-retry.total-timeout=5s", "payment-provider.lookup-circuit.sliding-window-size=10",
        "payment-provider.lookup-circuit.minimum-number-of-calls=10",
        "payment-provider.lookup-circuit.failure-rate-threshold=50",
        "payment-provider.lookup-circuit.wait-duration-in-open-state=10s",
        "payment-provider.lookup-circuit.permitted-calls-in-half-open-state=1",
        "spring.kafka.listener.auto-startup=false", "purchase-activity.auto-startup=true", "order-events.partitions=2",
        "spring.batch.job.enabled=false", "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
        "spring.batch.jdbc.initialize-schema=always", "weekly-ranking.chunk-size=2"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WeeklyRankingFlowTest {
    private static final String TOPIC = "order-events-w9-flow-" + UUID.randomUUID();
    private static final String GROUP = "purchase-activity-w9-flow-" + UUID.randomUUID();
    private static final LocalPgServer PG = LocalPgServer.start();
    private static final AdjustableClock CONFIRMATION_CLOCK = new AdjustableClock();
    private static final String WEEK = "2026-08-17";
    private static final String NORMAL_SNAPSHOT = "weekly-20260817-r1";
    private static final String RESTART_SNAPSHOT = "weekly-20260817-r2";
    private static final String LATE_PURCHASE_SNAPSHOT = "weekly-20260817-r3";

    @TestBean(name = "orderConfirmationClock") private Clock orderConfirmationClock;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private OrderPaymentFixture fixtures;
    @Autowired private PaymentRepository payments;
    @Autowired private OrderOutboxRelay relay;
    @Autowired private OutboxKafkaSender sender;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JobLauncher jobLauncher;
    @Autowired @Qualifier(WeeklyRankingJobConfig.JOB_NAME) private Job weeklyRanking;
    @MockitoSpyBean private RankingSnapshotStore snapshotStore;
    @MockitoSpyBean private RankingPublicationStore publicationStore;
    private AdminClient admin;

    static Clock orderConfirmationClock() {
        return CONFIRMATION_CLOCK;
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        new MySqlTestContainersConfig();
        new RedisTestContainersConfig();
        registry.add("spring.kafka.bootstrap-servers", KafkaTestContainersConfig::bootstrapServers);
        registry.add("spring.kafka.admin.properties.bootstrap.servers", KafkaTestContainersConfig::bootstrapServers);
        registry.add("payment-provider.base-url", PG::baseUrl);
        registry.add("order-events.topic", () -> TOPIC);
        registry.add("purchase-activity.group-id", () -> GROUP);
    }

    @BeforeEach
    void prepare() {
        PG.reset();
        admin = AdminClient.create(Map.of("bootstrap.servers", KafkaTestContainersConfig.bootstrapServers()));
        List.of("ranking_publication", "ranking_result", "ranking_score_stage", "ranking_snapshot_input",
                "ranking_snapshot").forEach(table -> jdbc.execute("DROP TABLE IF EXISTS " + table));
        RepositorySqlScripts.run(dataSource, "docs/week9/sql/weekly-ranking-tables.sql");
    }

    @AfterEach
    void cleanup() throws Exception {
        var stopped = new CountDownLatch(1);
        listeners.stop(stopped::countDown);
        assertThat(stopped.await(15, TimeUnit.SECONDS)).isTrue();
        try {
            cleanup.deleteAllEntities();
            admin.deleteTopics(List.of(TOPIC)).all().get(10, TimeUnit.SECONDS);
        } finally {
            admin.close(Duration.ofSeconds(3));
        }
    }

    @AfterAll
    static void closePg() {
        PG.close();
    }

    @Test
    void 실제_구매_활동을_고정한_랭킹은_재발행과_늦은_구매에도_유지되고_새_revision에서만_바뀐다() throws Exception {
        long userId = fixtures.createBuyerWithPoints(100_000);
        long productA = fixtures.createProductWithStock(1_000, 50).getId();
        long productB = fixtures.createProductWithStock(1_000, 50).getId();

        confirmAt("2026-08-18T01:00:00Z");
        long firstOrder = createAndConfirmOrder(userId, Map.of(productA, 2, productB, 1), 1);
        relay.runOnce();
        awaitActivities(2);
        assertThat(activityConfirmedAt(firstOrder)).isEqualTo("2026-08-18T01:00");

        republishOutbox(firstOrder);
        awaitConsumerCaughtUp(2);
        assertThat(activityRows()).isEqualTo(2);

        var failFirstCalculation = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failFirstCalculation.getAndSet(false)) {
                throw new IllegalStateException("injected calculation failure");
            }
            return invocation.callRealMethod();
        }).when(snapshotStore).productTotal(any(SnapshotId.class), anyLong());

        JobExecution failed = launch(RESTART_SNAPSHOT);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        ranking().andExpect(jsonPath("$.data.status").value("NOT_PUBLISHED"));

        // 늦은 구매가 도착하기 전에 같은 구매 활동으로 정상 실행의 API 응답을 확보한다.
        assertThat(launch(NORMAL_SNAPSHOT).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<RankingItem> normalItems = rankingItems(ranking()
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.snapshotId").value(NORMAL_SNAPSHOT)));

        confirmAt("2026-08-20T01:00:00Z");
        createAndConfirmOrder(userId, Map.of(productB, 3), 2);
        relay.runOnce();
        awaitActivities(3);

        JobExecution restarted = launch(RESTART_SNAPSHOT);
        assertThat(restarted.getJobInstance().getInstanceId()).isEqualTo(failed.getJobInstance().getInstanceId());
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(inputRows(RESTART_SNAPSHOT)).isEqualTo(2);
        List<RankingItem> restartedItems = rankingItems(ranking()
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.snapshotId").value(RESTART_SNAPSHOT))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].productId").value(productA))
                .andExpect(jsonPath("$.data.items[0].score").value(2))
                .andExpect(jsonPath("$.data.items[1].productId").value(productB))
                .andExpect(jsonPath("$.data.items[1].score").value(1)));
        assertThat(restartedItems).as("같은 입력의 정상 실행과 재시작에서 API가 반환한 랭킹")
                .isEqualTo(normalItems);

        var failFirstPublication = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failFirstPublication.getAndSet(false)) {
                throw new IllegalStateException("injected publication failure");
            }
            return invocation.callRealMethod();
        }).when(publicationStore).publishIfNewer(any(SnapshotId.class), any());

        assertThat(launch(LATE_PURCHASE_SNAPSHOT).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(inputRows(LATE_PURCHASE_SNAPSHOT)).isEqualTo(3);
        ranking().andExpect(jsonPath("$.data.snapshotId").value(RESTART_SNAPSHOT));

        assertThat(launch(LATE_PURCHASE_SNAPSHOT).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        ranking()
                .andExpect(jsonPath("$.data.snapshotId").value(LATE_PURCHASE_SNAPSHOT))
                .andExpect(jsonPath("$.data.items[0].productId").value(productB))
                .andExpect(jsonPath("$.data.items[0].score").value(4))
                .andExpect(jsonPath("$.data.items[0].rank").value(1))
                .andExpect(jsonPath("$.data.items[1].productId").value(productA))
                .andExpect(jsonPath("$.data.items[1].score").value(2));
        assertThat(inputRows(RESTART_SNAPSHOT)).isEqualTo(2);
    }

    private void confirmAt(String instant) {
        CONFIRMATION_CLOCK.advance(Duration.between(CONFIRMATION_CLOCK.instant(), Instant.parse(instant)));
    }

    private JobExecution launch(String snapshotId) throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString("periodStart", WEEK, true)
                .addString("snapshotId", snapshotId, true)
                .toJobParameters();
        return jobLauncher.run(weeklyRanking, params);
    }

    private ResultActions ranking() throws Exception {
        return mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", WEEK)).andExpect(status().isOk());
    }

    private List<RankingItem> rankingItems(ResultActions response) throws Exception {
        var items = mapper.readTree(response.andReturn().getResponse().getContentAsString())
                .required("data").required("items");
        var ranking = new ArrayList<RankingItem>();
        for (var item : items) {
            ranking.add(new RankingItem(item.required("productId").asLong(), item.required("score").asLong(),
                    item.required("rank").asInt()));
        }
        return ranking;
    }

    private record RankingItem(long productId, long score, int rank) {
    }

    private long createAndConfirmOrder(long userId, Map<Long, Integer> quantities, int attempt) throws Exception {
        var items = quantities.entrySet().stream()
                .map(item -> Map.of("productId", item.getKey(), "quantity", item.getValue()))
                .toList();
        var result = mvc.perform(post("/api/v1/orders").header("X-USER-ID", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("items", items))))
                .andExpect(status().isCreated()).andReturn();
        long orderId = mapper.readTree(result.getResponse().getContentAsString()).path("data").path("orderId").asLong();
        String paymentBody = """
                {"paymentAttemptKey":"017f22e2-79b0-7cc3-98c4-dc0c0c07398%d","cardType":"TEST","cardNo":"0000-0000-0000-0000"}
                """.formatted(attempt);
        mvc.perform(post("/api/v1/orders/{orderId}/confirm", orderId).header("X-USER-ID", userId)
                .contentType(MediaType.APPLICATION_JSON).content(paymentBody)).andExpect(status().isAccepted());
        PG.succeedPaymentForOrder(payments.findByOrderId(orderId).orElseThrow().providerOrderId());
        mvc.perform(post("/api/v1/orders/{orderId}/payment/reconcile", orderId)
                .header("X-USER-ID", userId)).andExpect(status().isOk());
        return orderId;
    }

    private void republishOutbox(long orderId) {
        var row = jdbc.queryForMap(
                "select destination_topic, message_key, payload from order_outbox where order_id = ?", orderId);
        sender.send((String) row.get("destination_topic"), (String) row.get("message_key"), (String) row.get("payload"));
    }

    private void awaitActivities(int expected) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(activityRows()).isEqualTo(expected));
    }

    private void awaitConsumerCaughtUp(long expectedRecords) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(latestOffset(0) + latestOffset(1)).isEqualTo(expectedRecords);
            var committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS);
            for (int partition = 0; partition < 2; partition++) {
                long end = latestOffset(partition);
                if (end > 0) {
                    var offset = committed.get(new TopicPartition(TOPIC, partition));
                    assertThat(offset).isNotNull();
                    assertThat(offset.offset()).isEqualTo(end);
                }
            }
        });
    }

    private long latestOffset(int partition) throws Exception {
        var topicPartition = new TopicPartition(TOPIC, partition);
        return admin.listOffsets(Map.of(topicPartition, OffsetSpec.latest())).all().get(5, TimeUnit.SECONDS)
                .get(topicPartition).offset();
    }

    private int activityRows() {
        Integer rows = jdbc.queryForObject("select count(*) from product_purchase_activity", Integer.class);
        return rows == null ? 0 : rows;
    }

    private String activityConfirmedAt(long orderId) {
        return jdbc.queryForObject("select date_format(min(confirmed_at), '%Y-%m-%dT%H:%i') "
                + "from product_purchase_activity where order_id = ?", String.class, orderId);
    }

    private int inputRows(String snapshotId) {
        Integer rows = jdbc.queryForObject("select count(*) from ranking_snapshot_input where snapshot_id = ?",
                Integer.class, snapshotId);
        return rows == null ? 0 : rows;
    }
}
