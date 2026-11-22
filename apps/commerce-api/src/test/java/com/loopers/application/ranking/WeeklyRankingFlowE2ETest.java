package com.loopers.application.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.brand.BrandFacade;
import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OrderOutboxTransactions;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.support.order.SpringAppProcess;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.testcontainers.KafkaTestBroker;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.ConsumerGroupState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * 실제 흐름: 주문 API → PG 확정 → Outbox relay → Kafka → 실제 commerce-streamer(별도 JVM)가 구매 활동 저장
 * → 실제 commerce-batch(별도 JVM)가 그 DB 활동으로 주간 snapshot을 고정·계산·공개 → 랭킹 API.
 * 같은 이벤트 재발행과, r1 공개 뒤 같은 주에 도착한 늦은 구매(r2)를 함께 확인한다.
 * 주간 기간은 테스트가 만든 주문의 실제 confirmedAt(UTC)으로 정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "commerce.order-events.enabled=true")
class WeeklyRankingFlowE2ETest {
    private static final KafkaTestBroker BROKER = new KafkaTestBroker();
    private static final FakePaymentServer PG = startPg();
    private static final String TOPIC = "order-events-ranking-" + UUID.randomUUID();
    private static final String GROUP = "purchase-activity";
    private static final int PARTITIONS = 3;
    private static final Duration CONSUMER_START_DEADLINE = Duration.ofSeconds(120);
    private static final Duration RESULT_DEADLINE = Duration.ofSeconds(30);
    private static final Duration BATCH_DEADLINE = Duration.ofSeconds(180);
    private static SpringAppProcess streamer;

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
        registry.add("commerce.order-events.topic", () -> TOPIC);
        registry.add("spring.kafka.bootstrap-servers", BROKER::bootstrapServers);
        registry.add("spring.kafka.admin.properties.bootstrap.servers", BROKER::bootstrapServers);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRegistrationService registration;
    @Autowired private BrandFacade brands;
    @Autowired private ProductFacade products;
    @Autowired private OrderOutboxRelay relay;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoSpyBean private OrderOutboxTransactions transactions;

    @Autowired private DataSource dataSource;

    private final List<Long> orderIds = new ArrayList<>();
    private long lastMark = System.nanoTime();

    /** 단계별 걸린 시간을 남긴다. 처리량 주장이 아니라 느린 단계를 찾기 위한 관찰이다. */
    private void mark(String step) {
        long now = System.nanoTime();
        System.out.println("[ranking-flow] " + step + " " + (now - lastMark) / 1_000_000 + " ms");
        lastMark = now;
    }

    /** 운영처럼 랭킹 스키마를 미리 만든다(ADR 0080). 첫 배치 전에도 API가 공개본 없음을 답할 수 있어야 한다. */
    @BeforeEach
    void createRankingSchema() {
        new ResourceDatabasePopulator(new FileSystemResource("../commerce-batch/src/main/resources/schema/weekly-ranking.sql"))
            .execute(dataSource);
    }

