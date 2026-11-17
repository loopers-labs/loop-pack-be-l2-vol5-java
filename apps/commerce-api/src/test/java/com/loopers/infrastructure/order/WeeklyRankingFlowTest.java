package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.CommerceApiApplication;
import com.loopers.application.order.OrderOutboxExecutionGate;
import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OrderOutboxRelay.RowOutcome;
import com.loopers.application.order.OrderOutboxRelay.RowResult;
import com.loopers.application.order.OrderOutboxRelayProbe;
import com.loopers.application.order.OrderOutboxRelayTransactionService;
import com.loopers.config.OrderConfirmationClockConfig;
import com.loopers.config.OrderOutboxClockConfig;
import com.loopers.config.OrderOutboxRelayProperties;
import com.loopers.domain.order.OrderOutboxMessageVerifier;
import com.loopers.domain.order.OrderOutboxSender;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.fixture.PgCommands;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.SettableClock;
import com.loopers.fixture.UserFixture;
import com.loopers.fixture.pg.CreateOutcome;
import com.loopers.fixture.pg.PgFixtureServer;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import com.loopers.infrastructure.order.OrderOutboxE2eLifecycleSupport.ChildLaunch;
import com.loopers.infrastructure.order.OrderOutboxE2eLifecycleSupport.CleanupSteps;
import com.loopers.infrastructure.order.OrderOutboxE2eLifecycleSupport.Ownership;
import com.loopers.infrastructure.order.WeeklyRankingBatchChild.Result;
import com.loopers.testsupport.LocalKafkaTestSupport;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주간 랭킹 실제 연결 한 건(Implementation Quest 7절, 랭킹 설계 11.4~11.6). 실제 PG 확정 → Outbox relay → 실제 Kafka →
 * streamer 자식이 저장한 구매 활동 → batch 자식의 입력 고정·계산·공개(new·실패·restart·늦은 구매의 새 revision) → 랭킹 API 를
 * 같은 테스트 DB 에서 확인한다. 주문 확정 Clock 만 고정해 주문 A·B 를 2026-10-19 주에 넣는다.
 *
 * <p>정리: relay·streamer 자식·API·PG·구매 테이블·Kafka 소유 자원은 기존 W7 규칙으로 정리하고, 공유 랭킹 다섯 테이블과 BATCH_* 는
 * 지우지 않고 테스트 컨테이너 수명에 맡긴다(랭킹 설계 11.6).
 */
@Timeout(420)
@DisplayName("주간 랭킹 실제 연결: 결제 확정된 구매가 Kafka·streamer 를 거쳐 batch 입력·재시작·새 revision 공개와 API 응답까지 이어진다.")
class WeeklyRankingFlowTest {

    private static final String LABEL = "WEEKLY_RANKING_FLOW";
    private static final Duration TOTAL = Duration.ofSeconds(420);
    private static final Duration OBSERVE = Duration.ofSeconds(60);
    private static final Duration BATCH = Duration.ofSeconds(120);
    private static final Path LOG_DIR = Path.of("build", "weekly-ranking-flow");
    private static final LocalDate WEEK = LocalDate.of(2026, 10, 19);
    private static final LocalDate EMPTY_WEEK = LocalDate.of(2026, 10, 12);
    private static final Instant A_CONFIRMED_AT = Instant.parse("2026-10-19T00:00:00Z");
    private static final Instant B_CONFIRMED_AT = Instant.parse("2026-10-20T03:00:00Z");
    private static final long SENTINEL_ORDER = 900_000_001L;
    private static final String READ_COUNT_KEY = "weeklyRankingProductReader.read.count";

    private static final long STARTED = System.nanoTime();
    private static final SettableClock CONFIRMATION_CLOCK = new SettableClock(A_CONFIRMED_AT, ZoneOffset.UTC);
    private static final Ownership OWNERSHIP = new Ownership();

    private static LocalKafkaTestSupport kafka;
    private static PgFixtureServer pg;
    private static ConfigurableApplicationContext api;
    private static OrderActivityStreamerChild streamer;
    private static JdbcTemplate fresh;
    private static String topic;
    private static String group;

    private final RestTemplate http = client();
    private Long viewer;

