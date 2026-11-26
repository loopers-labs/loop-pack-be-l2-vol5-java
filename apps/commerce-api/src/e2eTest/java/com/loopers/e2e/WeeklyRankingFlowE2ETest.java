package com.loopers.e2e;

import com.loopers.CommerceApiApplication;
import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderOutboxMessage;
import com.loopers.domain.order.OrderOutboxModel;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.KafkaOrderOutboxSender;
import com.loopers.infrastructure.order.OrderOutboxJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.interfaces.consumer.OrderConfirmedListener;
import com.loopers.support.pg.PaymentTestSupport;
import com.loopers.support.pg.TestPgResetExtension;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.pg.TestPgStatus;
import com.loopers.testcontainers.KafkaBrokerFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.autoconfigure.filter.TypeExcludeFilters;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 구매 이벤트 입력부터 주간 랭킹 API까지 한 실행으로 확인한다 (W9 과제 7절, docs/week9/design.md 9절).
 *
 * <p>기존 주문 API·로컬 PG fixture·Outbox relay·실제 Kafka·실제 구매 활동 consumer(W6·W7)로 구매 활동을 만들고,
 * 그 실제 DB 활동을 commerce-batch의 실제 weeklyRanking job으로 고정·계산·공개한 뒤 랭킹 API로 읽는다.
 * 같은 이벤트 재발행, snapshot 고정 뒤 늦은 구매, 새 revision 공개, 구간 끝 경계를 이어서 확인한다.
 *
 * <p>context 구성: commerce-api 앱 하나의 context에서 streamer·batch 클래스는 component scan에서 빼고
 * ({@link ExcludeStreamerFromScan}, {@link ExcludeBatchFromScan}), 실제로 쓰는 부품만 명시한다.
 * 시각은 테스트 clock으로 정하고, 다른 E2E와 겹치지 않는 주(2027-01-04)와 이 테스트만의 topic·group·사용자·상품을 쓴다.
 */
@SpringBootTest(classes = CommerceApiApplication.class, properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "spring.batch.jdbc.initialize-schema=always"
})
@AutoConfigureMockMvc
@TypeExcludeFilters({ExcludeStreamerFromScan.class, ExcludeBatchFromScan.class})
@Import({PurchaseActivityConsumerConfig.class, WeeklyRankingJobImportConfig.class, WeeklyRankingFlowE2ETest.RankingClockConfig.class})
@ExtendWith(TestPgResetExtension.class)
class WeeklyRankingFlowE2ETest {

    private static final Logger log = LoggerFactory.getLogger(WeeklyRankingFlowE2ETest.class);
    private static final String TOPIC = KafkaBrokerFixture.createTopic("order-events-w9-ranking", 1);
    private static final String GROUP = KafkaBrokerFixture.uniqueName("purchase-activity-w9-ranking");
    private static final Duration DEADLINE = Duration.ofSeconds(60);

    private static final String PERIOD_START = "2027-01-04";
    private static final Instant WEEK_START = Instant.parse("2027-01-04T00:00:00Z");
    private static final Instant NEXT_WEEK_START = Instant.parse("2027-01-11T00:00:00Z");
    private static final long USER_ID = 90_001L;
    private static final int INITIAL_STOCK = 50;

