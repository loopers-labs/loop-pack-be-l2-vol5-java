package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.support.order.SpringAppProcess;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.testcontainers.KafkaTestBroker;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 새 격리 MySQL·Kafka·PG에서 실제 W6 revision 실행물로 누락 A와 소비 완료 B를 만들고, 같은 DB에 현재 코드를 연결해 복구한다.
 * A는 W6 앱이 commit 뒤 send의 metadata 대기 중일 때 SIGKILL해서 만든다. 자원은 이 테스트가 시작하고 모두 끝난 뒤 정리한다.
 * 준비: {@code ./scripts/build-w6-revision.sh}
 * 실행: {@code ./gradlew :apps:commerce-api:experimentTest --tests '*W6OutboxRecoveryExperimentTest'}
 */
@Tag("experiment")
class W6OutboxRecoveryExperimentTest {
    private static final String TOPIC = "order-events-w6-recovery";
    private static final String GROUP = "purchase-activity";
    private static final int PARTITIONS = 3;
    private static final String UNREACHABLE_BROKER = "127.0.0.1:1";
    private static final Duration APP_DEADLINE = Duration.ofSeconds(120);
    private static final Duration RESULT_DEADLINE = Duration.ofSeconds(30);
    private static final ZoneOffset STORED_ZONE = ZoneOffset.UTC;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<SpringAppProcess> apps = new ArrayList<>();
    private int portInUse;
    private String apiUrl;
    private Connection db;
    private AdminClient admin;
    private KafkaTestBroker broker;