    @BeforeAll
    static void prepare() throws Exception {
        kafka = LocalKafkaTestSupport.connect();
        topic = kafka.createTopic("weekly-ranking-flow");
        group = kafka.ownGroup("purchase-activity-ranking");
        pg = PgFixtureServer.start();
        Class.forName("com.loopers.testcontainers.MySqlTestContainersConfig");
        Class.forName("com.loopers.testcontainers.RedisTestContainersConfig");
        fresh = new JdbcTemplate(new DriverManagerDataSource(System.getProperty("datasource.mysql-jpa.main.jdbc-url"),
            System.getProperty("datasource.mysql-jpa.main.username"), System.getProperty("datasource.mysql-jpa.main.password")));
        api = bootApi();
    }

    /** relay 진입 중단 → streamer 자식 종료 → API 데이터·컨텍스트 → PG → 이 실행이 만든 구매 테이블 → Kafka 소유 자원. */
    @AfterAll
    static void releaseResources() {
        OrderOutboxE2eLifecycleSupport.release(OWNERSHIP, new CleanupSteps() {
            @Override
            public void stopRelay() throws InterruptedException {
                if (api != null && api.isActive()) {
                    OrderOutboxExecutionGate gate = api.getBean(OrderOutboxExecutionGate.class);
                    gate.stop();
                    if (!gate.awaitIdle(Duration.ofSeconds(10))) {
                        throw new IllegalStateException("진행 중인 relay 가 10초 안에 끝나지 않았습니다.");
                    }
                }
            }

            @Override
            public void restoreTopicConfig() {
            }

            @Override
            public void stopChild() throws InterruptedException {
                if (streamer != null) {
                    streamer.stop(LABEL, OBSERVE);
                }
            }

            @Override
            public boolean childExited() {
                return streamer == null || !streamer.isAlive();
            }

            @Override
            public void truncateApiData() {
                if (api != null && api.isActive()) {
                    api.getBean(DatabaseCleanUp.class).truncateAllTables();
                    api.getBean(RedisCleanUp.class).truncateAll();
                }
            }

            @Override
            public void closeApi() {
                if (api != null) {
                    api.close();
                }
            }

            @Override
            public void closePg() {
                if (pg != null) {
                    pg.close();
                }
            }

            @Override
            public void dropPurchaseTables() {
                List.of("processed_order_confirmation", "product_purchase_activity")
                    .forEach(table -> fresh.execute("DROP TABLE IF EXISTS " + table));
            }

            @Override
            public void closeTopicAdmin() {
            }

            @Override
            public void closeKafka() {
                if (kafka != null) {
                    kafka.close();
                }
            }
        });
    }

