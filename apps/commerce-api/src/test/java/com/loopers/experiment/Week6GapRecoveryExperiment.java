package com.loopers.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.process.BootJarProcess;
import com.loopers.support.process.GitRevisionBuild;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.testcontainers.RedisTestContainersConfig;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 7주차 설계 6장 (D-111, D-112). 6주차 revision 실행물로 "확정됐지만 이벤트가 없는" 주문을 실제로 만들고,
 * 같은 DB · broker 에 7주차 운영 진입점(backfill · run-once)을 실행물로 붙여 복구함. 준비부터 복구 끝까지 상태를 초기화하지 않음.
 * A 는 linger 로 producer 버퍼에 머문 record 를 프로세스 강제 종료로 잃게 함. 확정 commit 뒤 전달 전 중단 한 장면이며 OS 장애 일반의 시험이 아님.
 * 실행: RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*Week6GapRecoveryExperiment'
 */
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
class Week6GapRecoveryExperiment {

    private static final String WEEK6_REVISION = "475fe3e4";
    private static final String DATABASE = "week6_recovery";
    private static final String TOPIC = "order-events-week6-recovery";
    private static final String USER_ID_HEADER = "X-USER-ID";
    // record 가 broker 로 가기 전 버퍼에 머무는 시간. 이 안에 A 의 api 를 끝냄
    private static final Duration LINGER = Duration.ofSeconds(30);
    private static final Duration START_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration CONSUME_TIMEOUT = LINGER.plusSeconds(30);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration QUIET_PERIOD = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestPgServer pg = TestPgServer.shared();
    private final List<BootJarProcess> processes = new ArrayList<>();
    private GitRevisionBuild week6;
    private String jdbcUrl;
    private String rootPassword;

    @AfterEach
    void tearDown() throws Exception {
        processes.forEach(BootJarProcess::kill);
        if (week6 != null) {
            week6.close();
        }
        if (jdbcUrl != null) {
            execute("DROP DATABASE IF EXISTS " + DATABASE);
        }
    }

