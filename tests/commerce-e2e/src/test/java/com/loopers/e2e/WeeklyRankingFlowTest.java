package com.loopers.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderEventOutboxProcessor;
import com.loopers.application.order.OrderEventRelay;
import com.loopers.application.order.OrderEventSender;
import com.loopers.application.order.OutboxRelayProperties;
import com.loopers.batch.job.ranking.RankingChecksum;
import com.loopers.batch.job.ranking.SnapshotStatus;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingStore;
import com.loopers.batch.job.ranking.WeeklyRankingTarget;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.support.ApiTestClient;
import com.loopers.support.MutableClock;
import com.loopers.support.fixture.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * §7 실제 이벤트 입력과 늦은 구매. 기존 주문 API·로컬 PG fixture → Outbox relay → 실제 Kafka → 구매 활동 consumer(commerce-streamer) → DB 활동을
 * weeklyRanking job(commerce-batch)이 snapshot 으로 고정·계산·공개하고 랭킹 API(commerce-api)로 읽는다. 기대값은 테스트 시계와 만든 주문으로 계산한다.
 * 시계: api 는 주문마다 확정 시각을 정하는 MutableClock, batch 는 기간 종료 판단·고정 시각용 MutableClock.
 * 컨테이너는 이 클래스의 JVM 에만 있다 (forkEvery = 1). 테스트가 띄운 세 context 만 닫는다.
 * 실행: ./gradlew :tests:commerce-e2e:test --tests '*WeeklyRankingFlowTest' (Docker 필요).
 */
class WeeklyRankingFlowTest {
    private static final Logger log = LoggerFactory.getLogger(WeeklyRankingFlowTest.class);
    private static final String TOPIC = "order-events-e2e-ranking";
    private static final String GROUP = "purchase-activity-e2e-ranking";
    private static final LocalDate WEEK = LocalDate.parse("2026-08-17");
    private static final DateTimeFormatter DB_TEXT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS");