    @Test
    void purchasesFlowIntoRestartableWeeklyRankingAndApi() throws Exception {
        startStreamer();
        Result prepared = WeeklyRankingBatchChild.prepareSchema(LOG_DIR, limit(BATCH));
        assertThat(prepared.exitCode()).isZero();
        assertThat(weeklyRankingInstances()).as("준비 child 는 업무 Job 을 실행하지 않는다").isZero();
        viewer = api.getBean(UserFixture.class).createUserWithPoint().getId();
        assertThat(ranking(WEEK, null).path("status").asText()).isEqualTo("NOT_PUBLISHED");

        // 기간 끝(다음 월요일 00:00) sentinel 은 실제 PG 데이터와 구분된 source 행이며 이 주 입력에 들어가면 안 된다.
        ProductFixture products = api.getBean(ProductFixture.class);
        long a = products.createProduct("랭킹 A", 1_000L, 100L).getId();
        long b = products.createProduct("랭킹 B", 1_000L, 100L).getId();
        long c = products.createProduct("랭킹 C", 1_000L, 100L).getId();
        fresh.update("INSERT INTO product_purchase_activity (order_id, product_id, quantity, confirmed_at, created_at, updated_at) "
            + "VALUES (?, ?, 99, '2026-10-26 00:00:00.000000', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", SENTINEL_ORDER, a);

        // 주문 A: a2·b1·c2, 확정 2026-10-19T00:00Z → relay → 실제 consume
        long orderA = confirmOrder(Map.of(a, 2, b, 1, c, 2), A_CONFIRMED_AT);
        relayAndAwaitHandled(orderA, "APPLIED");
        assertThat(sourceRows(orderA)).isEqualTo(3);

        // r1 정상 비교본
        Result r1 = WeeklyRankingBatchChild.startNew(LOG_DIR, WEEK.toString(), null, limit(BATCH));
        assertThat(List.of(r1.exitCode(), r1.status())).isEqualTo(List.of(0, "COMPLETED"));
        List<String> expected = List.of(a + ":2:1", c + ":2:2", b + ":1:3");
        assertThat(results("weekly-20261019-r1")).isEqualTo(expected);
        JsonNode r1Api = ranking(WEEK, null);
        assertThat(r1Api.path("snapshotId").asText()).isEqualTo("weekly-20261019-r1");
        assertThat(items(r1Api)).isEqualTo(expected);

        // r2: 같은 A 입력 고정 뒤 a·b chunk commit, c 직전 한 번 실패
        Result failed = WeeklyRankingBatchChild.startNew(LOG_DIR, WEEK.toString(), c, limit(BATCH));
        assertThat(List.of(failed.exitCode(), failed.status())).isEqualTo(List.of(1, "FAILED"));
        assertThat(failed.processedProductIds()).containsExactly(a, b);
        assertThat(failed.output()).contains("WEEKLY_RANKING_FAULT beforeProduct=" + c);
        List<Object> r2Manifest = manifest("weekly-20261019-r2");
        assertThat(r2Manifest.subList(0, 3)).isEqualTo(List.of("READY", 3L, manifest("weekly-20261019-r1").get(2)));
        assertThat(stage("weekly-20261019-r2")).isEqualTo(List.of(a + ":2", b + ":1"));
        assertThat(readCount(failed.executionId())).isEqualTo(2);
        assertThat(ranking(WEEK, null).path("snapshotId").asText()).as("실패 중 기존 공개본 유지").isEqualTo("weekly-20261019-r1");

        // A 의 같은 사건을 저장 payload·key 그대로 실제 Kafka 에 다시 발행 → 중복 처리, 원본 불변
        RecordMetadata republished = republish(orderA);
        awaitHandled(orderA, "ALREADY_PROCESSED");
        assertThat(sourceRows(orderA)).isEqualTo(3);

        // 늦은 구매 B: a3, 같은 주의 다음 날 확정. 기존 r2 입력·stage 는 바뀌지 않는다
        long orderB = confirmOrder(Map.of(a, 3), B_CONFIRMED_AT);
        relayAndAwaitHandled(orderB, "APPLIED");
        assertThat(weekSourceRows()).isEqualTo(4);
        assertThat(manifest("weekly-20261019-r2")).isEqualTo(r2Manifest);
        assertThat(stage("weekly-20261019-r2")).isEqualTo(List.of(a + ":2", b + ":1"));

        // 최신 실패 실행 restart: 새 JVM·같은 DB/metadata, 같은 instance 의 새 execution 에서 c 부터
        Result restarted = WeeklyRankingBatchChild.restart(LOG_DIR, failed.executionId(), limit(BATCH));
        assertThat(List.of(restarted.exitCode(), restarted.status())).isEqualTo(List.of(0, "COMPLETED"));
        assertThat(restarted.instanceId()).isEqualTo(failed.instanceId());
        assertThat(restarted.executionId()).isNotEqualTo(failed.executionId());
        assertThat(restarted.processedProductIds()).containsExactly(c);
        assertThat(results("weekly-20261019-r2")).as("B 를 포함하지 않은 A 만의 결과").isEqualTo(expected);
        assertThat(manifest("weekly-20261019-r2").subList(2, 4)).isEqualTo(manifest("weekly-20261019-r1").subList(2, 4));
        JsonNode r2Api = ranking(WEEK, null);
        assertThat(r2Api.path("snapshotId").asText()).isEqualTo("weekly-20261019-r2");
        assertThat(items(r2Api)).isEqualTo(items(r1Api));

        // 새 revision r3: A+B 를 새 입력으로 계산·공개, r1/r2 는 보존
        Result r3 = WeeklyRankingBatchChild.startNew(LOG_DIR, WEEK.toString(), null, limit(BATCH));
        assertThat(List.of(r3.exitCode(), r3.status())).isEqualTo(List.of(0, "COMPLETED"));
        assertThat(manifest("weekly-20261019-r3").subList(0, 2)).isEqualTo(List.of("COMPLETED", 4L));
        assertThat(results("weekly-20261019-r3")).isEqualTo(List.of(a + ":5:1", c + ":2:2", b + ":1:3"));
        assertThat(results("weekly-20261019-r1")).isEqualTo(expected);
        assertThat(results("weekly-20261019-r2")).isEqualTo(expected);
        assertThat(ranking(WEEK, null).path("snapshotId").asText()).isEqualTo("weekly-20261019-r3");

        // 현재 c 삭제: 제외 후 limit, 저장 rank 유지
        products.deleteProduct(c);
        assertThat(items(ranking(WEEK, 2))).isEqualTo(List.of(a + ":5:1", b + ":1:3"));

        // 공개 없는 주는 NOT_PUBLISHED, 활동 없는 주를 계산하면 PUBLISHED·빈 items
        assertThat(ranking(EMPTY_WEEK, null).path("status").asText()).isEqualTo("NOT_PUBLISHED");
        Result empty = WeeklyRankingBatchChild.startNew(LOG_DIR, EMPTY_WEEK.toString(), null, limit(BATCH));
        assertThat(empty.exitCode()).isZero();
        JsonNode emptyApi = ranking(EMPTY_WEEK, null);
        assertThat(List.of(emptyApi.path("status").asText(), emptyApi.path("snapshotId").asText(), emptyApi.path("items").size()))
            .isEqualTo(List.of("PUBLISHED", "weekly-20261012-r1", 0));

        System.out.println("[" + LABEL + "] orders A=" + orderA + " B=" + orderB + " products a=" + a + " b=" + b + " c=" + c
            + " sentinel=" + SENTINEL_ORDER + " republished=" + republished.partition() + "@" + republished.offset()
            + " r1=" + r1.instanceId() + "/" + r1.executionId() + " r2 failed=" + failed.instanceId() + "/" + failed.executionId()
            + " restarted=" + restarted.instanceId() + "/" + restarted.executionId() + " r3=" + r3.instanceId() + "/" + r3.executionId()
            + " empty=" + empty.executionId() + " r1/r2 manifest=" + manifest("weekly-20261019-r1") + "/" + manifest("weekly-20261019-r2")
            + " r3 manifest=" + manifest("weekly-20261019-r3") + " totalMs=" + Duration.ofNanos(System.nanoTime() - STARTED).toMillis());
    }