    @BeforeAll
    static void createTopic() throws Exception {
        PG.settleCreatedAs("SUCCESS");
        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void tearDown() {
        for (Long orderId : orderIds) {
            jdbc.update("DELETE FROM product_purchase_activity WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM processed_order_confirmation WHERE order_id = ?", orderId);
        }
        cleanUp.truncateAllTables();
    }

    @AfterAll
    static void closeResources() throws Exception {
        try {
            if (streamer != null) {
                streamer.close();
            }
        } finally {
            try {
                PG.close();
            } finally {
                BROKER.close();
            }
        }
    }

    @Test
    void realPurchaseEventsBecomePublishedWeeklyRankingAndLatePurchaseGoesToNextRevision() throws Exception {
        Long userId = registration.register().getId();
        Long brandId = brands.create("랭킹 브랜드", null).id();
        Long socks = products.create(brandId, "양말", 1_000L, 50).id();
        Long cap = products.create(brandId, "모자", 2_000L, 50).id();
        Long gloves = products.create(brandId, "장갑", 3_000L, 50).id();
        lastMark = System.nanoTime();
        startConsumer();
        mark("streamer 시작·partition 배정");

        // 1. 주문 1(양말 2, 모자 1): 한 주문 이벤트의 두 품목. relay가 PUBLISHED 저장 전에 멈췄다가 다시 보내 같은 이벤트가 두 번 간다.
        Long first = confirmOrder(userId, "ranking-1", Map.of(socks, 2, cap, 1));
        doThrow(new IllegalStateException("test: stop before saving PUBLISHED")).when(transactions).markPublished(anyLong());
        assertThatThrownBy(() -> relay.runOnce()).hasMessageContaining("test: stop");
        doCallRealMethod().when(transactions).markPublished(anyLong());
        assertThat(relay.runOnce()).isEqualTo(1);
        await("주문 1 구매 활동 2행과 재발행 처리", () -> activityRows(first) == 2 && consumerLag() == 0);
        assertThat(completions(first)).isEqualTo(1);
        mark("주문 1 확정·재발행·구매 활동");

        // 2. 주문 2(장갑 3, 양말 1)
        Long second = confirmOrder(userId, "ranking-2", Map.of(gloves, 3, socks, 1));
        assertThat(relay.runOnce()).isEqualTo(1);
        await("주문 2 구매 활동 2행", () -> activityRows(second) == 2);
        mark("주문 2 확정·구매 활동");

        // 기간: 생성한 주문의 실제 confirmedAt이 속한 UTC 주
        LocalDate periodStart = weekOf(first);
        assertThat(weekOf(second)).as("두 주문이 같은 UTC 주에 확정돼야 한다(주 경계 직전에 실행하면 다시 실행)")
            .isEqualTo(periodStart);
        String r1 = snapshotId(periodStart, 1);

        // 실행 전: 공개본 없음
        assertThat(ranking(periodStart).path("status").asText()).isEqualTo("NOT_PUBLISHED");

        // 3. 실제 commerce-batch로 r1: 양말 3, 장갑 3, 모자 1. 동점(3)은 productId가 작은 양말이 먼저다.
        assertThat(runBatch(periodStart, r1)).isZero();
        mark("batch r1");
        JsonNode published = ranking(periodStart);
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.path("snapshotId").asText()).isEqualTo(r1);
        assertThat(items(published)).containsExactly(socks + ":3:1", gloves + ":3:2", cap + ":1:3");
        assertThat(inputRows(r1)).isEqualTo(4);  // 재발행된 이벤트가 구매 활동·입력을 늘리지 않았다

        // 4. r1 공개 뒤 같은 주의 늦은 구매(모자 5)가 도착한다. 공개본과 r1 입력은 그대로다.
        Long late = confirmOrder(userId, "ranking-3", Map.of(cap, 5));
        assertThat(relay.runOnce()).isEqualTo(1);
        await("늦은 주문 구매 활동", () -> activityRows(late) == 1);
        mark("늦은 주문 확정·구매 활동");
        assertThat(weekOf(late)).isEqualTo(periodStart);
        assertThat(items(ranking(periodStart))).containsExactly(socks + ":3:1", gloves + ":3:2", cap + ":1:3");
        assertThat(inputRows(r1)).isEqualTo(4);

        // 5. 같은 주의 r2를 명시적으로 실행: 늦은 구매가 들어가 모자 6이 1위가 된다.
        String r2 = snapshotId(periodStart, 2);
        assertThat(runBatch(periodStart, r2)).isZero();
        mark("batch r2");
        JsonNode recalculated = ranking(periodStart);
        assertThat(recalculated.path("snapshotId").asText()).isEqualTo(r2);
        assertThat(recalculated.path("revision").asInt()).isEqualTo(2);
        assertThat(items(recalculated)).containsExactly(cap + ":6:1", socks + ":3:2", gloves + ":3:3");
        assertThat(inputRows(r2)).isEqualTo(5);
        assertThat(entries(r1)).containsExactly(socks + ":3:1", gloves + ":3:2", cap + ":1:3");  // r1을 몰래 고치지 않았다

        // 6. 다음 주는 공개본 없음, 상품을 삭제하면 계산 순위를 유지한 채 뺀다.
        assertThat(ranking(periodStart.plusWeeks(1)).path("status").asText()).isEqualTo("NOT_PUBLISHED");
        products.delete(cap);
        assertThat(items(ranking(periodStart))).containsExactly(socks + ":3:2", gloves + ":3:3");
    }

    private Long confirmOrder(Long userId, String attemptKey, Map<Long, Integer> quantities) {
        List<Map<String, Object>> items = quantities.entrySet().stream()
            .map(e -> Map.<String, Object>of("productId", e.getKey(), "quantity", e.getValue())).toList();
        Long orderId = post("/api/v1/orders", userId, Map.of("items", items), HttpStatus.CREATED)
            .path("data").path("id").asLong();
        orderIds.add(orderId);
        post("/api/v1/orders/" + orderId + "/confirm", userId, Map.of(
            "paymentAttemptKey", attemptKey, "cardType", "TEST", "cardNo", "0000-0000-0000-0000"), HttpStatus.ACCEPTED);
        post("/api/v1/orders/" + orderId + "/payment/reconcile", userId, Map.of(), HttpStatus.OK);
        return orderId;
    }

