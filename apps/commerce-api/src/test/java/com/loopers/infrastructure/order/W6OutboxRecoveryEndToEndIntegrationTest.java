package com.loopers.infrastructure.order;

import com.loopers.domain.coupon.CouponCacheRepository;
import com.loopers.CommerceApiApplication;
import com.loopers.application.order.OrderConfirmedRecorder;
import com.loopers.application.order.OrderOutboxExecutionGate;
import com.loopers.application.order.OrderOutboxOperations;
import com.loopers.application.order.OrderOutboxOperations.RecoveryItem;
import com.loopers.application.order.OrderOutboxOperations.RecoveryOutcome;
import com.loopers.application.order.OrderOutboxOperations.RecoveryReport;
import com.loopers.application.order.OrderOutboxRecordingService;
import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OrderOutboxRelay.RowOutcome;
import com.loopers.application.order.OrderOutboxRelay.RowResult;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.fixture.pg.PgFixtureServer;
import com.loopers.fixture.pg.RequestCounts;
import com.loopers.fixture.pg.TransactionSnapshot;
import com.loopers.infrastructure.order.OrderEventEndToEndIntegrationTest.C10Cases;
import com.loopers.infrastructure.order.OrderOutboxE2eLifecycleSupport.CleanupSteps;
import com.loopers.infrastructure.order.OrderOutboxE2eLifecycleSupport.Ownership;
import com.loopers.infrastructure.order.support.CouponBeanIsolation;
import com.loopers.infrastructure.order.support.W6PreparationContextIntegrationTest;
import com.loopers.infrastructure.order.support.W7CouponIsolationInitializer;
import com.loopers.testsupport.LocalKafkaTestSupport;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * O11 W6 준비 상태의 실제 W7 복구 E2E(설계 W7.4-1·W7.4-2·W7.3-6). 전용 Gradle 작업 w6OutboxRecoveryTest 의 새 worker JVM 에서만 실행한다.
 *
 * <p>한 실행이 자기 MySQL/Redis fixture·PG fixture·실행 소유 topic/group·streamer 자식 JVM 하나를 끝까지 소유한다.
 * <ol>
 *   <li>빈 스키마 확인 → C10 의 W6 준비 API(12 Entity, create) → 같은 DB 의 실제 streamer → C10 의 A(미발행)·B(소비) 준비와 기존 C10 기대 전부.</li>
 *   <li>A/B 업무·소비 행·PG 기준·기존 DDL 고정 → W6 API 만 close → 정상 W7 API(update, 자동 relay OFF) → Outbox 만 추가됐는지 대조.</li>
 *   <li>recoverOrders([A, B]) → 새 relay 실행 → A 는 APPLIED, B 는 다른 offset 의 ALREADY_PROCESSED → 두 번째 복구는 EXISTS·전체 불변.</li>
 * </ol>
 * A/B 성공 상태를 SQL 로 만들거나 Outbox 를 지워 누락을 흉내 내지 않는다. 정리는 05a 의 소유권·writer 종료 판단(release)을 그대로 쓴다.
 */
@Timeout(300)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("O11: W6 로 준비한 미발행 A·소비된 B 를 같은 DB 의 W7 로 바꾼 뒤 명시 복구하면 A 는 처음 소비되고 B 는 중복으로 수렴하며, 다시 복구해도 바뀌지 않는다.")
class W6OutboxRecoveryEndToEndIntegrationTest {

    private static final String LABEL = "O11";
    private static final Duration OBSERVE = Duration.ofSeconds(60);
    private static final Set<String> PURCHASE_TABLES = Set.of("processed_order_confirmation", "product_purchase_activity");
    private static final String OUTBOX = "order_confirmed_outbox";
    private static final String CONFIRMED_AT_SQL = "2026-10-29 01:02:03.123456";
    private static final Ownership OWNERSHIP = new Ownership();

    private static LocalKafkaTestSupport kafka;
    private static PgFixtureServer pg;
    private static JdbcTemplate fresh;
    private static String topic;
    private static String group;
    private static ConfigurableApplicationContext w6Api;
    private static ConfigurableApplicationContext w7Api;