    @DisplayName("6주차 코드가 남긴 '확정됐지만 이벤트 없는' 주문을 7주차 backfill · relay 가 저장된 사실로 잇고, 이미 발행된 주문은 소비 측에서 no-op 이며 PG · 결제 · 재고는 그대로다.")
    @Test
    void recoversWeek6Gap() throws Exception {
        // 준비: 격리 자원과 6주차 실행물
        prepareResources();
        Path root = Path.of(System.getProperty("project.root-dir"));
        week6 = GitRevisionBuild.checkout(root, WEEK6_REVISION, root.resolve("build").resolve("week6-revision"));
        week6.buildBootJars(":apps:commerce-api", ":apps:commerce-streamer");

        int apiPort = freePort();
        BootJarProcess week6Api = start("week6-api", week6.bootJar("apps/commerce-api"), week6ApiArgs(apiPort));
        week6Api.awaitLine(line -> line.contains("Started CommerceApiApplication"), START_TIMEOUT);
        BootJarProcess streamer = start("week6-streamer", week6.bootJar("apps/commerce-streamer"), streamerArgs());
        streamer.awaitLine(line -> line.contains("Started CommerceStreamerApplication"), START_TIMEOUT);
        streamer.awaitLine(line -> line.contains("partitions assigned"), START_TIMEOUT);

        long userId = insert("INSERT INTO users (created_at, updated_at) VALUES (NOW(6), NOW(6))");
        long brandId = insert("INSERT INTO brand (name, description, created_at, updated_at) VALUES ('브랜드', NULL, NOW(6), NOW(6))");
        long first = insert("INSERT INTO product (brand_id, name, price, stock, created_at, updated_at) VALUES (" + brandId + ", '첫 번째 상품', 1000, 10, NOW(6), NOW(6))");
        long second = insert("INSERT INTO product (brand_id, name, price, stock, created_at, updated_at) VALUES (" + brandId + ", '두 번째 상품', 2500, 10, NOW(6), NOW(6))");

        // 주문 B: 6주차 직접 발행이 정상 전달되고 streamer 가 구매 활동을 남김
        long orderB = confirmThroughWeek6(apiPort, userId, Map.of(first, 1));
        streamer.awaitCount(consumed(eventId(orderB)), 1, CONSUME_TIMEOUT);

        // 주문 A: 확정 commit 뒤 record 가 버퍼에 있는 동안 api 를 끝냄
        long orderA = confirmThroughWeek6(apiPort, userId, orderedItems(first, 2, second, 1));
        week6Api.kill();
        Thread.sleep(LINGER.plusSeconds(5).toMillis());

        // 준비 상태 확인 (새 DB 조회)
        Map<String, Object> facts = new LinkedHashMap<>();
        for (long orderId : List.of(orderA, orderB)) {
            facts.put("order " + orderId, orderFacts(orderId));
        }
        int createCount = pg.createCount();
        int lookupCount = pg.lookupCount();
        int transactionCount = pg.transactions().size();
        List<List<Object>> stockBefore = rows("SELECT id, stock FROM product ORDER BY id");
        List<List<Object>> ordersBefore = rows("SELECT id, status, confirmed_at, version FROM orders ORDER BY id");
        List<List<Object>> paymentsBefore = rows("SELECT order_id, status, reservation_status, transaction_key FROM payment ORDER BY order_id");
        List<List<Object>> activityB = activityOf(orderB);
        List<ConsumerRecord<String, String>> week6Records = topicRecords();
        System.out.println("[w6-recovery] 준비 사실 " + facts + " pgCreate=" + createCount + " pgLookup=" + lookupCount + " pgTransactions=" + transactionCount);

        assertThat(statusOf(orderA)).isEqualTo("CONFIRMED");
        assertThat(statusOf(orderB)).isEqualTo("CONFIRMED");
        assertThat(rows("SELECT reservation_status FROM payment WHERE order_id IN (" + orderA + ", " + orderB + ")"))
            .isEqualTo(List.of(List.of("CONSUMED"), List.of("CONSUMED")));
        assertThat(rows("SELECT table_name FROM information_schema.tables WHERE table_schema = '" + DATABASE + "' AND table_name = 'order_confirmed_outbox'")).isEmpty();
        assertThat(activityOf(orderA)).isEmpty();
        assertThat(activityB).isEqualTo(List.of(List.of(first, 1L)));
        assertThat(week6Records).extracting(ConsumerRecord::key).containsExactly(String.valueOf(orderB));
        assertThat(week6Api.count(line -> line.contains("주문 확정 이벤트 발행.") && line.contains("orderId=" + orderA))).isZero();

        // 복구: 6주차 api 는 끝났고, 같은 DB · broker 에 7주차 운영 진입점을 실행물로 붙임
        String orderIds = orderA + "," + orderB;
        BootJarProcess backfill = runCommand("week7-backfill", "--order-outbox.command=backfill", "--order-outbox.order-ids=" + orderIds);
        BootJarProcess relay = runCommand("week7-run-once", "--order-outbox.command=run-once");
        streamer.awaitCount(consumed(eventId(orderA)), 1, CONSUME_TIMEOUT);
        streamer.awaitCount(consumed(eventId(orderB)), 2, CONSUME_TIMEOUT);
        List<List<Object>> outboxAfterFirst = rows("SELECT order_id, event_id, status, attempt_count FROM order_confirmed_outbox ORDER BY order_id");

        // 두 번째 backfill · relay 는 아무것도 늘리지 않음
        BootJarProcess backfillAgain = runCommand("week7-backfill-again", "--order-outbox.command=backfill", "--order-outbox.order-ids=" + orderIds);
        runCommand("week7-run-once-again", "--order-outbox.command=run-once");
        Thread.sleep(QUIET_PERIOD.toMillis());

        // 확인
        List<ConsumerRecord<String, String>> records = topicRecords();
        List<String> valuesOfB = records.stream().filter(r -> r.key().equals(String.valueOf(orderB))).map(ConsumerRecord::value).toList();
        System.out.println("[w6-recovery] 복구 뒤 records=" + records.stream().map(r -> r.key() + "@" + r.partition() + ":" + r.offset()).toList()
            + " outbox=" + outboxAfterFirst);
        assertAll(
            () -> assertThat(backfill.count(backfillResult(orderA, "RECORDED"))).isEqualTo(1),
            () -> assertThat(backfill.count(backfillResult(orderB, "RECORDED"))).isEqualTo(1),
            () -> assertThat(backfillAgain.count(backfillResult(orderA, "ALREADY_RECORDED"))).isEqualTo(1),
            () -> assertThat(backfillAgain.count(backfillResult(orderB, "ALREADY_RECORDED"))).isEqualTo(1),
            () -> assertThat(relay.count(line -> line.contains("Outbox relay 한 회차 실행함"))).isEqualTo(1),
            // B 를 먼저 확정해 order_id 순서는 B, A 임
            () -> assertThat(outboxAfterFirst).isEqualTo(List.of(
                List.of(orderB, eventId(orderB), "PUBLISHED", 1L),
                List.of(orderA, eventId(orderA), "PUBLISHED", 1L))),
            () -> assertThat(rows("SELECT order_id, event_id, status, attempt_count FROM order_confirmed_outbox ORDER BY order_id")).isEqualTo(outboxAfterFirst),
            // A 는 새로 활동이 생기고, B 는 재발행을 받아도 그대로임
            () -> assertThat(streamer.count(consumed(eventId(orderA)).and(line -> line.contains("result=RECORDED ")))).isEqualTo(1),
            () -> assertThat(streamer.count(consumed(eventId(orderB)).and(line -> line.contains("result=ALREADY_RECORDED ")))).isEqualTo(1),
            () -> assertThat(streamer.count(consumed(eventId(orderA)))).isEqualTo(1),
            () -> assertThat(streamer.count(consumed(eventId(orderB)))).isEqualTo(2),
            () -> assertThat(activityOf(orderA)).isEqualTo(List.of(List.of(first, 2L), List.of(second, 1L))),
            () -> assertThat(activityOf(orderB)).isEqualTo(activityB),
            () -> assertThat(rows("SELECT event_id FROM processed_order_confirmation ORDER BY order_id"))
                .isEqualTo(List.of(List.of(eventId(orderB)), List.of(eventId(orderA)))),
            // B 의 재발행은 6주차가 보낸 것과 같은 사건 · 같은 bytes 임
            () -> assertThat(records).extracting(ConsumerRecord::key)
                .containsExactlyInAnyOrder(String.valueOf(orderB), String.valueOf(orderA), String.valueOf(orderB)),
            () -> assertThat(valuesOfB).hasSize(2).containsOnly(week6Records.get(0).value()),
            // 복구는 PG · 결제 · 주문 · 재고를 건드리지 않음
            () -> assertThat(pg.createCount()).isEqualTo(createCount).isEqualTo(2),
            () -> assertThat(pg.lookupCount()).isEqualTo(lookupCount),
            () -> assertThat(pg.transactions()).hasSize(transactionCount).hasSize(2),
            () -> assertThat(rows("SELECT id, stock FROM product ORDER BY id")).isEqualTo(stockBefore),
            () -> assertThat(rows("SELECT id, status, confirmed_at, version FROM orders ORDER BY id")).isEqualTo(ordersBefore),
            () -> assertThat(rows("SELECT order_id, status, reservation_status, transaction_key FROM payment ORDER BY order_id")).isEqualTo(paymentsBefore)
        );
    }

