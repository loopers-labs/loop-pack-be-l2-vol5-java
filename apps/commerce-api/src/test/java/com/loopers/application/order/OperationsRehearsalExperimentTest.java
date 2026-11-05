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
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 리허설. 평소 트래픽(초당 10건)을 실제 주문 API로 흘리는 중에 Outbox와 바로 닿는 장애를 실제 방식으로 일으키고,
 * 운영자가 볼 수 있는 신호만 1초마다 기록한다.
 * <p>
 * 신호: API 흐름 성공·실패와 응답 시간(k6), 확정 주문 수, PENDING 수·가장 오래된 PENDING의 나이·최대 시도 횟수·대표 오류,
 * PUBLISHED 수, Kafka 기록 수, consumer lag, DLT 기록 수, 구매 활동 반영 수, 앱 로그의 문구 수.
 * 장애는 Outbox를 직접 고치지 않고 만든다. broker 프로세스 정지, relay가 켜진 서버 추가. 같은 컴퓨터에서 k6·앱·가짜 PG·MySQL·Kafka broker 1대를 함께 돌린 결과다.
 * 실행: {@code ./gradlew :apps:commerce-api:experimentTest --tests '*OperationsRehearsalExperimentTest'} (k6 필요)
 */
@Tag("experiment")
class OperationsRehearsalExperimentTest {
    private static final String TOPIC = "order-events-rehearsal";
    private static final String DLT = TOPIC + "-dlt";
    private static final String GROUP = "purchase-activity";
    private static final int PARTITIONS = 3;
    private static final int RATE = 10;
    private static final int LOAD_SECONDS = 100;
    private static final int USERS = 500;
    private static final int PRODUCTS = 20;
    private static final Duration APP_DEADLINE = Duration.ofSeconds(120);
    private static final Duration DRAIN_LIMIT = Duration.ofSeconds(150);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> OPERATION_POOL = List.of(
        "--datasource.mysql-jpa.main.maximum-pool-size=40", "--datasource.mysql-jpa.main.minimum-idle=30");

    enum Incident {
        /** Kafka가 메시지를 받지 못함: broker 프로세스를 60초 내린다. */
        BROKER_DOWN,
        /** 설정 실수로 relay가 켜진 주문 서버가 60초 동안 한 대 더 뜬다. */
        SECOND_RELAY
    }