    @BeforeAll
    static void prepare() throws Exception {
        // 이 worker 가 fixture 를 처음 초기화하는지 본다. 이미 속성이 있으면 다른 테스트와 JVM 을 공유한 것이다.
        String inheritedUrl = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        Class.forName("com.loopers.testcontainers.MySqlTestContainersConfig");
        Class.forName("com.loopers.testcontainers.RedisTestContainersConfig");
        String url = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        fresh = new JdbcTemplate(new DriverManagerDataSource(url, System.getProperty("datasource.mysql-jpa.main.username"),
            System.getProperty("datasource.mysql-jpa.main.password")));
        List<String> tablesAtStart = tableNames();
        System.out.println("[O11 WORKER] pid=" + ProcessHandle.current().pid() + " inheritedUrl=" + inheritedUrl + " url=" + url
            + " mysqlHost=" + fresh.queryForObject("SELECT @@hostname", String.class) + " tablesAtStart=" + tablesAtStart);
        assertAll(
            () -> assertThat(inheritedUrl).as("새 worker 에서 이 실행의 fixture 가 처음 초기화됨").isNull(),
            () -> assertThat(url).as("Testcontainers fixture 의 loopers DB").endsWith("/loopers").doesNotContain(":3306/"),
            () -> assertThat(tablesAtStart).as("API·streamer 기동 전 빈 스키마(지워서 꾸미지 않음)").isEmpty()
        );

        kafka = LocalKafkaTestSupport.connect();
        System.out.println("[O11 KAFKA] bootstrap=" + kafka.bootstrapServers() + " runId=" + kafka.runId() + " broker=" + kafka.brokerInfo());
        topic = kafka.createTopic("order-events-o11");
        group = kafka.ownGroup("purchase-activity-o11");
        pg = PgFixtureServer.start();
        OrderEventEndToEndIntegrationTest.attach(kafka, topic, group, pg, fresh);
        w6Api = OrderEventEndToEndIntegrationTest.bootW6Api();
    }