    // ---------------------------------------------------------------- 준비·주문·전달

    private static void startStreamer() throws Exception {
        List<String> tables = fresh.queryForList(
            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()", String.class);
        OrderOutboxE2eLifecycleSupport.launchChild(OWNERSHIP, tables, new ChildLaunch() {
            @Override
            public void start() throws Exception {
                streamer = OrderActivityStreamerChild.start(kafka, topic, group, LABEL);
            }

            @Override
            public void awaitReady() {
                streamer.awaitReady(limit(OBSERVE));
            }
        });
        streamer.awaitAssignment(limit(OBSERVE));
    }

    /** 실제 API 로 주문하고 PG SUCCESS 로 확정한다. 확정 시각은 주문 확정 Clock 으로만 정한다. */
    private long confirmOrder(Map<Long, Integer> quantities, Instant confirmedAt) {
        CONFIRMATION_CLOCK.set(confirmedAt, ZoneOffset.UTC);
        Long user = api.getBean(UserFixture.class).createUserWithPoint().getId();
        List<Map<String, Object>> items = quantities.entrySet().stream()
            .map(entry -> Map.<String, Object>of("productId", entry.getKey(), "quantity", entry.getValue())).toList();
        ResponseEntity<JsonNode> created = post(user, "/api/v1/orders", Map.of("items", items));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        long order = created.getBody().path("data").path("id").asLong();
        pg.enqueuePlan(PgOperation.CREATE, PaymentModel.providerOrderIdOf(order), ResponsePlan.outcome(CreateOutcome.SUCCESS));
        ResponseEntity<JsonNode> confirmed = post(user, "/api/v1/orders/" + order + "/confirm", PgCommands.forOrder(order));
        assertThat(confirmed.getBody().path("data").path("status").asText()).isEqualTo("CONFIRMED");
        OrderActivityStreamerChild.await("PG 요청·콜백 작업 idle", limit(OBSERVE),
            () -> pg.state().activeRequests() == 0 && pg.state().activeCallbackJobs() == 0);
        assertThat(fresh.queryForObject("SELECT CAST(confirmed_at AS CHAR) FROM orders WHERE id = ?", String.class, order))
            .startsWith(confirmedAt.toString().replace("T", " ").replace("Z", ""));
        return order;
    }

    /** 자동 relay 가 꺼진 API 의 실제 relay 를 한 번 실행해 PUBLISHED 를 확인하고, streamer 자식의 처리 결과를 기다린다. */
    private static void relayAndAwaitHandled(long order, String result) {
        OrderOutboxRelay relay = new OrderOutboxRelay(api.getBean(OrderOutboxRelayTransactionService.class),
            api.getBean(OrderOutboxMessageVerifier.class), api.getBean(OrderOutboxSender.class), OrderOutboxRelayProbe.NO_OP,
            api.getBean(OrderOutboxExecutionGate.class), api.getBean(OrderOutboxClockConfig.OUTBOX_RECORD_CLOCK, Clock.class),
            api.getBean(OrderOutboxRelayProperties.class));
        assertThat(relay.runDueBatch().value().orElseThrow()).extracting(RowResult::outcome).containsExactly(RowOutcome.PUBLISHED);
        awaitHandled(order, result);
    }

    private static void awaitHandled(long order, String result) {
        String line = "eventId=order-confirmed:" + order + " result=" + result;
        OrderActivityStreamerChild.await("streamer 처리 " + line, limit(OBSERVE), () -> {
            assertThat(streamer.isAlive()).as("streamer 자식 생존").isTrue();
            return streamer.output().contains(line);
        });
    }

    /** Outbox 에 저장된 같은 key·payload 를 별도 producer 로 실제 topic 에 한 번 더 보낸다. 새 PG 주문은 만들지 않는다. */
    private static RecordMetadata republish(long order) throws Exception {
        Map<String, Object> outbox = fresh.queryForMap(
            "SELECT message_key, payload FROM order_confirmed_outbox WHERE order_id = ?", order);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers(),
            ProducerConfig.CLIENT_ID_CONFIG, "weekly-ranking-flow-republish-" + kafka.runId(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            return producer.send(new ProducerRecord<>(topic, (String) outbox.get("message_key"), (String) outbox.get("payload")))
                .get(OBSERVE.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private static ConfigurableApplicationContext bootApi() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        ConfigurableApplicationContext context = new SpringApplicationBuilder(CommerceApiApplication.class).profiles("test")
            .initializers(ctx -> ((BeanDefinitionRegistry) ctx.getBeanFactory()).registerBeanDefinition(
                "weeklyRankingFlowClockReplacer", new RootBeanDefinition(ClockReplacer.class, ClockReplacer::new)))
            .run("--server.port=" + port, "--management.server.port=0", "--spring.jpa.hibernate.ddl-auto=create",
                "--commerce.pg.base-url=" + pg.baseUrl(),
                "--commerce.pg.callback-url=http://127.0.0.1:" + port + "/api/v1/payments/callback",
                "--spring.kafka.bootstrap-servers=" + kafka.bootstrapServers(),
                "--spring.kafka.admin.properties.bootstrap.servers=" + kafka.bootstrapServers(),
                "--commerce.order-events.topic=" + topic,
                "--commerce.order-events.relay.enabled=false",
                "--commerce.order-events.producer-properties[client.id]=weekly-ranking-flow-" + kafka.runId());
        assertThat(context.getBean(OrderConfirmationClockConfig.ORDER_CONFIRMATION_CLOCK, Clock.class)).isSameAs(CONFIRMATION_CLOCK);
        assertThat(context.getBean(OrderOutboxRelayProperties.class).enabled()).as("자동 relay OFF").isFalse();
        return context;
    }

    /** 주문 확정 Clock 만 고정 Clock 으로 바꾼다. Outbox·감사·Kafka 시각은 실제 시간이다. */
    private static final class ClockReplacer implements BeanDefinitionRegistryPostProcessor {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            String name = OrderConfirmationClockConfig.ORDER_CONFIRMATION_CLOCK;
            registry.removeBeanDefinition(name);
            registry.registerBeanDefinition(name, new RootBeanDefinition(Clock.class, () -> CONFIRMATION_CLOCK));
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        }
    }

    // ---------------------------------------------------------------- 관찰

    private JsonNode ranking(LocalDate week, Integer limit) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", viewer.toString());
        String query = "?periodStart=" + week + (limit == null ? "" : "&limit=" + limit);
        ResponseEntity<JsonNode> response = http.exchange(baseUrl() + "/api/v1/rankings/weekly" + query, HttpMethod.GET,
            new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody().path("data");
    }

    /** API items 의 "productId:score:rank". */
    private static List<String> items(JsonNode data) {
        return StreamSupport.stream(data.path("items").spliterator(), false)
            .map(item -> item.path("productId").asLong() + ":" + item.path("score").asText() + ":" + item.path("rank").asLong())
            .toList();
    }

    private static List<String> results(String snapshotId) {
        return fresh.query("SELECT product_id, score, calculated_rank FROM ranking_result WHERE snapshot_id = ? "
            + "ORDER BY calculated_rank", (rs, n) -> rs.getLong(1) + ":" + rs.getBigDecimal(2).toBigInteger() + ":" + rs.getLong(3),
            snapshotId);
    }

    private static List<String> stage(String snapshotId) {
        return fresh.query("SELECT product_id, score FROM ranking_score_stage WHERE snapshot_id = ? ORDER BY product_id",
            (rs, n) -> rs.getLong(1) + ":" + rs.getBigDecimal(2).toBigInteger(), snapshotId);
    }

    /** status·input_count·input_checksum·result_checksum. */
    private static List<Object> manifest(String snapshotId) {
        return fresh.queryForObject("SELECT status, input_count, input_checksum, result_checksum FROM ranking_snapshot "
                + "WHERE snapshot_id = ?",
            (rs, n) -> Arrays.asList(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)), snapshotId);
    }

    private static long sourceRows(long order) {
        return fresh.queryForObject("SELECT COUNT(*) FROM product_purchase_activity WHERE order_id = ?", Long.class, order);
    }

    private static long weekSourceRows() {
        return fresh.queryForObject("SELECT COUNT(*) FROM product_purchase_activity WHERE confirmed_at >= ? AND confirmed_at < ? "
            + "AND order_id <> ?", Long.class, WEEK.atStartOfDay(), WEEK.plusDays(7).atStartOfDay(), SENTINEL_ORDER);
    }

    private static long weeklyRankingInstances() {
        return fresh.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_INSTANCE WHERE JOB_NAME = 'weeklyRanking'", Long.class);
    }

    /**
     * 실패 실행의 계산 Step 에 영속된 reader 위치. Batch 5.2 기본 직렬화(Base64 + Java 직렬화)를 읽기 전용으로 되돌리며,
     * java.* 타입만 허용한다. 부모 API 테스트는 Batch 를 의존하지 않으므로 JobExplorer 대신 이 방식으로 확인한다.
     */
    @SuppressWarnings("unchecked")
    private static int readCount(long jobExecutionId) throws Exception {
        String context = fresh.queryForObject("SELECT c.SHORT_CONTEXT FROM BATCH_STEP_EXECUTION_CONTEXT c "
            + "JOIN BATCH_STEP_EXECUTION s ON s.STEP_EXECUTION_ID = c.STEP_EXECUTION_ID "
            + "WHERE s.JOB_EXECUTION_ID = ? AND s.STEP_NAME = 'calculateWeeklyScores'", String.class, jobExecutionId);
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(context)))) {
            in.setObjectInputFilter(ObjectInputFilter.Config.createFilter("java.**;!*"));
            return (Integer) ((Map<String, Object>) in.readObject()).get(READ_COUNT_KEY);
        }
    }

    private ResponseEntity<JsonNode> post(Long user, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", user.toString());
        return http.exchange(baseUrl() + path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static String baseUrl() {
        return "http://127.0.0.1:" + ((ServletWebServerApplicationContext) api).getWebServer().getPort();
    }

    private static Duration limit(Duration stage) {
        Duration remaining = TOTAL.minusNanos(System.nanoTime() - STARTED);
        assertThat(remaining.isNegative()).as("전체 420초 예산 초과").isFalse();
        return remaining.compareTo(stage) < 0 ? remaining : stage;
    }

    private static RestTemplate client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return new RestTemplate(factory);
    }
}