    @TestConfiguration
    static class RankingClockConfig {
        @Bean
        @Primary
        SettableClock rankingClock() {
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

    @Autowired
    private SettableClock clock;

    @Autowired
    private OrderOutboxRelay relay;

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
    private OrderOutboxJpaRepository orderOutboxJpaRepository;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private KafkaProperties kafkaProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${order-outbox.relay.send-timeout}")
    private Duration sendTimeout;

    private final TestPgServer pg = TestPgServer.shared();
    private final List<KafkaOrderOutboxSender> producers = new ArrayList<>();
    private final List<Long> orderIds = new ArrayList<>();
    private MessageListenerContainer consumer;
    private long expectedOffset;

    @AfterEach
    void stopOwnResources() {
        listenerRegistry.getListenerContainer(OrderConfirmedListener.LISTENER_ID).stop();
        for (KafkaOrderOutboxSender producer : producers) {
            producer.destroy();
        }
    }

    @DisplayName("실제 주문 확정 → Outbox → Kafka → 구매 활동을 weeklyRanking으로 고정·공개하면 API가 그 순위를 보여 주고, "
        + "같은 이벤트 재발행은 점수를 늘리지 않으며, 고정 뒤 늦은 구매는 새 revision을 공개하기 전까지 보이지 않는다.")
    @Test
    void ranksRealPurchases_andAppliesLatePurchaseOnlyInNewRevision() throws Exception {
        // 0. 자원: 상품 A·B·C·D, 사용자, 실제 consumer
        BrandModel brand = brandJpaRepository.save(new BrandModel("w9-ranking-brand"));
        ProductModel a = productJpaRepository.save(new ProductModel(brand.getId(), "w9-a", 7_000L, INITIAL_STOCK));
        ProductModel b = productJpaRepository.save(new ProductModel(brand.getId(), "w9-b", 8_000L, INITIAL_STOCK));
        ProductModel c = productJpaRepository.save(new ProductModel(brand.getId(), "w9-c", 9_000L, INITIAL_STOCK));
        ProductModel d = productJpaRepository.save(new ProductModel(brand.getId(), "w9-d", 6_000L, INITIAL_STOCK));
        PointModel point = new PointModel(USER_ID);
        point.charge(100_000L);
        pointJpaRepository.save(point);
        consumer = listenerRegistry.getListenerContainer(OrderConfirmedListener.LISTENER_ID);
        consumer.start();
        E2eSupport.awaitAssigned(consumer, DEADLINE);

        // 1. 그 주의 확정 주문 세 건. 첫 주문은 구간 시작 시각 정각에 확정하고, 한 주문에 상품 두 개(A×2, B×1)를 담는다
        Long order1 = confirmedOrderAt(WEEK_START, Map.of(a.getId(), 2, b.getId(), 1));
        Long order2 = confirmedOrderAt(WEEK_START.plus(Duration.ofDays(1)), Map.of(a.getId(), 3));
        Long order3 = confirmedOrderAt(WEEK_START.plus(Duration.ofDays(2)), Map.of(c.getId(), 5));
        relayAndAwaitConsumed(3);
        assertThat(activityRowsOf(List.of(order1, order2, order3))).isEqualTo(4);

        // 2. 같은 이벤트(order1)를 다시 발행해도 구매 활동은 늘지 않는다
        KafkaOrderOutboxSender resender = new KafkaOrderOutboxSender(kafkaProperties, TOPIC, sendTimeout);
        producers.add(resender);
        OrderOutboxModel order1Outbox = orderOutboxJpaRepository.findAllByOrderId(order1).get(0);
        resender.send(OrderOutboxMessage.of(order1Outbox));
        expectedOffset += 1;
        E2eSupport.awaitCommittedOffset(GROUP, TOPIC, expectedOffset, DEADLINE,
            () -> E2eSupport.describe(GROUP, TOPIC, consumer, jdbcTemplate, orderIds));
        assertThat(activityRowsOf(List.of(order1, order2, order3))).isEqualTo(4);

        // 3. 공개 전에는 NOT_PUBLISHED
        rankingApi().andExpect(jsonPath("$.data.status").value("NOT_PUBLISHED"));

        // 4. r1: 실제 DB 활동을 고정·계산·공개한다. A=5, C=5(동점이면 id 오름차순), B=1
        clock.set(WEEK_START.plus(Duration.ofDays(3)));
        JobExecution r1 = jobLauncher.run(weeklyRankingJob, params("weekly-20270104-r1"));
        assertThat(r1.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        rankingApi()
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.snapshotId").value("weekly-20270104-r1"))
            .andExpect(jsonPath("$.data.items.length()").value(3))
            .andExpect(jsonPath("$.data.items[0].productId").value(a.getId()))
            .andExpect(jsonPath("$.data.items[0].score").value(5))
            .andExpect(jsonPath("$.data.items[0].rank").value(1))
            .andExpect(jsonPath("$.data.items[1].productId").value(c.getId()))
            .andExpect(jsonPath("$.data.items[1].rank").value(2))
            .andExpect(jsonPath("$.data.items[2].productId").value(b.getId()))
            .andExpect(jsonPath("$.data.items[2].score").value(1))
            .andExpect(jsonPath("$.data.items[2].rank").value(3));

        // 5. 고정 뒤 같은 주의 늦은 구매(B×7)와, 다음 주 시작 정각의 구매(D×4)가 들어온다
        Long late = confirmedOrderAt(WEEK_START.plus(Duration.ofDays(4)), Map.of(b.getId(), 7));
        Long nextWeek = confirmedOrderAt(NEXT_WEEK_START, Map.of(d.getId(), 4));
        relayAndAwaitConsumed(2);

        // 현재 공개본(r1)과 그 입력은 바뀌지 않고, 같은 parameter로 몰래 다시 계산할 수도 없다
        rankingApi()
            .andExpect(jsonPath("$.data.snapshotId").value("weekly-20270104-r1"))
            .andExpect(jsonPath("$.data.items[2].productId").value(b.getId()))
            .andExpect(jsonPath("$.data.items[2].score").value(1));
        assertThat(snapshotInputRows("weekly-20270104-r1")).isEqualTo(4);
        assertThatThrownBy(() -> jobLauncher.run(weeklyRankingJob, params("weekly-20270104-r1")))
            .isInstanceOf(JobInstanceAlreadyCompleteException.class);

        // 6. r2: 늦은 구매를 포함한 재계산을 명시적으로 수행한다. 다음 주 정각 구매는 구간 밖이다
        clock.set(NEXT_WEEK_START.plus(Duration.ofHours(1)));
        JobExecution r2 = jobLauncher.run(weeklyRankingJob, params("weekly-20270104-r2"));
        assertThat(r2.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(snapshotInputRows("weekly-20270104-r2")).isEqualTo(5);
        rankingApi()
            .andExpect(jsonPath("$.data.snapshotId").value("weekly-20270104-r2"))
            .andExpect(jsonPath("$.data.revision").value(2))
            .andExpect(jsonPath("$.data.items.length()").value(3))
            .andExpect(jsonPath("$.data.items[0].productId").value(b.getId()))
            .andExpect(jsonPath("$.data.items[0].score").value(8))
            .andExpect(jsonPath("$.data.items[1].productId").value(a.getId()))
            .andExpect(jsonPath("$.data.items[2].productId").value(c.getId()));
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from weekly_ranking where snapshot_id = 'weekly-20270104-r1'", Integer.class)).isEqualTo(3);

        log.info("[W9 E2E] orders={} late={} nextWeek={} | r1={} r2={}", List.of(order1, order2, order3), late, nextWeek,
            jdbcTemplate.queryForList("select product_id, score, rank_no from weekly_ranking where snapshot_id = 'weekly-20270104-r1' order by rank_no"),
            jdbcTemplate.queryForList("select product_id, score, rank_no from weekly_ranking where snapshot_id = 'weekly-20270104-r2' order by rank_no"));
    }

    /** 기존 주문 API로 주문을 만들고, 지정한 시각에 PG SUCCESS callback으로 확정한다. confirmedAt은 그 시각의 clock이다. */
    private Long confirmedOrderAt(Instant confirmedAt, Map<Long, Integer> items) throws Exception {
        clock.set(confirmedAt);
        Long orderId = E2eSupport.createOrder(mvc, USER_ID, new java.util.TreeMap<>(items));
        E2eSupport.confirm(mvc, USER_ID, orderId).andExpect(status().isAccepted());
        PaymentTestSupport.completeAt(pg, orderId, TestPgStatus.SUCCESS);
        E2eSupport.callbackOf(mvc, PaymentTestSupport.onlyTransactionOf(pg, orderId)).andExpect(status().isOk());
        orderIds.add(orderId);
        return orderId;
    }

    private void relayAndAwaitConsumed(int records) {
        OrderOutboxRelay.Run run = relay.runOnce();
        assertThat(run.published()).isEqualTo(records);
        expectedOffset += records;
        E2eSupport.awaitCommittedOffset(GROUP, TOPIC, expectedOffset, DEADLINE,
            () -> E2eSupport.describe(GROUP, TOPIC, consumer, jdbcTemplate, orderIds));
    }

    private ResultActions rankingApi() throws Exception {
        return mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", PERIOD_START).param("limit", "10"))
            .andExpect(status().isOk());
    }

    private JobParameters params(String snapshotId) {
        return new JobParametersBuilder()
            .addString("periodStart", PERIOD_START, true)
            .addString("snapshotId", snapshotId, true)
            .toJobParameters();
    }

    private int activityRowsOf(List<Long> orders) {
        return orders.stream().mapToInt(orderId -> E2eSupport.purchaseActivities(jdbcTemplate, orderId).size()).sum();
    }

    private int snapshotInputRows(String snapshotId) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from ranking_snapshot_input where snapshot_id = ?", Integer.class, snapshotId);
        return count == null ? 0 : count;
    }
}