    /** 05a 의 판단: relay idle·child 실제 종료가 확인돼야 TRUNCATE/DROP, child 종료가 확인돼야 topic/group 삭제. 남긴 자원은 보고한다. */
    @AfterAll
    static void releaseResources() {
        OrderOutboxE2eLifecycleSupport.release(OWNERSHIP, new CleanupSteps() {
            @Override
            public void stopRelay() throws InterruptedException {
                if (w7Api != null && w7Api.isActive()) {
                    OrderOutboxExecutionGate gate = w7Api.getBean(OrderOutboxExecutionGate.class);
                    gate.stop();
                    if (!gate.awaitIdle(Duration.ofSeconds(10))) {
                        throw new IllegalStateException("진행 중인 relay 가 10초 안에 끝나지 않았습니다.");
                    }
                }
            }

            @Override
            public void restoreTopicConfig() {
                // O11 은 topic 설정을 바꾸지 않는다.
            }

            @Override
            public void stopChild() throws InterruptedException {
                OrderActivityStreamerChild streamer = OrderEventEndToEndIntegrationTest.streamer();
                if (streamer != null) {
                    streamer.stop(LABEL, OBSERVE);
                }
            }

            @Override
            public boolean childExited() {
                OrderActivityStreamerChild streamer = OrderEventEndToEndIntegrationTest.streamer();
                return streamer == null || !streamer.isAlive();
            }

            /** 살아 있는 API context 의 cleanup 을 쓰고, 둘 다 닫혔으면 유지한 테스트 접속 정보로 API 테이블만 비운다(Redis 는 JVM 소유 fixture). */
            @Override
            public void truncateApiData() {
                ConfigurableApplicationContext active = w7Api != null && w7Api.isActive() ? w7Api
                    : w6Api != null && w6Api.isActive() ? w6Api : null;
                if (active != null) {
                    active.getBean(DatabaseCleanUp.class).truncateAllTables();
                    active.getBean(RedisCleanUp.class).truncateAll();
                    return;
                }
                if (fresh == null) {
                    return;
                }
                List<String> apiTables = tableNames().stream().filter(table -> !PURCHASE_TABLES.contains(table)).toList();
                try (SingleConnectionDataSource single = new SingleConnectionDataSource(System.getProperty("datasource.mysql-jpa.main.jdbc-url"),
                    System.getProperty("datasource.mysql-jpa.main.username"), System.getProperty("datasource.mysql-jpa.main.password"), true)) {
                    JdbcTemplate jdbc = new JdbcTemplate(single);
                    jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                    apiTables.forEach(table -> jdbc.execute("TRUNCATE TABLE `" + table + "`"));
                    jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
                }
            }

            @Override
            public void closeApi() {
                try {
                    if (w7Api != null) {
                        w7Api.close();
                    }
                } finally {
                    if (w6Api != null) {
                        w6Api.close();
                    }
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
                PURCHASE_TABLES.forEach(table -> fresh.execute("DROP TABLE IF EXISTS " + table));
            }

            @Override
            public void closeTopicAdmin() {
                // O11 은 별도 관리 client 를 만들지 않는다.
            }

            @Override
            public void closeKafka() {
                if (kafka != null) {
                    kafka.close();
                }
            }
        });
    }

    @DisplayName("W6 준비(C10 기대 전부) → W6 close → 같은 DB 의 W7 update → 명시 복구·전달 → 두 번째 복구 불변")
    @Test
    void recoversW6PreparedEvents(CapturedOutput output) throws Exception {
        // 1. W6 준비 조립: 12 Entity·W6 sole recorder/listener/JSON adapter·W7 bean 부재, Outbox 테이블 없음.
        W6PreparationContextIntegrationTest.assertAssembly(w6Api, topic);
        assertThat(tableNames()).as("W6 준비 API 의 12개 테이블, Outbox 없음").hasSize(12).doesNotContain(OUTBOX);

        // 2. 같은 DB 의 streamer 자식과 C10 의 A·B 준비. 기존 C10 기대를 모두 복구 전에 실행한다.
        OrderEventEndToEndIntegrationTest.startStreamerAndAssertReady(OWNERSHIP);
        C10Cases cases = new OrderEventEndToEndIntegrationTest().reproduceUnpublishedAndConsumedEvents(output);
        Long a = cases.unpublished();
        Long b = cases.consumed();
        OrderActivityStreamerChild.await("PG 요청·콜백 작업 idle", OBSERVE,
            () -> pg.state().activeRequests() == 0 && pg.state().activeCallbackJobs() == 0);

        // 3. 전환 기준: 기존 14개 테이블의 정의·전체 행, PG 카운터·거래.
        Map<String, String> ddlBefore = createTables();
        Map<String, List<Map<String, Object>>> rowsBefore = allRows(ddlBefore.keySet());
        RequestCounts pgCounts = pg.state().counts();
        List<TransactionSnapshot> pgTransactions = pg.state().transactions();
        assertAll(
            () -> assertThat(ddlBefore.keySet()).as("API 12 + streamer 2").hasSize(14).doesNotContain(OUTBOX).containsAll(PURCHASE_TABLES),
            () -> assertThat(rowsBefore.get("processed_order_confirmation")).as("B 만 소비됨").hasSize(1)
        );

        // 4. W6 API 만 정상 close(데이터·PG·child·topic 은 유지) → 정상 W7 API(update, 자동 relay OFF).
        w6Api.close();
        w7Api = bootW7Api();
        assertW7Assembly(w7Api);
        Map<String, String> ddlAfter = createTables();
        assertAll(
            () -> assertThat(ddlAfter.keySet()).as("Outbox 만 추가").containsExactlyInAnyOrderElementsOf(
                java.util.stream.Stream.concat(ddlBefore.keySet().stream(), java.util.stream.Stream.of(OUTBOX)).toList()),
            () -> assertThat(withoutOutbox(ddlAfter)).as("기존 14개 테이블 정의 불변(SHOW CREATE TABLE)").isEqualTo(ddlBefore),
            () -> assertThat(allRows(ddlBefore.keySet())).as("기존 14개 테이블 전체 행 불변").isEqualTo(rowsBefore),
            () -> assertThat(fresh.queryForObject("SELECT COUNT(*) FROM " + OUTBOX, Long.class)).isZero(),
            () -> OrderConfirmedOutboxSchemaIntegrationTest.assertColumnsMatchDesignTable(fresh),
            () -> OrderConfirmedOutboxSchemaIntegrationTest.assertNamedConstraintsOnly(fresh),
            () -> assertPgUnchanged(pgCounts, pgTransactions)
        );

        // 5. 명시 복구 → 새 relay 실행 흐름으로 전달.
        OrderOutboxOperations operations = w7Api.getBean(OrderOutboxOperations.class);
        RecoveryReport first = operations.recoverOrders(List.of(b, a)).value().orElseThrow();
        Map<Long, Map<String, Object>> outboxRestored = Map.of(a, outboxRow(a), b, outboxRow(b));
        Map<TopicPartition, Long> start = kafka.endOffsets(topic);
        List<RowResult> delivered = w7Api.getBean(OrderOutboxRelay.class).runDueBatch().value().orElseThrow();
        ConsumerRecord<String, String> recordA = singleRecord(start, a);
        ConsumerRecord<String, String> recordB = singleRecord(start, b);
        awaitHandled(recordA, a, "APPLIED");
        awaitHandled(recordB, b, "ALREADY_PROCESSED");
        awaitCommitted(recordA);
        awaitCommitted(recordB);
        List<List<Long>> itemsA = storedItems(a);
        assertAll(
            () -> assertThat(first.items()).containsExactly(
                new RecoveryItem(a, "order-confirmed:" + a, RecoveryOutcome.RESTORED, OrderOutboxStatus.PENDING),
                new RecoveryItem(b, "order-confirmed:" + b, RecoveryOutcome.RESTORED, OrderOutboxStatus.PENDING)),
            () -> assertThat(outboxRestored.values()).allSatisfy(row -> assertThat(row).containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 0L).containsEntry("last_error", null)),
            () -> assertThat(delivered).extracting(RowResult::outcome).containsOnly(RowOutcome.PUBLISHED).hasSize(2),
            () -> assertThat(recordA.value()).isEqualTo(outboxRestored.get(a).get("payload")),
            () -> assertThat(recordB.value()).isEqualTo(outboxRestored.get(b).get("payload")),
            () -> assertThat(List.<Object>of(recordB.partition(), recordB.offset())).as("B 의 최초 record 와 다른 실제 위치")
                .isNotEqualTo(List.<Object>of(cases.consumedRecord().partition(), cases.consumedRecord().offset())),
            () -> assertThat(rows("processed_order_confirmation", a)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("event_id", "order-confirmed:" + a).containsEntry("item_count", itemsA.size())),
            () -> assertThat(fresh.query("SELECT product_id, quantity, CAST(confirmed_at AS CHAR) FROM product_purchase_activity WHERE order_id = ?"
                    + " ORDER BY product_id", (row, index) -> List.<Object>of(row.getLong(1), row.getLong(2), row.getString(3)), a))
                .as("A 의 전체 품목 활동(고정 확정 시각)").isEqualTo(itemsA.stream()
                    .map(item -> List.<Object>of(item.get(0), item.get(1), CONFIRMED_AT_SQL)).toList()),
            () -> assertThat(rows("processed_order_confirmation", b)).as("B 완료 행 전체 불변")
                .isEqualTo(rowsOf(rowsBefore, "processed_order_confirmation", b)),
            () -> assertThat(rows("product_purchase_activity", b)).as("B 활동 행 전체 불변(삭제 상품 포함)")
                .isEqualTo(rowsOf(rowsBefore, "product_purchase_activity", b)),
            () -> assertThat(rows("product_purchase_activity", b)).extracting(row -> ((Number) row.get("product_id")).longValue())
                .contains(cases.deletedProduct()),
            () -> assertThat(outboxRow(a)).containsEntry("status", "PUBLISHED"),
            () -> assertThat(outboxRow(b)).containsEntry("status", "PUBLISHED"),
            () -> assertThat(allRows(businessTables(ddlBefore.keySet()))).as("업무 12개 테이블 전체 행 불변")
                .isEqualTo(subset(rowsBefore, businessTables(ddlBefore.keySet()))),
            () -> assertPgUnchanged(pgCounts, pgTransactions)
        );

        // 6. 두 번째 복구는 EXISTS 이며 Outbox·구매·업무·PG 전체가 그대로다.
        Map<String, Object> outboxA = outboxRow(a);
        Map<String, Object> outboxB = outboxRow(b);
        Map<String, List<Map<String, Object>>> purchaseAfter = allRows(PURCHASE_TABLES);
        RecoveryReport second = operations.recoverOrders(List.of(a, b)).value().orElseThrow();
        assertAll(
            () -> assertThat(second.items()).containsExactly(
                new RecoveryItem(a, "order-confirmed:" + a, RecoveryOutcome.EXISTS, OrderOutboxStatus.PUBLISHED),
                new RecoveryItem(b, "order-confirmed:" + b, RecoveryOutcome.EXISTS, OrderOutboxStatus.PUBLISHED)),
            () -> assertThat(outboxRow(a)).as("A Outbox 전체 열·감사 시각 불변").isEqualTo(outboxA),
            () -> assertThat(outboxRow(b)).isEqualTo(outboxB),
            () -> assertThat(allRows(PURCHASE_TABLES)).isEqualTo(purchaseAfter),
            () -> assertThat(allRows(businessTables(ddlBefore.keySet()))).isEqualTo(subset(rowsBefore, businessTables(ddlBefore.keySet()))),
            () -> assertPgUnchanged(pgCounts, pgTransactions),
            () -> assertThat(OrderEventEndToEndIntegrationTest.streamer().output()).doesNotContain("ORDER_ACTIVITY_FAILURE")
        );
        System.out.println("[O11 RESULT] A=" + a + " B=" + b + " recordA=" + recordA.partition() + "@" + recordA.offset()
            + " recordB=" + recordB.partition() + "@" + recordB.offset() + " bFirst=" + cases.consumedRecord().partition() + "@"
            + cases.consumedRecord().offset() + " committed=" + kafka.committedOffsets(group) + " pg=" + pgCounts
            + " tablesAfter=" + ddlAfter.keySet());
    }