    private static MySQLContainer<?> mysql;
    private static KafkaTestBroker broker;
    private static FakePaymentServer pg;
    private static SpringAppProcess.Database database;
    private static Connection db;
    private static AdminClient admin;
    private static SpringAppProcess streamer;
    private static SpringAppProcess api;
    private static String apiUrl;
    private static List<Long> userIds;
    private static List<Long> productIds;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0")).withDatabaseName("operations_rehearsal")
            .withCommand("--max-connections=500");
        mysql.start();
        broker = new KafkaTestBroker();
        pg = new FakePaymentServer();
        pg.settleCreatedAs("SUCCESS");
        database = new SpringAppProcess.Database(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        db = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
        admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers(),
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 2_000, AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 1_000));
        admin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS, (short) 1), new NewTopic(DLT, 1, (short) 1)))
            .all().get(10, TimeUnit.SECONDS);
        api = startApi(List.of());
        streamer = startStreamer(List.of());
        seedCatalog();
    }

    @AfterAll
    static void stopInfrastructure() throws Exception {
        try {
            for (SpringAppProcess app : new SpringAppProcess[] {api, streamer}) {
                if (app != null) {
                    app.close();
                }
            }
        } finally {
            admin.close();
            db.close();
            pg.close();
            broker.close();
            mysql.stop();
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Incident.class)
    void rehearse(Incident incident) throws Exception {
        Baseline base = baseline();
        List<Map<String, Object>> marks = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> samples = new CopyOnWriteArrayList<>();
        AtomicBoolean sampling = new AtomicBoolean(true);
        long origin = System.currentTimeMillis();
        Thread sampler = Thread.ofPlatform().start(() -> sample(base, origin, samples, sampling));
        Path work = Files.createTempDirectory("operations-rehearsal");
        Process k6 = startK6(work);
        try {
            inject(incident, origin, base, marks);
            assertThat(k6.waitFor(LOAD_SECONDS + 60L, TimeUnit.SECONDS)).as("k6 종료").isTrue();
            mark(marks, origin, "부하 끝", base);
            awaitDrained(base);
            mark(marks, origin, "관찰 끝", base);
        } finally {
            sampling.set(false);
            sampler.join(5_000);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("incident", incident);
        report.put("marks", marks);
        report.put("timeline", timeline(samples, flows(work.resolve("console.log")), origin));
        report.put("final", snapshot(base));
        System.out.println("OPERATIONS_REHEARSAL " + MAPPER.writeValueAsString(report));

        Map<String, Object> last = snapshot(base);
        assertThat((long) last.get("consumed")).as("확정 주문은 모두 한 번씩 반영").isEqualTo((long) last.get("confirmed"));
    }

    // ---- 장애 ----

    /** 장애를 정해진 시각에 넣고 거둔다. */
    private void inject(Incident incident, long origin, Baseline base, List<Map<String, Object>> marks) throws Exception {
        switch (incident) {
            case BROKER_DOWN -> {
                at(origin, 20);
                broker.stopProcess();
                mark(marks, origin, "broker 내림", base);
                at(origin, 79);
                mark(marks, origin, "broker 올리기 직전", base);
                broker.startAgain();
                mark(marks, origin, "broker 올림", base);
            }
            case SECOND_RELAY -> {
                at(origin, 20);
                SpringAppProcess second = startApi(List.of());
                mark(marks, origin, "relay가 켜진 서버 한 대 더 뜸", base);
                at(origin, 80);
                second.close();
                mark(marks, origin, "두 번째 서버 내림", base);
            }
        }
    }

    private static void at(long origin, int second) throws InterruptedException {
        long wait = origin + second * 1_000L - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
    }

    // ---- 부하 ----

    private static Process startK6(Path work) throws IOException {
        Path config = work.resolve("config.json");
        Files.writeString(config, MAPPER.writeValueAsString(Map.of("baseUrl", apiUrl, "userIds", userIds,
            "productIds", productIds, "stages", List.of(Map.of("rate", RATE, "seconds", LOAD_SECONDS)))));
        Path script = Path.of(System.getProperty("experiment.repositoryRoot"), "scripts", "week7-order-flow.k6.js");
        String k6 = Files.isExecutable(Path.of("/opt/homebrew/bin/k6")) ? "/opt/homebrew/bin/k6" : "k6";
        return new ProcessBuilder(k6, "run", "--quiet", "--console-output=" + work.resolve("console.log"),
            "-e", "CONFIG_FILE=" + config, "-e", "SUMMARY_FILE=" + work.resolve("summary.json"), script.toString())
            .redirectErrorStream(true).redirectOutput(work.resolve("k6.out").toFile()).start();
    }

    private void awaitDrained(Baseline base) throws Exception {
        long deadline = System.nanoTime() + DRAIN_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> now = snapshot(base);
            if ((long) now.get("consumed") >= (long) now.get("confirmed")) {
                return;
            }
            Thread.sleep(1_000);
        }
    }

    // ---- 신호 ----

    private record Baseline(long orderId, long topicRecords, long dltRecords) {}

    private static Baseline baseline() throws Exception {
        return new Baseline(scalar(db, "SELECT COALESCE(MAX(id), 0) FROM orders"), endOffsets(TOPIC), endOffsets(DLT));
    }

    /** 운영자가 한 번에 볼 수 있는 값들. DB·broker가 응답하지 않으면 그 값은 -1이다. */
    private static Map<String, Object> snapshot(Connection connection, Baseline base) {
        Map<String, Object> values = new LinkedHashMap<>();
        long order = base.orderId();
        values.put("confirmed", safe(() -> scalar(connection, "SELECT COUNT(*) FROM orders WHERE status = 'CONFIRMED' AND id > " + order)));
        values.put("pending", safe(() -> scalar(connection,
            "SELECT COUNT(*) FROM order_outbox WHERE status = 'PENDING' AND order_id > " + order)));
        values.put("oldestPendingSeconds", safe(() -> scalar(connection, "SELECT COALESCE(TIMESTAMPDIFF(SECOND, MIN(created_at), "
            + "UTC_TIMESTAMP(6)), 0) FROM order_outbox WHERE status = 'PENDING' AND order_id > " + order)));
        values.put("maxPendingAttempts", safe(() -> scalar(connection,
            "SELECT COALESCE(MAX(attempt_count), 0) FROM order_outbox WHERE status = 'PENDING' AND order_id > " + order)));
        values.put("published", safe(() -> scalar(connection,
            "SELECT COUNT(*) FROM order_outbox WHERE status = 'PUBLISHED' AND order_id > " + order)));
        values.put("consumed", safe(() -> scalar(connection,
            "SELECT COUNT(*) FROM processed_order_confirmation WHERE order_id > " + order)));
        values.put("topicRecords", safe(() -> endOffsets(TOPIC) - base.topicRecords()));
        values.put("dltRecords", safe(() -> endOffsets(DLT) - base.dltRecords()));
        values.put("consumerLag", safe(OperationsRehearsalExperimentTest::consumerLag));
        return values;
    }

    private Map<String, Object> snapshot(Baseline base) {
        return snapshot(db, base);
    }

    /** 장애 시점을 기록한다. 멈춘 DB에 붙잡히지 않도록 응답 대기 한도를 둔 새 연결로 읽는다. */
    private static void mark(List<Map<String, Object>> marks, long origin, String what, Baseline base) throws Exception {
        Map<String, Object> mark = new LinkedHashMap<>();
        mark.put("second", (System.currentTimeMillis() - origin) / 1_000);
        mark.put("what", what);
        try (Connection quick = quickConnection()) {
            mark.putAll(snapshot(quick, base));
            mark.put("topPendingError", safeText(() -> topPendingError(quick, base)));
        } catch (SQLException unavailable) {
            mark.put("database", "응답 없음");
        }
        mark.put("apiSendFailedLogLines", api.logLineCount("Order event send failed"));
        mark.put("streamerStoppedLogLines", streamer.isAlive()
            ? streamer.logLineCount("processing failed after retries") : -1);
        marks.add(mark);
    }

    private static String topPendingError(Connection connection, Baseline base) throws SQLException {
        try (Statement statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT last_error, COUNT(*) FROM order_outbox WHERE status = 'PENDING' AND last_error IS NOT NULL "
                    + "AND order_id > " + base.orderId() + " GROUP BY last_error ORDER BY 2 DESC LIMIT 1")) {
            return rows.next() ? rows.getLong(2) + "행: " + rows.getString(1) : "";
        }
    }

    private static Connection quickConnection() throws SQLException {
        String url = database.jdbcUrl() + (database.jdbcUrl().contains("?") ? "&" : "?") + "socketTimeout=800&connectTimeout=800";
        return DriverManager.getConnection(url, database.username(), database.password());
    }

    /** 1초마다 신호를 남긴다. 멈춘 DB에 붙잡히지 않도록 별도 연결에 응답 대기 한도를 둔다. */
    private static void sample(Baseline base, long origin, List<Map<String, Object>> samples, AtomicBoolean sampling) {
        Connection connection = null;
        while (sampling.get()) {
            long started = System.currentTimeMillis();
            try {
                if (connection == null || connection.isClosed()) {
                    connection = quickConnection();
                }
            } catch (SQLException unavailable) {
                connection = null;
            }
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("second", (started - origin) / 1_000.0);
            if (connection != null) {
                sample.putAll(snapshot(connection, base));
            }
            samples.add(sample);
            try {
                Thread.sleep(Math.max(0, 1_000 - (System.currentTimeMillis() - started)));
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 5초 단위로 API 흐름 결과와 신호를 함께 묶는다. */
    private static List<Map<String, Object>> timeline(List<Map<String, Object>> samples, List<JsonNode> flows, long origin) {
        List<Map<String, Object>> rows = new ArrayList<>();
        double end = samples.isEmpty() ? 0 : (double) samples.getLast().get("second");
        for (int from = 0; from <= end; from += 5) {
            int start = from;
            List<JsonNode> mine = flows.stream().filter(flow -> {
                double second = (flow.path("startedEpochMs").asLong() - origin) / 1_000.0;
                return second >= start && second < start + 5;
            }).toList();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("from", start);
            row.put("apiOk", mine.stream().filter(flow -> flow.path("ok").asBoolean()).count());
            row.put("apiFailed", mine.stream().filter(flow -> !flow.path("ok").asBoolean()).count());
            row.put("apiFlowP95Ms", percentile(mine.stream().map(flow -> flow.path("flowMs").asLong()).sorted().toList(), 0.95));
            samples.stream().filter(sample -> (double) sample.get("second") >= start + 4).findFirst()
                .or(() -> samples.isEmpty() ? Optional.empty() : Optional.of(samples.getLast()))
                .ifPresent(sample -> sample.forEach((key, value) -> {
                    if (!"second".equals(key)) {
                        row.put(key, value);
                    }
                }));
            rows.add(row);
        }
        return rows;
    }

    private static List<JsonNode> flows(Path consoleLog) throws IOException {
        Pattern message = Pattern.compile("msg=\"(.*)\"");
        List<JsonNode> flows = new ArrayList<>();
        if (!Files.exists(consoleLog)) {
            return flows;
        }
        try (Stream<String> lines = Files.lines(consoleLog)) {
            for (String line : (Iterable<String>) lines::iterator) {
                Matcher matcher = message.matcher(line);
                if (matcher.find()) {
                    JsonNode node = MAPPER.readTree(matcher.group(1).replace("\\\"", "\""));
                    if ("flow".equals(node.path("kind").asText())) {
                        flows.add(node);
                    }
                }
            }
        }
        return flows;
    }

    private static long percentile(List<Long> sorted, double rank) {
        return sorted.isEmpty() ? 0 : sorted.get((int) Math.ceil(rank * sorted.size()) - 1);
    }

    private static long endOffsets(String topic) throws Exception {
        int partitions = topic.equals(DLT) ? 1 : PARTITIONS;
        Map<TopicPartition, OffsetSpec> latest = new LinkedHashMap<>();
        IntStream.range(0, partitions).forEach(partition -> latest.put(new TopicPartition(topic, partition), OffsetSpec.latest()));
        return admin.listOffsets(latest).all().get(2, TimeUnit.SECONDS).values().stream().mapToLong(info -> info.offset()).sum();
    }

    /** 아직 읽지 않은 메시지 수. 위치를 한 번도 남기지 않은 partition은 처음부터로 본다. */
    private static long consumerLag() throws Exception {
        var committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(2, TimeUnit.SECONDS);
        Map<TopicPartition, OffsetSpec> latest = new LinkedHashMap<>();
        IntStream.range(0, PARTITIONS).forEach(partition -> latest.put(new TopicPartition(TOPIC, partition), OffsetSpec.latest()));
        var ends = admin.listOffsets(latest).all().get(2, TimeUnit.SECONDS);
        long lag = 0;
        for (var entry : ends.entrySet()) {
            var position = committed.get(entry.getKey());
            lag += entry.getValue().offset() - (position == null ? 0 : position.offset());
        }
        return lag;
    }

    private interface Query {
        long get() throws Exception;
    }

    private interface TextQuery {
        String get() throws Exception;
    }

    private static long safe(Query query) {
        try {
            return query.get();
        } catch (Exception unavailable) {
            return -1;
        }
    }

    private static String safeText(TextQuery query) {
        try {
            return query.get();
        } catch (Exception unavailable) {
            return "(조회 실패)";
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    // ---- 앱·데이터 준비 ----

    private static SpringAppProcess startApi(List<String> extra) throws Exception {
        int port = SpringAppProcess.freePort();
        List<String> arguments = new ArrayList<>(OPERATION_POOL);
        arguments.add("--commerce.order-events.relay.scheduling-enabled=true");
        arguments.addAll(extra);
        SpringAppProcess app = SpringAppProcess.currentApi(database, broker.bootstrapServers(), TOPIC, port, pg.baseUrl(),
            arguments);
        String url = "http://127.0.0.1:" + port;
        HttpClient client = HttpClient.newHttpClient();
        long deadline = System.nanoTime() + APP_DEADLINE.toNanos();
        while (true) {
            assertThat(app.isAlive()).as("API 기동 실패:\n%s", app.logTail()).isTrue();
            try {
                if (client.send(HttpRequest.newBuilder(URI.create(url + "/api/v1/products")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    break;
                }
            } catch (IOException notReady) {
                // 아직 기동 중
            }
            assertThat(System.nanoTime()).as("API 기동 기한").isLessThan(deadline);
            Thread.sleep(500);
        }
        if (api == null || !api.isAlive()) {
            apiUrl = url; // 두 번째 서버는 부하를 받지 않는다. relay만 돈다.
        }
        return app;
    }

    private static SpringAppProcess startStreamer(List<String> extra) throws Exception {
        List<String> arguments = new ArrayList<>(OPERATION_POOL);
        arguments.addAll(extra);
        SpringAppProcess app = SpringAppProcess.streamer(database, broker.bootstrapServers(), TOPIC, arguments);
        long deadline = System.nanoTime() + APP_DEADLINE.toNanos();
        while (!consumerAssigned()) {
            assertThat(app.isAlive()).as("구매 활동 앱 기동 실패:\n%s", app.logTail()).isTrue();
            assertThat(System.nanoTime()).as("consumer 배정 기한").isLessThan(deadline);
            Thread.sleep(500);
        }
        return app;
    }

    private static boolean consumerAssigned() {
        try {
            var group = admin.describeConsumerGroups(List.of(GROUP)).all().get(2, TimeUnit.SECONDS).get(GROUP);
            int assigned = group.members().stream().mapToInt(member -> member.assignment().topicPartitions().size()).sum();
            return group.state() == ConsumerGroupState.STABLE && assigned == PARTITIONS;
        } catch (Exception notYet) {
            return false;
        }
    }

    private static void seedCatalog() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        Long brandId = postAdmin(client, "/api-admin/v1/brands", Map.of("name", "리허설 브랜드", "description", "합성"))
            .path("data").path("id").asLong();
        List<Long> products = new ArrayList<>();
        for (int i = 0; i < PRODUCTS; i++) {
            products.add(postAdmin(client, "/api-admin/v1/products", Map.of("brandId", brandId, "name", "양말 " + i,
                "price", 1_000L, "initialStockQuantity", 1_000_000)).path("data").path("id").asLong());
        }
        productIds = products;
        try (Statement statement = db.createStatement()) {
            for (int i = 0; i < USERS; i++) {
                statement.addBatch("INSERT INTO users (created_at, updated_at) VALUES (NOW(6), NOW(6))");
            }
            statement.executeBatch();
            statement.executeUpdate("INSERT INTO point_balance (user_id, balance, created_at, updated_at) "
                + "SELECT id, 0, NOW(6), NOW(6) FROM users");
            List<Long> users = new ArrayList<>();
            try (var rows = statement.executeQuery("SELECT id FROM users")) {
                while (rows.next()) {
                    users.add(rows.getLong(1));
                }
            }
            userIds = users;
        }
    }

    private static JsonNode postAdmin(HttpClient client, String path, Object body) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(apiUrl + path))
            .header("Content-Type", "application/json").header("X-USER-ROLE", "ADMIN")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s 응답: %s", path, response.body()).isEqualTo(201);
        return MAPPER.readTree(response.body());
    }
}