    private void prepareResources() throws Exception {
        // 공유 컨테이너를 띄움. 다른 테스트와 섞이지 않게 별도 database · topic 을 씀
        if (MySqlTestContainersConfig.container().isEmpty() || KafkaTestContainersConfig.container().isEmpty()) {
            throw new IllegalStateException("Testcontainers 로만 실행함. TEST_LOCAL_INFRA 를 끄고 실행할 것");
        }
        new RedisTestContainersConfig();
        String containerUrl = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        rootPassword = System.getProperty("datasource.mysql-jpa.main.password");
        jdbcUrl = containerUrl.substring(0, containerUrl.lastIndexOf('/') + 1) + DATABASE;
        try (Connection connection = DriverManager.getConnection(containerUrl, "root", rootPassword);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
            statement.execute("CREATE DATABASE " + DATABASE + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        }
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()))) {
            if (!admin.listTopics().names().get().contains(TOPIC)) {
                admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get();
            }
        }
        pg.reset();
    }

    private List<String> week6ApiArgs(int port) {
        List<String> args = new ArrayList<>(commonArgs("test"));
        args.addAll(List.of(
            "--server.port=" + port,
            "--management.server.port=0",
            // 6주차 test profile 그대로 create. 이 database 는 비어 있음
            "--spring.jpa.hibernate.ddl-auto=create",
            "--payment.test-pg.callback-url=http://127.0.0.1:" + port + "/api/v1/payments/callback",
            "--spring.kafka.producer.properties.linger.ms=" + LINGER.toMillis()
        ));
        return args;
    }

    private List<String> streamerArgs() {
        List<String> args = new ArrayList<>(commonArgs("test"));
        args.addAll(List.of(
            "--server.port=0",
            "--management.server.port=0",
            // 6주차 api 가 만든 테이블을 지우지 않도록 update
            "--spring.jpa.hibernate.ddl-auto=update",
            "--order-event.consumer-group=purchase-activity-week6-recovery"
        ));
        return args;
    }

    /** 7주차 운영 진입점은 local profile 에서만 켜짐. 스케줄러가 대신 보내지 않게 relay 를 끔 */
    private BootJarProcess runCommand(String name, String... command) {
        List<String> args = new ArrayList<>(commonArgs("local"));
        args.addAll(List.of(
            // 웹을 끄면 관리자 경계의 HttpSecurity 가 없어 기동하지 못하므로 임의 포트로 띄움
            "--server.port=0",
            "--management.server.port=0",
            // 6주차 스키마 위에 Outbox 테이블만 더함. create 면 준비 상태가 지워짐
            "--spring.jpa.hibernate.ddl-auto=update",
            "--order-outbox.relay.enabled=false"
        ));
        args.addAll(List.of(command));
        BootJarProcess process = start(name, Path.of(System.getProperty("commerce-api.boot-jar")), args);
        assertThat(process.awaitExit(COMMAND_TIMEOUT)).as(name + " 종료 코드").isZero();
        return process;
    }

    private List<String> commonArgs(String profile) {
        return List.of(
            "--spring.profiles.active=" + profile,
            "--datasource.mysql-jpa.main.jdbc-url=" + jdbcUrl,
            "--datasource.mysql-jpa.main.username=root",
            "--datasource.mysql-jpa.main.password=" + rootPassword,
            "--spring.kafka.bootstrap-servers=" + bootstrapServers(),
            "--datasource.redis.database=0",
            "--datasource.redis.master.host=" + System.getProperty("datasource.redis.master.host"),
            "--datasource.redis.master.port=" + System.getProperty("datasource.redis.master.port"),
            "--datasource.redis.replicas[0].host=" + System.getProperty("datasource.redis.replicas[0].host"),
            "--datasource.redis.replicas[0].port=" + System.getProperty("datasource.redis.replicas[0].port"),
            "--order-event.topic=" + TOPIC,
            "--payment.test-pg.base-url=" + pg.baseUrl()
        );
    }

    private BootJarProcess start(String name, Path bootJar, List<String> args) {
        BootJarProcess process = BootJarProcess.start(name, bootJar, args);
        processes.add(process);
        return process;
    }

    /** 주문 API → 결제 → PG 성공 → reconcile 로 확정함. 6주차 직접 발행은 확정 commit 뒤 이 요청 안에서 일어남 */
    private long confirmThroughWeek6(int port, long userId, Map<Long, Integer> items) throws Exception {
        StringBuilder lines = new StringBuilder();
        items.forEach((productId, quantity) -> lines.append(lines.isEmpty() ? "" : ", ")
            .append("{\"productId\": ").append(productId).append(", \"quantity\": ").append(quantity).append('}'));
        long orderId = objectMapper.readTree(post(port, userId, "/api/v1/orders", "{\"items\": [" + lines + "]}")).at("/data/id").asLong();
        post(port, userId, "/api/v1/orders/" + orderId + "/confirm",
            "{\"paymentAttemptKey\": \"attempt-" + orderId + "\", \"cardType\": \"TEST\", \"cardNo\": \"0000-0000-0000-0000\"}");
        List<TestPgServer.Transaction> transactions = pg.transactionsOf(Payment.providerOrderIdOf(orderId));
        assertThat(transactions).hasSize(1);
        pg.complete(transactions.get(0).transactionKey(), "SUCCESS");
        String reconciled = post(port, userId, "/api/v1/orders/" + orderId + "/payment/reconcile", "");
        assertThat(objectMapper.readTree(reconciled).at("/data/status").asText()).isEqualTo("CONFIRMED");
        return orderId;
    }

    private String post(int port, long userId, String path, String body) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header(USER_ID_HEADER, String.valueOf(userId))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + " " + response.body()).isBetween(200, 299);
        return response.body();
    }

    private Map<String, Object> orderFacts(long orderId) throws SQLException {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("eventId", eventId(orderId));
        facts.put("confirmedAt", rows("SELECT confirmed_at FROM orders WHERE id = " + orderId));
        facts.put("items", rows("SELECT product_id, quantity FROM order_items WHERE order_id = " + orderId + " ORDER BY product_id"));
        facts.put("payment", rows("SELECT provider_order_id, transaction_key, status, reservation_status FROM payment WHERE order_id = " + orderId));
        return facts;
    }

    private String statusOf(long orderId) throws SQLException {
        return (String) rows("SELECT status FROM orders WHERE id = " + orderId).get(0).get(0);
    }

    private List<List<Object>> activityOf(long orderId) throws SQLException {
        return rows("SELECT product_id, quantity FROM product_purchase_activity WHERE order_id = " + orderId + " ORDER BY product_id");
    }

    private List<ConsumerRecord<String, String>> topicRecords() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                .map(info -> new TopicPartition(TOPIC, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (partitions.stream().anyMatch(partition -> consumer.position(partition) < end.get(partition))) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("topic 을 끝까지 읽지 못함");
                }
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
            records.sort((left, right) -> left.partition() != right.partition()
                ? Integer.compare(left.partition(), right.partition()) : Long.compare(left.offset(), right.offset()));
            return records;
        }
    }

    private long insert(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "root", rootPassword);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1), "root", rootPassword);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** 숫자는 long, 시각은 문자열로 맞춰 비교함 */
    private List<List<Object>> rows(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "root", rootPassword);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            int columns = resultSet.getMetaData().getColumnCount();
            List<List<Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                List<Object> row = new ArrayList<>();
                for (int column = 1; column <= columns; column++) {
                    Object value = resultSet.getObject(column);
                    row.add(value instanceof Number number ? (Object) number.longValue() : value == null ? null : value instanceof String ? value : value.toString());
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private static Map<Long, Integer> orderedItems(long first, int firstQuantity, long second, int secondQuantity) {
        Map<Long, Integer> items = new LinkedHashMap<>();
        items.put(first, firstQuantity);
        items.put(second, secondQuantity);
        return items;
    }

    private static String eventId(long orderId) {
        return "order-confirmed:v1:" + orderId;
    }

    private static Predicate<String> consumed(String eventId) {
        return line -> line.contains("주문 확정 이벤트 소비") && line.contains("eventId=" + eventId + " ");
    }

    private static Predicate<String> backfillResult(long orderId, String result) {
        return line -> line.contains("Outbox backfill. orderId=" + orderId + " result=" + result + " ");
    }

    private static String bootstrapServers() {
        return System.getProperty("spring.kafka.bootstrap-servers");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