    // ---------------------------------------------------------------- W7 context

    /**
     * 정상 W7 API. 같은 DB/Redis/PG/topic, ddl-auto=update, 자동 relay OFF. 새 포트와 callback URL 은 기동 전에 함께 주입한다.
     * W7 이후 추가된 쿠폰 관리 타입·bean 만 {@link W7CouponIsolationInitializer} 로 빼서 전환이 Outbox 하나만 추가하는지 본다.
     */
    private static ConfigurableApplicationContext bootW7Api() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        ConfigurableApplicationContext context = new SpringApplicationBuilder(CommerceApiApplication.class).profiles("test")
            .initializers(new W7CouponIsolationInitializer())
            .run("--server.port=" + port, "--management.server.port=0", "--spring.jpa.hibernate.ddl-auto=update",
                "--commerce.pg.base-url=" + pg.baseUrl(),
                "--commerce.pg.callback-url=http://127.0.0.1:" + port + "/api/v1/payments/callback",
                "--spring.kafka.bootstrap-servers=" + kafka.bootstrapServers(),
                "--spring.kafka.admin.properties.bootstrap.servers=" + kafka.bootstrapServers(),
                "--commerce.order-events.topic=" + topic,
                "--commerce.order-events.relay.enabled=false",
                "--commerce.order-events.producer-properties[client.id]=order-outbox-o11-" + kafka.runId());
        assertThat(((ServletWebServerApplicationContext) context).getWebServer().getPort()).isEqualTo(port);
        return context;
    }

    private static void assertW7Assembly(ConfigurableApplicationContext context) {
        assertAll(
            () -> assertThat(context.getBeansOfType(OrderConfirmedRecorder.class)).containsOnlyKeys("orderOutboxRecordingService"),
            () -> assertThat(AopUtils.getTargetClass(context.getBean(OrderConfirmedRecorder.class))).isEqualTo(OrderOutboxRecordingService.class),
            () -> assertThat(context.containsBean("orderConfirmedEventListener")).as("W6 listener 없음").isFalse(),
            () -> assertThat(context.containsBean("kafkaOrderEventPublisher")).as("W6 adapter 없음").isFalse(),
            () -> assertThat(context.containsBean("orderEventKafkaTemplate")).isFalse(),
            () -> assertThat(context.getEnvironment().getProperty("commerce.order-events.relay.enabled")).isEqualTo("false"),
            () -> assertThat(context.getBean(jakarta.persistence.EntityManagerFactory.class).getMetamodel().getManagedTypes().stream()
                .map(type -> type.getJavaType().getName()).collect(Collectors.toSet()))
                .as("W6 관리 타입 + Outbox").isEqualTo(W7CouponIsolationInitializer.MANAGED_TYPES),
            () -> assertThat(CouponBeanIsolation.COUPON_DEFINITIONS).allSatisfy(name ->
                assertThat(context.containsBean(name)).as("쿠폰 bean 없음: " + name).isFalse()),
            () -> assertThat(context.getBeanNamesForType(CouponCacheRepository.class)).as("실제·test 대역 캐시 저장소 모두 없음").isEmpty()
        );
    }

    // ---------------------------------------------------------------- broker·child

    private static ConsumerRecord<String, String> singleRecord(Map<TopicPartition, Long> start, Long order) {
        List<ConsumerRecord<String, String>> records = kafka.readRange(start, kafka.endOffsets(topic)).stream()
            .filter(record -> record.key().equals(order.toString())).toList();
        assertThat(records).as("복구 뒤 주문 " + order + " 의 record 하나").hasSize(1);
        return records.get(0);
    }

    private static void awaitHandled(ConsumerRecord<String, String> record, Long order, String result) {
        String line = "ORDER_ACTIVITY_HANDLED_AND_ACK_RETURNED topic=" + record.topic() + " partition=" + record.partition()
            + " offset=" + record.offset() + " eventId=order-confirmed:" + order + " result=" + result;
        OrderActivityStreamerChild.await("child 처리 " + record.partition() + "@" + record.offset() + " " + result, OBSERVE,
            () -> OrderEventEndToEndIntegrationTest.streamer().output().contains(line));
    }

    private static void awaitCommitted(ConsumerRecord<String, String> record) {
        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
        OrderActivityStreamerChild.await("committed offset " + record.partition() + "@" + record.offset() + "+1", OBSERVE,
            () -> kafka.committedOffsets(group).getOrDefault(partition, -1L) >= record.offset() + 1);
    }

    // ---------------------------------------------------------------- 새 SQL

    private static List<String> tableNames() {
        return fresh.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME",
            String.class);
    }

    private static Map<String, String> createTables() {
        Map<String, String> ddl = new TreeMap<>();
        tableNames().forEach(table -> ddl.put(table, (String) fresh.queryForMap("SHOW CREATE TABLE `" + table + "`").get("Create Table")));
        return ddl;
    }

    private static Map<String, String> withoutOutbox(Map<String, String> ddl) {
        Map<String, String> copy = new TreeMap<>(ddl);
        copy.remove(OUTBOX);
        return copy;
    }

    private static Map<String, List<Map<String, Object>>> allRows(Set<String> tables) {
        Map<String, List<Map<String, Object>>> rows = new TreeMap<>();
        tables.forEach(table -> rows.put(table, fresh.queryForList("SELECT * FROM `" + table + "` ORDER BY id")));
        return rows;
    }

    private static Set<String> businessTables(Set<String> tables) {
        return tables.stream().filter(table -> !PURCHASE_TABLES.contains(table)).collect(Collectors.toSet());
    }

    private static Map<String, List<Map<String, Object>>> subset(Map<String, List<Map<String, Object>>> rows, Set<String> tables) {
        Map<String, List<Map<String, Object>>> copy = new TreeMap<>(rows);
        copy.keySet().retainAll(tables);
        return copy;
    }

    private static List<Map<String, Object>> rows(String table, Long order) {
        return fresh.queryForList("SELECT * FROM `" + table + "` WHERE order_id = ? ORDER BY id", order);
    }

    private static List<Map<String, Object>> rowsOf(Map<String, List<Map<String, Object>>> rows, String table, Long order) {
        return rows.get(table).stream().filter(row -> ((Number) row.get("order_id")).longValue() == order).toList();
    }

    private static Map<String, Object> outboxRow(Long order) {
        return fresh.queryForMap("SELECT * FROM " + OUTBOX + " WHERE order_id = ?", order);
    }

    private static List<List<Long>> storedItems(Long order) {
        return fresh.query("SELECT product_id, SUM(quantity) AS quantity FROM order_item WHERE order_id = ? GROUP BY product_id "
            + "ORDER BY product_id", (row, index) -> List.of(row.getLong("product_id"), row.getLong("quantity")), order);
    }

    /** API close·전환·복구·relay 전 과정에서 PG 요청·콜백이 늘지 않고 거래도 그대로다. */
    private static void assertPgUnchanged(RequestCounts counts, List<TransactionSnapshot> transactions) {
        assertThat(pg.state().counts()).as("PG create·lookup·callback 증가 0").isEqualTo(counts);
        assertThat(pg.state().transactions()).as("PG 거래 불변").isEqualTo(transactions);
    }
}
