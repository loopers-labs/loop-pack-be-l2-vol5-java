package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.support.order.SpringAppProcess;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.testcontainers.KafkaTestBroker;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.ConsumerGroupState;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주문 API부터 구매 활동 저장까지 전체 경로에 부하를 주고, 도착률을 올리면서 어느 단계가 먼저 쌓이는지 본다.
 * <p>
 * k6가 사용자처럼 주문 생성 → 결제 확정 → PG 결과 알림을 보낸다. 가짜 PG는 생성에 PENDING으로 답하고, 조회에는 성공으로 답한다.
 * 주문 API와 구매 활동 앱은 별도 프로세스이고, 연결 풀은 운영 설정(40)으로 띄운다. relay 설정은 두 가지로 비교한다.
 * 단계마다 DB를 1초마다 읽어 주문 확정 → Outbox 발행 → 구매 활동 반영의 처리 속도와 쌓인 양을 나눈다.
 * 같은 컴퓨터에서 k6·앱 두 개·가짜 PG·MySQL·Kafka broker 1대를 함께 돌린 결과다.
 * 실행: {@code ./gradlew :apps:commerce-api:experimentTest --tests '*OrderFlowLoadExperimentTest'} (k6 필요)
 */
@Tag("experiment")
class OrderFlowLoadExperimentTest {
    private static final String TOPIC = "order-events-load";
    private static final String GROUP = "purchase-activity";
    private static final int PARTITIONS = 3;
    private static final int USERS = 2_000;
    private static final int PRODUCTS = 20;
    private static final Duration APP_DEADLINE = Duration.ofSeconds(120);
    private static final Duration TAIL = Duration.ofSeconds(120);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 단계: 도착률(흐름/초), 시간(초). 첫 단계는 워밍업이다. */
    private static final List<int[]> STAGES = List.of(new int[] {5, 20}, new int[] {20, 30}, new int[] {40, 30},
        new int[] {80, 30}, new int[] {120, 30}, new int[] {200, 30});
    private static final List<String> OPERATION_POOL = List.of(
        "--datasource.mysql-jpa.main.maximum-pool-size=40", "--datasource.mysql-jpa.main.minimum-idle=30");

    enum RelaySetting {
        /** 지금 제품 설정. 계산상 처리 한계 초당 약 16건. */
        PRODUCT_10S_200(10_000, 200),
        /** 쉬는 시간을 줄이고 회차를 키운 설정. 직렬 relay의 상한(초당 약 85~100건)에 가깝다. */
        NEAR_SERIAL_CEILING_1S_1000(1_000, 1_000);

        private final long fixedDelayMs;
        private final int batchSize;

        RelaySetting(long fixedDelayMs, int batchSize) {
            this.fixedDelayMs = fixedDelayMs;
            this.batchSize = batchSize;
        }
    }

    private static MySQLContainer<?> mysql;
    private static KafkaTestBroker broker;
    private static FakePaymentServer pg;
    private static SpringAppProcess.Database database;
    private static Connection db;
    private static AdminClient admin;
    private static SpringAppProcess streamer;
    private static List<Long> userIds;
    private static List<Long> productIds;