    @Test
    void recoversMissedEventAndKeepsConsumedEventWithoutPaymentEffects() throws Exception {
        try (var mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0")).withDatabaseName("w6_recovery");
             var ownedBroker = new KafkaTestBroker();
             var pg = new FakePaymentServer()) {
            mysql.start();
            broker = ownedBroker;
            var database = new SpringAppProcess.Database(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            try (Connection connection = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                 AdminClient kafkaAdmin = AdminClient.create(Map.of(
                     AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, ownedBroker.bootstrapServers()))) {
                db = connection;
                admin = kafkaAdmin;
                try {
                    run(database, ownedBroker.bootstrapServers(), pg);
                } finally {
                    for (SpringAppProcess app : apps.reversed()) {
                        app.close();
                    }
                }
            }
        }
    }

    private void run(SpringAppProcess.Database database, String bootstrap, FakePaymentServer pg) throws Exception {
        admin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS, (short) 1))).all().get(10, TimeUnit.SECONDS);
        SpringAppProcess streamer = started(SpringAppProcess.streamer(database, bootstrap, TOPIC));
        await("consumer partition 배정", APP_DEADLINE, streamer, this::consumerAssigned);

        // 준비 1: 브로커에 닿지 않는 W6 앱으로 주문 A를 확정한다. send의 metadata 대기 중 프로세스를 강제 종료한다.
        SpringAppProcess w6 = startApi(() -> SpringAppProcess.w6Api(database, UNREACHABLE_BROKER, TOPIC, port(), pg.baseUrl()));
        assertThat(tableExists("order_outbox")).as("W6 스키마에는 Outbox가 없다").isFalse();
        Long userId = seedUser();
        Long brandId = post("/api-admin/v1/brands", null, Map.of("name", "복구 브랜드", "description", "합성"), 201)
            .path("data").path("id").asLong();
        Long socks = createProduct(brandId, "양말", 1_000L);
        Long cap = createProduct(brandId, "모자", 2_000L);

        Long orderA = createOrder(userId, List.of(Map.of("productId", socks, "quantity", 2), Map.of("productId", cap, "quantity", 1)));
        startPayment(userId, orderA, "w6-case-a");
        pg.changeStatus("TEST-TX-1", "SUCCESS");
        CompletableFuture<HttpResponse<String>> stoppedReconcile = http.sendAsync(
            request("/api/v1/orders/" + orderA + "/payment/reconcile", userId, "{}"), HttpResponse.BodyHandlers.ofString());
        await("주문 A 확정 commit", RESULT_DEADLINE, w6, () -> "CONFIRMED".equals(orderFacts(orderA).get("status")));
        assertThat(stoppedReconcile).as("after-commit send가 끝나기 전에 멈춰야 한다").isNotDone();
        w6.kill();

        // 준비 2: 브로커에 연결된 W6 앱으로 주문 B를 실제 발행·소비까지 끝낸다.
        w6 = startApi(() -> SpringAppProcess.w6Api(database, bootstrap, TOPIC, port(), pg.baseUrl()));
        Long orderB = createOrder(userId, List.of(Map.of("productId", socks, "quantity", 1)));
        startPayment(userId, orderB, "w6-case-b");
        pg.changeStatus("TEST-TX-2", "SUCCESS");
        post("/api/v1/orders/" + orderB + "/payment/reconcile", userId, Map.of(), 200);
        await("주문 B 구매 활동 저장", RESULT_DEADLINE, streamer, () -> activityRows(orderB).size() == 1);
        w6.close();
        apps.remove(w6);

        Map<String, Object> factsA = orderFacts(orderA);
        Map<String, Object> factsB = orderFacts(orderB);
        assertThat(factsA).containsEntry("status", "CONFIRMED").containsEntry("reservation", "CONSUMED");
        assertThat(factsB).containsEntry("status", "CONFIRMED").containsEntry("reservation", "CONSUMED");
        assertThat(tableExists("order_outbox")).isFalse();
        assertThat(activityRows(orderA)).isEmpty();
        assertThat(allRecords()).extracting(ConsumerRecord::key).containsExactly(orderB.toString());
        List<ConsumerRecord<String, String>> beforeRecovery = allRecords();
        List<Map<String, Object>> activitiesB = activityRows(orderB);
        Map<Long, Integer> stock = stocks(socks, cap);
        int createCalls = pg.createCalls();
        int transactions = pg.transactionCount();
        int lookups = pg.lookupCalls();
        System.out.println("W7_W6_RECOVERY_PREPARED " + MAPPER.writeValueAsString(Map.of("A", factsA, "B", factsB,
            "pg", Map.of("create", createCalls, "transactions", transactions, "lookups", lookups))));

        // 복구: 직접 발행이 없는 현재 코드를 같은 DB에 연결한다. 대상 두 주문의 Outbox를 저장된 사실로 보충하고 relay가 보낸다.
        List<String> recoveryArguments = List.of("--commerce.order-events.relay.scheduling-enabled=true",
            "--commerce.order-events.recovery.order-ids=" + orderA + "," + orderB);
        SpringAppProcess current = startApi(() -> SpringAppProcess.currentApi(database, bootstrap, TOPIC, port(), pg.baseUrl(), recoveryArguments));
        await("두 주문의 Outbox PUBLISHED", RESULT_DEADLINE, current, () -> publishedRows() == 2);
        await("주문 A 구매 활동 저장", RESULT_DEADLINE, streamer, () -> activityRows(orderA).size() == 2);
        await("consumer offset 끝까지 진행", RESULT_DEADLINE, streamer, this::consumerCaughtUp);

        // 같은 사건: 기존 eventId·확정 시각·품목을 그대로 쓴다.
        List<Map<String, Object>> outbox = outboxRows();
        assertThat(outbox).hasSize(2);
        assertSameEvent(outbox.get(0), orderA, factsA, Map.of(socks, 2, cap, 1));
        assertSameEvent(outbox.get(1), orderB, factsB, Map.of(socks, 1));

        // 구매 활동: A는 빠졌던 행이 생기고, B는 재발행을 받아도 그대로다.
        assertThat(activityRows(orderA)).extracting(row -> row.get("product_id"), row -> row.get("quantity"))
            .containsExactlyInAnyOrder(tuple(socks, 2), tuple(cap, 1));
        assertThat(activityRows(orderB)).isEqualTo(activitiesB);
        assertThat(count("SELECT COUNT(*) FROM processed_order_confirmation")).isEqualTo(2);
        assertThat(deadLetterTopicExists()).as("같은 사실의 재발행은 격리되지 않는다").isFalse();

        // Kafka: W6가 보낸 B와 복구가 보낸 B는 같은 내용의 별도 record다.
        List<ConsumerRecord<String, String>> afterRecovery = allRecords();
        assertThat(afterRecovery).hasSize(3);
        List<ConsumerRecord<String, String>> recordsB = afterRecovery.stream()
            .filter(record -> record.key().equals(orderB.toString())).toList();
        assertThat(recordsB).hasSize(2);
        assertThat(recordsB).extracting(ConsumerRecord::offset).doesNotHaveDuplicates();
        assertThat(MAPPER.readTree(recordsB.get(1).value())).isEqualTo(MAPPER.readTree(beforeRecovery.getFirst().value()));

        // 결제·주문·재고: 복구로 바뀐 것이 없다.
        assertNoPaymentOrOrderEffects(pg, createCalls, transactions, lookups, orderA, factsA, orderB, factsB, stock, socks, cap);

        // 두 번째 복구: 같은 대상을 다시 실행해도 Outbox·구매 효과가 늘지 않는다.
        current.close();
        apps.remove(current);
        List<Map<String, Object>> outboxAfterFirstRecovery = outboxRows();
        List<Map<String, Object>> activitiesA = activityRows(orderA);
        long recordsAfterFirstRecovery = endOffsetTotal();
        SpringAppProcess again = startApi(() -> SpringAppProcess.currentApi(database, bootstrap, TOPIC, port(), pg.baseUrl(), recoveryArguments));
        await("두 번째 복구의 no-op 로그", RESULT_DEADLINE, again, () -> again.logContains("orderId=" + orderA + ", added=false")
            && again.logContains("orderId=" + orderB + ", added=false"));
        assertThat(outboxRows()).isEqualTo(outboxAfterFirstRecovery);
        assertThat(endOffsetTotal()).isEqualTo(recordsAfterFirstRecovery);
        assertThat(activityRows(orderA)).isEqualTo(activitiesA);
        assertThat(activityRows(orderB)).isEqualTo(activitiesB);
        assertNoPaymentOrOrderEffects(pg, createCalls, transactions, lookups, orderA, factsA, orderB, factsB, stock, socks, cap);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outbox", outboxAfterFirstRecovery.stream().map(row -> row.get("event_id") + "=" + row.get("status")
            + " attempts=" + row.get("attempt_count")).toList());
        result.put("records", afterRecovery.stream().map(record -> record.key() + "@" + record.partition() + ":" + record.offset()).toList());
        result.put("activitiesA", activitiesA.stream().map(row -> row.get("product_id") + "x" + row.get("quantity")).toList());
        result.put("activitiesB", activitiesB.stream().map(row -> row.get("product_id") + "x" + row.get("quantity")).toList());
        result.put("pgDuringRecovery", Map.of("create", pg.createCalls() - createCalls,
            "transactions", pg.transactionCount() - transactions, "lookups", pg.lookupCalls() - lookups));
        System.out.println("W7_W6_RECOVERY_DONE " + MAPPER.writeValueAsString(result));
    }

    private int port() {
        return portInUse;
    }

    private void assertSameEvent(Map<String, Object> row, Long orderId, Map<String, Object> facts, Map<Long, Integer> items) throws Exception {
        assertThat(row.get("event_id")).isEqualTo("order-confirmed:v1:" + orderId);
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        JsonNode payload = MAPPER.readTree((String) row.get("payload"));
        assertThat(payload.get("eventId").asText()).isEqualTo("order-confirmed:v1:" + orderId);
        assertThat(Instant.parse(payload.get("confirmedAt").asText())).isEqualTo(Instant.parse((String) facts.get("confirmedAt")));
        Map<Long, Integer> stored = new LinkedHashMap<>();
        payload.get("items").forEach(item -> stored.put(item.get("productId").asLong(), item.get("quantity").asInt()));
        assertThat(stored).isEqualTo(items);
    }

    private void assertNoPaymentOrOrderEffects(FakePaymentServer pg, int createCalls, int transactions, int lookups,
                                               Long orderA, Map<String, Object> factsA, Long orderB, Map<String, Object> factsB,
                                               Map<Long, Integer> stock, Long socks, Long cap) throws SQLException {
        assertThat(pg.createCalls()).isEqualTo(createCalls);
        assertThat(pg.transactionCount()).isEqualTo(transactions);
        assertThat(pg.lookupCalls()).isEqualTo(lookups);
        assertThat(orderFacts(orderA)).isEqualTo(factsA);
        assertThat(orderFacts(orderB)).isEqualTo(factsB);
        assertThat(stocks(socks, cap)).isEqualTo(stock);
    }

    private SpringAppProcess started(SpringAppProcess app) {
        apps.add(app);
        return app;
    }

    private interface AppStarter {
        SpringAppProcess start() throws Exception;
    }

    private SpringAppProcess startApi(AppStarter starter) throws Exception {
        portInUse = SpringAppProcess.freePort();
        apiUrl = "http://127.0.0.1:" + portInUse;
        SpringAppProcess app = started(starter.start());
        await("API 기동", APP_DEADLINE, app, () -> {
            try {
                return http.send(HttpRequest.newBuilder(URI.create(apiUrl + "/api/v1/products")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
            } catch (IOException notReady) {
                return false;
            }
        });
        return app;
    }

    private Long seedUser() throws SQLException {
        try (var users = db.prepareStatement("INSERT INTO users (created_at, updated_at) VALUES (NOW(6), NOW(6))",
            Statement.RETURN_GENERATED_KEYS)) {
            users.executeUpdate();
            ResultSet keys = users.getGeneratedKeys();
            keys.next();
            long userId = keys.getLong(1);
            try (var points = db.prepareStatement(
                "INSERT INTO point_balance (user_id, balance, created_at, updated_at) VALUES (?, 0, NOW(6), NOW(6))")) {
                points.setLong(1, userId);
                points.executeUpdate();
            }
            return userId;
        }
    }

    private Long createProduct(Long brandId, String name, long price) throws Exception {
        return post("/api-admin/v1/products", null, Map.of("brandId", brandId, "name", name, "price", price,
            "initialStockQuantity", 10), 201).path("data").path("id").asLong();
    }

    private Long createOrder(Long userId, List<Map<String, Object>> items) throws Exception {
        return post("/api/v1/orders", userId, Map.of("items", items), 201).path("data").path("id").asLong();
    }

    private void startPayment(Long userId, Long orderId, String attemptKey) throws Exception {
        post("/api/v1/orders/" + orderId + "/confirm", userId, Map.of("paymentAttemptKey", attemptKey,
            "cardType", "TEST", "cardNo", "0000-0000-0000-0000"), 202);
    }

    private JsonNode post(String path, Long userId, Object body, int expected) throws Exception {
        HttpResponse<String> response = http.send(request(path, userId, MAPPER.writeValueAsString(body)), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s 응답: %s", path, response.body()).isEqualTo(expected);
        return MAPPER.readTree(response.body());
    }

    private HttpRequest request(String path, Long userId, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiUrl + path))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (userId != null) {
            builder.header("X-USER-ID", userId.toString());
        } else {
            builder.header("X-USER-ROLE", "ADMIN");
        }
        return builder.build();
    }

    private Map<String, Object> orderFacts(Long orderId) throws SQLException {
        List<Map<String, Object>> rows = query("""
            SELECT o.status, o.reservation_status, o.confirmed_at, a.transaction_key, a.status AS payment_status
            FROM orders o JOIN payment_attempt a ON a.order_id = o.id WHERE o.id = ?""", orderId);
        Map<String, Object> row = rows.getFirst();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("orderId", orderId);
        facts.put("eventId", "order-confirmed:v1:" + orderId);
        facts.put("status", row.get("status"));
        facts.put("reservation", row.get("reservation_status"));
        facts.put("confirmedAt", row.get("confirmed_at") == null ? null : ((LocalDateTime) row.get("confirmed_at")).toInstant(STORED_ZONE).toString());
        facts.put("transactionKey", row.get("transaction_key"));
        facts.put("payment", row.get("payment_status"));
        return facts;
    }

    private List<Map<String, Object>> activityRows(Long orderId) throws SQLException {
        return query("SELECT id, product_id, quantity, confirmed_at FROM product_purchase_activity WHERE order_id = ? ORDER BY id", orderId);
    }

    private List<Map<String, Object>> outboxRows() throws SQLException {
        return query("SELECT event_id, status, attempt_count, payload, published_at FROM order_outbox ORDER BY order_id");
    }

    private long publishedRows() throws SQLException {
        return tableExists("order_outbox") ? count("SELECT COUNT(*) FROM order_outbox WHERE status = 'PUBLISHED'") : 0;
    }

    private Map<Long, Integer> stocks(Long... productIds) throws SQLException {
        Map<Long, Integer> stocks = new LinkedHashMap<>();
        for (Long productId : productIds) {
            stocks.put(productId, ((Number) query("SELECT quantity AS stock_quantity FROM product WHERE id = ?", productId).getFirst().get("stock_quantity")).intValue());
        }
        return stocks;
    }

    private boolean tableExists(String table) throws SQLException {
        return !query("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?", table).isEmpty();
    }

    private long count(String sql) throws SQLException {
        return ((Number) query(sql).getFirst().values().iterator().next()).longValue();
    }

    private List<Map<String, Object>> query(String sql, Object... parameters) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            ResultSet result = statement.executeQuery();
            var meta = result.getMetaData();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int column = 1; column <= meta.getColumnCount(); column++) {
                    row.put(meta.getColumnLabel(column).toLowerCase(), result.getObject(column));
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private boolean consumerAssigned() throws Exception {
        var group = admin.describeConsumerGroups(List.of(GROUP)).all().get(10, TimeUnit.SECONDS).get(GROUP);
        int assigned = group.members().stream().mapToInt(member -> member.assignment().topicPartitions().size()).sum();
        return group.state() == ConsumerGroupState.STABLE && assigned == PARTITIONS;
    }

    private boolean consumerCaughtUp() throws Exception {
        var committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
        Map<TopicPartition, Long> ends = endOffsets();
        return ends.entrySet().stream().allMatch(end -> end.getValue() == 0
            || (committed.get(end.getKey()) != null && committed.get(end.getKey()).offset() == end.getValue()));
    }

    private long endOffsetTotal() throws Exception {
        return endOffsets().values().stream().mapToLong(Long::longValue).sum();
    }

    private Map<TopicPartition, Long> endOffsets() throws Exception {
        var request = partitions().stream().collect(Collectors.toMap(Function.identity(), ignored -> OffsetSpec.latest()));
        var result = admin.listOffsets(request).all().get(10, TimeUnit.SECONDS);
        return result.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().offset()));
    }

    private boolean deadLetterTopicExists() throws Exception {
        return admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(TOPIC + "-dlt");
    }

    private List<TopicPartition> partitions() {
        return IntStream.range(0, PARTITIONS).mapToObj(partition -> new TopicPartition(TOPIC, partition)).toList();
    }

    private List<ConsumerRecord<String, String>> allRecords() throws Exception {
        long expected = endOffsetTotal();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            consumer.assign(partitions());
            consumer.seekToBeginning(partitions());
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.nanoTime() + RESULT_DEADLINE.toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }

    private void await(String step, Duration limit, SpringAppProcess app, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            assertThat(app.isAlive()).as("%s 중 앱 종료:\n%s", step, app.logTail()).isTrue();
            if (condition.call()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("기한 초과: %s\n%s".formatted(step, app.logTail()));
    }
}