    private static final MutableClock API_CLOCK = new MutableClock(Instant.parse("2026-08-17T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final MutableClock BATCH_CLOCK = new MutableClock(Instant.parse("2026-08-24T01:00:00Z"), ZoneOffset.UTC);

    @TestConfiguration
    static class ApiClock {
        @Bean
        @Primary
        MutableClock apiMutableClock() {
            return API_CLOCK;
        }
    }

    /** 공개 TX 의 마지막 쓰기(snapshot 상태)를 한 번 실패시키는 저장소. 켰을 때만 실패한다. 나머지는 실제 SQL. */
    static class FailOncePublishStore extends WeeklyRankingStore {
        final AtomicBoolean failNextPublish = new AtomicBoolean();

        FailOncePublishStore(JdbcTemplate jdbc) {
            super(jdbc);
        }

        @Override
        public int markPublished(String snapshotId, SnapshotStatus status, String resultChecksum, LocalDateTime publishedAt, long jobExecutionId) {
            if (failNextPublish.getAndSet(false)) {
                throw new IllegalStateException("주입된 실패: 공개 TX 의 snapshot 상태 변경");
            }
            return super.markPublished(snapshotId, status, resultChecksum, publishedAt, jobExecutionId);
        }
    }

    @TestConfiguration
    static class BatchTestConfig {
        @Bean
        @Primary
        MutableClock batchMutableClock() {
            return BATCH_CLOCK;
        }

        @Bean
        @Primary
        FailOncePublishStore failOncePublishStore(JdbcTemplate jdbcTemplate) {
            return new FailOncePublishStore(jdbcTemplate);
        }
    }

    private ConfigurableApplicationContext api;
    private ConfigurableApplicationContext streamer;
    private ConfigurableApplicationContext batch;

    private ApiTestClient client;
    private UserModel user;

    @AfterEach
    void tearDown() {
        E2eContexts.closeQuietly(batch);
        E2eContexts.closeQuietly(streamer);
        E2eContexts.closeQuietly(api);
    }

    /** 확정 시각 at 의 주문 하나를 기존 API 로 만들고 확정한다 (PG SUCCESS → 확정 TX 에서 Outbox 한 행). */
    private long confirmedOrder(Instant at, Map<Long, Integer> items) {
        API_CLOCK.set(at);
        List<Map<String, Object>> lines = new ArrayList<>();
        items.forEach((productId, quantity) -> lines.add(Map.of("productId", productId, "quantity", quantity)));
        long orderId = client.post("/api/v1/orders", user.getId(), Map.of("items", lines))
            .assertSuccess(HttpStatus.CREATED).data().path("id").asLong();
        String status = client.post("/api/v1/orders/" + orderId + "/confirm", user.getId(),
                Map.of("paymentAttemptKey", "attempt-" + orderId, "cardType", "SAMSUNG", "cardNumber", "1234-5678-9814-1451"))
            .assertSuccess(HttpStatus.OK).data().path("status").asText();
        assertThat(status).isEqualTo("CONFIRMED");
        return orderId;
    }

    /** 지금 시계로 도래한 Outbox 를 모두 실제 broker 로 보내고, consumer 가 끝까지 처리·ack 할 때까지 기다린다. */
    private void relayAndConsume(int expectedPublished) {
        OrderEventRelay relay = new OrderEventRelay(api.getBean(OrderEventOutboxProcessor.class), api.getBean(OrderEventSender.class),
            api.getBean(OutboxRelayProperties.class), API_CLOCK);
        assertThat(relay.runDue().published()).isEqualTo(expectedPublished);
        E2eKafka.awaitConsumedAll(GROUP, TOPIC);
    }

    private JobExecution runJob(LocalDate periodStart, long revision) throws Exception {
        return batch.getBean(JobLauncher.class).run(batch.getBean(WeeklyRankingJobConfig.JOB_NAME, Job.class),
            new WeeklyRankingTarget(periodStart, revision).toJobParameters());
    }

    private JsonNode ranking(LocalDate periodStart, String extraQuery) {
        return client.get("/api/v1/rankings/weekly?periodStart=" + periodStart + extraQuery, null).assertSuccess(HttpStatus.OK).data();
    }

    /** (productId, score, rank) 순서대로. */
    private static List<List<Long>> items(JsonNode data) {
        return StreamSupport.stream(data.path("items").spliterator(), false)
            .map(item -> List.of(item.path("product").path("id").asLong(), item.path("score").asLong(), item.path("rank").asLong()))
            .toList();
    }

    private static RankingChecksum.InputLine line(long orderId, long productId, int quantity, Instant confirmedAt) {
        return new RankingChecksum.InputLine(orderId, productId, quantity, LocalDateTime.ofInstant(confirmedAt, ZoneOffset.UTC));
    }

    @DisplayName("[§7] 실제 주문→Outbox→Kafka→구매 활동을 snapshot 으로 고정해 공개하고 API 로 읽는다. 재발행은 점수를 늘리지 않고, 고정 뒤 늦은 구매는 r1 을 바꾸지 않으며 "
        + "새 revision(r2)이 공개 실패 동안엔 r1, 재시작으로 공개된 뒤엔 r2 가 보인다. 경계·빈 주·미공개·삭제 상품 제외도 API 에서 구분된다.")
    @Test
    void ranksRealPurchasesAndRecomputesLatePurchaseAsNewRevision() throws Exception {
        E2eContexts.startContainers();
        E2eDb db = new E2eDb();
        db.runScript(Path.of(System.getProperty("ranking.weekly-sql")));
        api = E2eContexts.startApi(TOPIC, List.of(), ApiClock.class);
        streamer = E2eContexts.startStreamer(TOPIC, GROUP);
        batch = E2eContexts.startBatch(BatchTestConfig.class);
        FailOncePublishStore store = batch.getBean(FailOncePublishStore.class);

        Fixtures fixtures = api.getBean(Fixtures.class);
        fixtures.catalogRevision();
        user = fixtures.userWithBalance(10_000L);
        UserModel admin = fixtures.admin();
        BrandModel brand = fixtures.brand("브랜드");
        List<ProductModel> products = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            products.add(fixtures.product(brand.getId(), "상품 " + i, 1_000L, 100));
        }
        assertThat(products.stream().map(ProductModel::getId).toList()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        client = new ApiTestClient(new TestRestTemplate(new RestTemplateBuilder().rootUri("http://localhost:" + E2eContexts.port(api))),
            api.getBean(ObjectMapper.class));

        // 1. 기존 주문 API 로 확정 (확정 시각 = 테스트 시계). 구간 시작은 포함, 끝·앞 주는 제외될 주문 포함
        Instant t1 = Instant.parse("2026-08-17T00:00:00Z");
        Instant t2 = Instant.parse("2026-08-18T01:00:00Z");
        Instant t3 = Instant.parse("2026-08-23T23:59:59.999999Z");
        Instant t4 = Instant.parse("2026-08-19T01:00:00Z");
        Instant tNextWeek = Instant.parse("2026-08-24T00:00:00Z");
        Instant tPrevWeek = Instant.parse("2026-08-16T23:59:59.999999Z");
        long o1 = confirmedOrder(t1, Map.of(7L, 2, 8L, 1));
        long o2 = confirmedOrder(t2, Map.of(7L, 3));
        long o3 = confirmedOrder(t3, Map.of(9L, 5));
        long o4 = confirmedOrder(t4, Map.of(6L, 4));
        long oNext = confirmedOrder(tNextWeek, Map.of(8L, 10));
        long oPrev = confirmedOrder(tPrevWeek, Map.of(9L, 7));
        API_CLOCK.set(tNextWeek);
        relayAndConsume(6);

        assertThat(db.activityWithTimeOfOrder(o1)).extracting(row -> row.get("product_id"), row -> row.get("quantity"), row -> row.get("confirmed_at"))
            .as("같은 주문의 두 품목이 모두, 확정 시각의 UTC 값으로")
            .containsExactly(tuple(7L, 2, DB_TEXT.format(LocalDateTime.ofInstant(t1, ZoneOffset.UTC))),
                tuple(8L, 1, DB_TEXT.format(LocalDateTime.ofInstant(t1, ZoneOffset.UTC))));
        assertThat(db.activityCount()).isEqualTo(7);

        // 2. 같은 이벤트 재발행 (W7 Outbox 에 저장된 payload 그대로) → 원본 활동이 늘지 않는다
        Map<String, Object> o1Outbox = db.outboxOfOrder(o1).get(0);
        api.getBean(OrderEventSender.class).send((String) o1Outbox.get("message_key"), (String) o1Outbox.get("payload")).get(30, TimeUnit.SECONDS);
        E2eKafka.awaitConsumedAll(GROUP, TOPIC);
        assertThat(db.activityCount()).isEqualTo(7);
        assertThat(db.processedOfOrder(o1)).hasSize(1);

        // 3. job 전: 공개본 없음
        JsonNode beforeJob = ranking(WEEK, "");
        assertThat(beforeJob.path("status").asText()).isEqualTo("NOT_PUBLISHED");
        assertThat(beforeJob.path("items")).isEmpty();

        // 4. r1: 실제 DB 활동을 고정·계산·공개
        WeeklyRankingTarget r1 = new WeeklyRankingTarget(WEEK, 1);
        JobExecution r1Execution = runJob(WEEK, 1);
        assertThat(r1Execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<RankingChecksum.InputLine> r1Lines = List.of(line(o1, 7, 2, t1), line(o1, 8, 1, t1), line(o2, 7, 3, t2), line(o3, 9, 5, t3), line(o4, 6, 4, t4));
        Map<String, Object> r1Snapshot = db.rankingSnapshot(r1.snapshotId());
        assertThat(db.rankingInputs(r1.snapshotId())).as("경계: 시작 포함·끝 제외, 앞 주 제외, 재발행 중복 없음")
            .containsExactly(List.of(o1, 7L, 2L), List.of(o1, 8L, 1L), List.of(o2, 7L, 3L), List.of(o3, 9L, 5L), List.of(o4, 6L, 4L));
        assertThat(r1Snapshot.get("input_checksum")).isEqualTo(RankingChecksum.ofInputs(r1Lines));
        List<List<Long>> r1Expected = List.of(List.of(7L, 5L, 1L), List.of(9L, 5L, 2L), List.of(6L, 4L, 3L), List.of(8L, 1L, 4L));
        assertThat(db.ranking(r1.snapshotId())).isEqualTo(r1Expected);
        JsonNode r1Api = ranking(WEEK, "");
        assertThat(r1Api.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(r1Api.path("snapshotId").asText()).isEqualTo(r1.snapshotId());
        assertThat(r1Api.path("revision").asLong()).isEqualTo(1);
        assertThat(r1Api.path("frozenAt").asText()).isEqualTo("2026-08-24T01:00:00Z");
        assertThat(items(r1Api)).isEqualTo(r1Expected);

        // 5. 고정 뒤 같은 주의 늦은 구매 (확정 2026-08-20, 소비는 지금) → r1 입력·결과·재실행은 그대로
        Instant tLate = Instant.parse("2026-08-20T00:00:00Z");
        long oLate = confirmedOrder(tLate, Map.of(8L, 6));
        relayAndConsume(1);
        assertThat(db.activityCount()).isEqualTo(8);
        assertThat(db.rankingInputs(r1.snapshotId())).hasSize(5);
        assertThat(db.rankingSnapshot(r1.snapshotId())).isEqualTo(r1Snapshot);
        assertThat(items(ranking(WEEK, ""))).isEqualTo(r1Expected);
        assertThatThrownBy(() -> runJob(WEEK, 1)).isInstanceOf(JobInstanceAlreadyCompleteException.class);

        // 6. 새 revision r2 를 명시적으로 만든다. 공개 TX 실패 동안은 r1 이 일관되게 보인다
        WeeklyRankingTarget r2 = new WeeklyRankingTarget(WEEK, 2);
        BATCH_CLOCK.set(Instant.parse("2026-08-25T01:00:00Z"));
        store.failNextPublish.set(true);
        JobExecution r2Failed = runJob(WEEK, 2);
        assertThat(r2Failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(db.rankingSnapshot(r2.snapshotId()).get("status")).isEqualTo("FROZEN");
        assertThat(db.ranking(r2.snapshotId())).isEmpty();
        JsonNode whileR2Failed = ranking(WEEK, "");
        assertThat(whileR2Failed.path("snapshotId").asText()).isEqualTo(r1.snapshotId());
        assertThat(items(whileR2Failed)).isEqualTo(r1Expected);

        // 7. 같은 parameter 로 재시작 → r2 공개
        JobExecution r2Restarted = runJob(WEEK, 2);
        assertThat(r2Restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(r2Restarted.getJobInstance().getInstanceId()).isEqualTo(r2Failed.getJobInstance().getInstanceId());
        List<RankingChecksum.InputLine> r2Lines = new ArrayList<>(r1Lines);
        r2Lines.add(line(oLate, 8, 6, tLate));
        Map<String, Object> r2Snapshot = db.rankingSnapshot(r2.snapshotId());
        assertThat(r2Snapshot.get("input_checksum")).isEqualTo(RankingChecksum.ofInputs(r2Lines));
        List<List<Long>> r2Expected = List.of(List.of(8L, 7L, 1L), List.of(7L, 5L, 2L), List.of(9L, 5L, 3L), List.of(6L, 4L, 4L));
        assertThat(db.ranking(r2.snapshotId())).isEqualTo(r2Expected);
        JsonNode r2Api = ranking(WEEK, "");
        assertThat(r2Api.path("snapshotId").asText()).isEqualTo(r2.snapshotId());
        assertThat(r2Api.path("revision").asLong()).isEqualTo(2);
        assertThat(items(r2Api)).isEqualTo(r2Expected);
        assertThat(db.ranking(r1.snapshotId())).as("이전 revision 의 행은 지우지 않는다").isEqualTo(r1Expected);

        // 8. 삭제 상품 제외: 저장된 순위 유지(1·3·4위), limit 은 제외 뒤에
        client.delete("/api-admin/v1/products/7", admin.getId()).assertSuccess(HttpStatus.OK);
        assertThat(items(ranking(WEEK, ""))).containsExactly(List.of(8L, 7L, 1L), List.of(9L, 5L, 3L), List.of(6L, 4L, 4L));
        assertThat(items(ranking(WEEK, "&limit=2"))).containsExactly(List.of(8L, 7L, 1L), List.of(9L, 5L, 3L));

        // 9. 다음 주: 끝 경계(다음 월 00:00Z) 주문이 그 주에 들어간다. 빈 주는 PUBLISHED·빈 items, job 을 돌리지 않은 주는 NOT_PUBLISHED
        BATCH_CLOCK.set(Instant.parse("2026-09-01T00:00:00Z"));
        LocalDate nextWeek = LocalDate.parse("2026-08-24");
        LocalDate emptyWeek = LocalDate.parse("2026-08-03");
        assertThat(runJob(nextWeek, 1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(runJob(emptyWeek, 1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(db.rankingInputs(new WeeklyRankingTarget(nextWeek, 1).snapshotId())).containsExactly(List.of(oNext, 8L, 10L));
        JsonNode empty = ranking(emptyWeek, "");
        assertThat(empty.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(empty.path("snapshotId").asText()).isEqualTo("weekly:2026-08-03:r1");
        assertThat(empty.path("items")).isEmpty();
        JsonNode unpublished = ranking(LocalDate.parse("2026-08-10"), "");
        assertThat(unpublished.path("status").asText()).as("앞 주 활동(oPrev)이 있어도 job 을 돌리지 않았으면 미공개").isEqualTo("NOT_PUBLISHED");
        assertThat(unpublished.path("items")).isEmpty();

        log.info("[W9 관찰: E2E] orders={}, 활동={}행, r1 input checksum={} result checksum={} ranking={}, 늦은 구매 order={} (확정 {}), "
                + "r2 실패 execution={} → 재시작 execution={} (instance {}), r2 input checksum={} result checksum={} ranking={}, 상품 7 삭제 뒤 API={}",
            List.of(o1, o2, o3, o4, oNext, oPrev), db.activityCount(), r1Snapshot.get("input_checksum"), r1Snapshot.get("result_checksum"), r1Expected,
            oLate, tLate, r2Failed.getId(), r2Restarted.getId(), r2Restarted.getJobInstance().getInstanceId(),
            r2Snapshot.get("input_checksum"), r2Snapshot.get("result_checksum"), r2Expected, items(ranking(WEEK, "")));
    }
}