    private final HttpClient http = HttpClient.newHttpClient();
    private String apiUrl;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0")).withDatabaseName("order_flow_load")
            .withCommand("--max-connections=500");
        mysql.start();
        broker = new KafkaTestBroker();
        pg = new FakePaymentServer();
        pg.settleCreatedAs("SUCCESS");
        database = new SpringAppProcess.Database(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        db = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
        admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers()));
        admin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS, (short) 1), new NewTopic(TOPIC + "-dlt", 1, (short) 1)))
            .all().get(10, TimeUnit.SECONDS);
    }

    @AfterAll
    static void stopInfrastructure() throws Exception {
        try {
            if (streamer != null) {
                streamer.close();
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
    @EnumSource(RelaySetting.class)
    void findFirstBottleneck(RelaySetting relay) throws Exception {
        try (SpringAppProcess api = startApi(relay)) {
            ensureCatalog();
            ensureStreamer();
            // 앞 설정이 남긴 PENDING을 relay가 먼저 보내면 이번 설정의 주문이 뒤로 밀려 측정이 섞인다. 측정 대상이 아니므로 치운다.
            try (Statement statement = db.createStatement()) {
                statement.executeUpdate("DELETE FROM order_outbox WHERE status = 'PENDING'");
            }
            long baseOrderId = scalar("SELECT COALESCE(MAX(id), 0) FROM orders");

            List<long[]> samples = new CopyOnWriteArrayList<>(); // epochMs, 확정, PENDING, PUBLISHED, 반영
            AtomicBoolean sampling = new AtomicBoolean(true);
            Thread sampler = Thread.ofPlatform().start(() -> sample(baseOrderId, samples, sampling));
            Path work = Files.createTempDirectory("order-flow-load");
            try {
                runK6(work);
                awaitDrained(baseOrderId);
            } finally {
                sampling.set(false);
                sampler.join(5_000);
            }

            List<JsonNode> flows = flows(work.resolve("console.log"));
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("relay", relay);
            report.put("stages", stageReports(flows, samples));
            report.put("afterLoad", Map.of(
                "confirmed", scalar("SELECT COUNT(*) FROM orders WHERE status = 'CONFIRMED' AND id > " + baseOrderId),
                "pending", scalar("SELECT COUNT(*) FROM order_outbox WHERE status = 'PENDING' AND order_id > " + baseOrderId),
                "consumed", scalar("SELECT COUNT(*) FROM processed_order_confirmation WHERE order_id > " + baseOrderId),
                "duplicateActivities", scalar("SELECT COUNT(*) - COUNT(DISTINCT order_id) FROM processed_order_confirmation "
                    + "WHERE order_id > " + baseOrderId)));
            System.out.println("ORDER_FLOW_LOAD_EXPERIMENT " + MAPPER.writeValueAsString(report));

            assertThat(flows).as("k6 흐름 기록").isNotEmpty();
            assertThat(report.get("afterLoad")).isNotNull();
        }
    }

    // ---- 부하 ----

    private void runK6(Path work) throws Exception {
        Path config = work.resolve("config.json");
        List<Map<String, Integer>> stages = STAGES.stream().map(stage -> Map.of("rate", stage[0], "seconds", stage[1])).toList();
        Files.writeString(config, MAPPER.writeValueAsString(Map.of("baseUrl", apiUrl, "userIds", userIds,
            "productIds", productIds, "stages", stages)));
        Path script = Path.of(System.getProperty("experiment.repositoryRoot"), "scripts", "week7-order-flow.k6.js");
        Process k6 = new ProcessBuilder(k6Binary(), "run", "--quiet", "--console-output=" + work.resolve("console.log"),
            "-e", "CONFIG_FILE=" + config, "-e", "SUMMARY_FILE=" + work.resolve("summary.json"), script.toString())
            .redirectErrorStream(true).redirectOutput(work.resolve("k6.out").toFile()).start();
        int loadSeconds = STAGES.stream().mapToInt(stage -> stage[1]).sum();
        assertThat(k6.waitFor(loadSeconds + 120L, TimeUnit.SECONDS)).as("k6 종료").isTrue();
        assertThat(k6.exitValue()).as("k6 실행:\n%s", Files.readString(work.resolve("k6.out"))).isZero();
    }

    private static String k6Binary() {
        String configured = System.getenv("K6");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return Files.isExecutable(Path.of("/opt/homebrew/bin/k6")) ? "/opt/homebrew/bin/k6" : "k6";
    }

    /** 부하가 끝난 뒤 확정된 주문이 모두 반영되거나 TAIL이 지날 때까지 기다린다. 쌓인 양이 빠지는 속도를 보려는 것이다. */
    private void awaitDrained(long baseOrderId) throws Exception {
        long deadline = System.nanoTime() + TAIL.toNanos();
        while (System.nanoTime() < deadline) {
            long confirmed = scalar("SELECT COUNT(*) FROM orders WHERE status = 'CONFIRMED' AND id > " + baseOrderId);
            long consumed = scalar("SELECT COUNT(*) FROM processed_order_confirmation WHERE order_id > " + baseOrderId);
            if (confirmed > 0 && consumed >= confirmed) {
                return;
            }
            Thread.sleep(1_000);
        }
    }

    // ---- 관찰 ----

    private void sample(long baseOrderId, List<long[]> samples, AtomicBoolean sampling) {
        try (Connection sampleDb = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
             Statement statement = sampleDb.createStatement()) {
            while (sampling.get()) {
                long now = System.currentTimeMillis();
                samples.add(new long[] {now,
                    scalar(statement, "SELECT COUNT(*) FROM orders WHERE status = 'CONFIRMED' AND id > " + baseOrderId),
                    scalar(statement, "SELECT COUNT(*) FROM order_outbox WHERE status = 'PENDING' AND order_id > " + baseOrderId),
                    scalar(statement, "SELECT COUNT(*) FROM order_outbox WHERE status = 'PUBLISHED' AND order_id > " + baseOrderId),
                    scalar(statement, "SELECT COUNT(*) FROM processed_order_confirmation WHERE order_id > " + baseOrderId)});
                Thread.sleep(Math.max(0, 1_000 - (System.currentTimeMillis() - now)));
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static List<JsonNode> flows(Path consoleLog) throws IOException {
        Pattern message = Pattern.compile("msg=\"(.*)\"");
        List<JsonNode> flows = new ArrayList<>();
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

    /**
     * 단계마다 API 쪽(시작한 흐름, 성공, 응답 시간)과 뒤쪽(확정·발행·반영 속도, 단계 끝의 쌓인 양)을 함께 남긴다.
     * 단계 경계는 첫 흐름의 시작 시각에서 단계 시간을 더해 정한다.
     */
    private static List<Map<String, Object>> stageReports(List<JsonNode> flows, List<long[]> samples) {
        long origin = flows.stream().mapToLong(flow -> flow.path("startedEpochMs").asLong()).min().orElseThrow();
        List<Map<String, Object>> reports = new ArrayList<>();
        long stageStart = origin;
        for (int index = 0; index < STAGES.size(); index++) {
            int[] stage = STAGES.get(index);
            long stageEnd = stageStart + stage[1] * 1_000L;
            int stageIndex = index;
            List<JsonNode> mine = flows.stream().filter(flow -> flow.path("stage").asInt() == stageIndex).toList();
            long[] first = sampleAt(samples, stageStart);
            long[] last = sampleAt(samples, stageEnd);
            double seconds = Math.max(1, last[0] - first[0]) / 1_000.0;

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("rate", stage[0]);
            report.put("seconds", stage[1]);
            report.put("planned", stage[0] * stage[1]);
            report.put("started", mine.size());
            report.put("ok", mine.stream().filter(flow -> flow.path("ok").asBoolean()).count());
            report.put("failedAt", failures(mine));
            report.put("flowMs", percentiles(mine, "flowMs"));
            report.put("createMs", percentiles(mine, "createMs"));
            report.put("confirmMs", percentiles(mine, "confirmMs"));
            report.put("callbackMs", percentiles(mine, "callbackMs"));
            report.put("confirmedPerSecond", round((last[1] - first[1]) / seconds));
            report.put("publishedPerSecond", round((last[3] - first[3]) / seconds));
            report.put("consumedPerSecond", round((last[4] - first[4]) / seconds));
            report.put("pendingAtEnd", last[2]);
            report.put("publishedNotConsumedAtEnd", last[3] - last[4]);
            reports.add(report);
            stageStart = stageEnd;
        }
        return reports;
    }

    private static long[] sampleAt(List<long[]> samples, long epochMs) {
        return samples.stream().min(Comparator.comparingLong(sample -> Math.abs(sample[0] - epochMs))).orElseThrow();
    }

    private static Map<String, Long> failures(List<JsonNode> flows) {
        Map<String, Long> failures = new LinkedHashMap<>();
        flows.stream().filter(flow -> flow.has("failedAt")).forEach(flow -> failures.merge(
            flow.path("failedAt").asText() + ":" + flow.path(flow.path("failedAt").asText() + "Status").asText("-"), 1L, Long::sum));
        return failures;
    }

    private static Map<String, Long> percentiles(List<JsonNode> flows, String field) {
        List<Long> values = flows.stream().filter(flow -> flow.has(field)).map(flow -> Math.round(flow.path(field).asDouble()))
            .sorted().toList();
        if (values.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("p50", values.get((int) Math.ceil(0.50 * values.size()) - 1));
        result.put("p95", values.get((int) Math.ceil(0.95 * values.size()) - 1));
        result.put("p99", values.get((int) Math.ceil(0.99 * values.size()) - 1));
        result.put("max", values.getLast());
        return result;
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    // ---- 앱·데이터 준비 ----

    private SpringAppProcess startApi(RelaySetting relay) throws Exception {
        int port = SpringAppProcess.freePort();
        apiUrl = "http://127.0.0.1:" + port;
        List<String> arguments = new ArrayList<>(OPERATION_POOL);
        arguments.add("--commerce.order-events.relay.scheduling-enabled=true");
        arguments.add("--commerce.order-events.relay.fixed-delay-ms=" + relay.fixedDelayMs);
        arguments.add("--commerce.order-events.relay.batch-size=" + relay.batchSize);
        SpringAppProcess app = SpringAppProcess.currentApi(database, broker.bootstrapServers(), TOPIC, port, pg.baseUrl(),
            arguments);
        long deadline = System.nanoTime() + APP_DEADLINE.toNanos();
        while (true) {
            assertThat(app.isAlive()).as("API 기동 실패:\n%s", app.logTail()).isTrue();
            try {
                if (http.send(HttpRequest.newBuilder(URI.create(apiUrl + "/api/v1/products")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return app;
                }
            } catch (IOException notReady) {
                // 아직 기동 중
            }
            assertThat(System.nanoTime()).as("API 기동 기한").isLessThan(deadline);
            Thread.sleep(500);
        }
    }

    private void ensureStreamer() throws Exception {
        if (streamer != null && streamer.isAlive()) {
            return;
        }
        streamer = SpringAppProcess.streamer(database, broker.bootstrapServers(), TOPIC, OPERATION_POOL);
        long deadline = System.nanoTime() + APP_DEADLINE.toNanos();
        while (!consumerAssigned()) {
            assertThat(streamer.isAlive()).as("구매 활동 앱 기동 실패:\n%s", streamer.logTail()).isTrue();
            assertThat(System.nanoTime()).as("consumer 배정 기한").isLessThan(deadline);
            Thread.sleep(500);
        }
    }

    private boolean consumerAssigned() {
        try {
            var group = admin.describeConsumerGroups(List.of(GROUP)).all().get(10, TimeUnit.SECONDS).get(GROUP);
            int assigned = group.members().stream().mapToInt(member -> member.assignment().topicPartitions().size()).sum();
            return group.state() == ConsumerGroupState.STABLE && assigned == PARTITIONS;
        } catch (Exception notYet) {
            return false;
        }
    }

    /** 사용자와 상품을 한 번만 만든다. 한 상품에 주문이 몰려 재고 행 하나가 병목이 되지 않도록 상품을 여러 개 둔다. */
    private void ensureCatalog() throws Exception {
        if (productIds != null) {
            return;
        }
        Long brandId = postAdmin("/api-admin/v1/brands", Map.of("name", "부하 브랜드", "description", "합성"))
            .path("data").path("id").asLong();
        List<Long> products = new ArrayList<>();
        for (int i = 0; i < PRODUCTS; i++) {
            products.add(postAdmin("/api-admin/v1/products", Map.of("brandId", brandId, "name", "양말 " + i, "price", 1_000L,
                "initialStockQuantity", 1_000_000)).path("data").path("id").asLong());
        }
        productIds = products;
        try (Statement statement = db.createStatement()) {
            long before = scalar("SELECT COALESCE(MAX(id), 0) FROM users");
            for (int i = 0; i < USERS; i++) {
                statement.addBatch("INSERT INTO users (created_at, updated_at) VALUES (NOW(6), NOW(6))");
            }
            statement.executeBatch();
            statement.executeUpdate("INSERT INTO point_balance (user_id, balance, created_at, updated_at) "
                + "SELECT id, 0, NOW(6), NOW(6) FROM users WHERE id > " + before);
            List<Long> users = new ArrayList<>();
            try (var rows = statement.executeQuery("SELECT id FROM users WHERE id > " + before)) {
                while (rows.next()) {
                    users.add(rows.getLong(1));
                }
            }
            userIds = users;
        }
    }

    private JsonNode postAdmin(String path, Object body) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(apiUrl + path))
            .header("Content-Type", "application/json").header("X-USER-ROLE", "ADMIN")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s 응답: %s", path, response.body()).isEqualTo(201);
        return MAPPER.readTree(response.body());
    }

    private static long scalar(String sql) throws SQLException {
        try (Statement statement = db.createStatement()) {
            return scalar(statement, sql);
        }
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        try (var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