    /** 실제 commerce-batch를 별도 JVM으로 실행하고 종료 코드를 돌려준다. */
    private int runBatch(LocalDate periodStart, String snapshotId) throws Exception {
        try (SpringAppProcess batch = SpringAppProcess.batch(SpringAppProcess.Database.fromSystemProperties(),
            "weeklyRanking", List.of("periodStart=" + periodStart, "snapshotId=" + snapshotId))) {
            int exit = batch.waitForExit(BATCH_DEADLINE);
            System.out.println("[ranking-flow] batch " + snapshotId + " 로그 끝:\n" + batch.logTail());
            assertThat(exit).as("batch 종료 코드:\n%s", batch.logTail()).isZero();
            return exit;
        }
    }

    private JsonNode ranking(LocalDate periodStart) {
        ResponseEntity<JsonNode> response = rest.getForEntity(
            "/api/v1/rankings/weekly?periodStart=" + periodStart + "&limit=10", JsonNode.class);
        assertThat(response.getStatusCode()).as("랭킹 응답: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody().path("data");
    }

    private static List<String> items(JsonNode data) {
        List<String> items = new ArrayList<>();
        data.path("items").forEach(item -> items.add(
            item.path("productId").asLong() + ":" + item.path("score").asLong() + ":" + item.path("rank").asInt()));
        return items;
    }

    private List<String> entries(String snapshotId) {
        return jdbc.query(
            "SELECT product_id, score, ranking FROM ranking_entry WHERE snapshot_id = ? ORDER BY ranking",
            (rs, n) -> rs.getLong("product_id") + ":" + rs.getLong("score") + ":" + rs.getInt("ranking"), snapshotId);
    }

    private LocalDate weekOf(Long orderId) {
        LocalDateTime confirmedAt = jdbc.queryForObject(
            "SELECT MIN(confirmed_at) FROM product_purchase_activity WHERE order_id = ?", LocalDateTime.class, orderId);
        return confirmedAt.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static String snapshotId(LocalDate periodStart, int revision) {
        return "weekly-" + periodStart.format(DateTimeFormatter.BASIC_ISO_DATE) + "-r" + revision;
    }

    private int activityRows(Long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_purchase_activity WHERE order_id = ?",
            Integer.class, orderId);
    }

    private int completions(Long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_order_confirmation WHERE order_id = ?",
            Integer.class, orderId);
    }

    private int inputRows(String snapshotId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ranking_snapshot_input WHERE snapshot_id = ?",
            Integer.class, snapshotId);
    }

    private JsonNode post(String path, Long userId, Object body, HttpStatus expected) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", userId.toString());
        ResponseEntity<JsonNode> response = rest.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).as("%s 응답: %s", path, response.getBody()).isEqualTo(expected);
        return response.getBody();
    }

    private void startConsumer() throws Exception {
        streamer = SpringAppProcess.streamer(SpringAppProcess.Database.fromSystemProperties(), BROKER.bootstrapServers(), TOPIC);
        await("실제 consumer의 partition 배정", CONSUMER_START_DEADLINE, () -> {
            try (AdminClient admin = admin()) {
                var group = admin.describeConsumerGroups(List.of(GROUP)).all().get(10, TimeUnit.SECONDS).get(GROUP);
                int assigned = group.members().stream().mapToInt(m -> m.assignment().topicPartitions().size()).sum();
                return group.state() == ConsumerGroupState.STABLE && assigned == PARTITIONS;
            }
        });
    }

    /** consumer group이 topic의 모든 record를 commit했는지(남은 lag 0). */
    private long consumerLag() throws Exception {
        try (AdminClient admin = admin()) {
            var committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            var partitions = committed.keySet().stream()
                .filter(p -> p.topic().equals(TOPIC))
                .collect(Collectors.toMap(p -> p, p -> OffsetSpec.latest()));
            if (partitions.isEmpty()) {
                return Long.MAX_VALUE;
            }
            var ends = admin.listOffsets(partitions).all().get(10, TimeUnit.SECONDS);
            return partitions.keySet().stream()
                .mapToLong(p -> ends.get(p).offset() - committed.get(p).offset()).sum();
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

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
