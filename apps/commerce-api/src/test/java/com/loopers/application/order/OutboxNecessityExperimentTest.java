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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 사고를 6주차(commit 뒤 바로 send) 앱과 7주차(Outbox + relay) 앱에 똑같이 일으켜 사건이 남는지 비교한다.
 * <p>
 * 6주차 앱은 {@code scripts/build-w6-revision.sh}가 만든 실행물이고, 7주차 앱은 현재 코드다. 두 앱과 구매 활동 앱은
 * 같은 MySQL·Kafka(broker 1대)를 쓴다. 주문은 두 앱의 실제 주문 API와 로컬 PG fixture로 확정한다.
 * broker 장애는 broker 프로세스를 내린 것이다(연결 거부). 컨테이너를 얼리면(pause) 보내던 요청이 broker에 남아
 * 되살아난 뒤 기록되므로 유실을 재현하지 못한다. 앱 강제 종료는 SIGKILL이다.
 * 전송 기한(120초)을 기다리면 실험이 너무 길어져서, O1에서만 두 앱 모두 producer의 전송 기한을 15초로 줄인다.
 * 실행: {@code ./gradlew :apps:commerce-api:experimentTest --tests '*OutboxNecessityExperimentTest'}
 */
@Tag("experiment")
class OutboxNecessityExperimentTest {
    private static final String TOPIC = "order-events-necessity";
    private static final String GROUP = "purchase-activity";
    private static final int PARTITIONS = 3;
    private static final Duration APP_DEADLINE = Duration.ofSeconds(120);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SHORT_DELIVERY = List.of(
        "--spring.kafka.producer.properties.delivery.timeout.ms=15000",
        "--spring.kafka.producer.properties.request.timeout.ms=5000");
    private static final List<String> RELAY_ON = List.of("--commerce.order-events.relay.scheduling-enabled=true");

    private static MySQLContainer<?> mysql;
    private static KafkaTestBroker broker;
    private static FakePaymentServer pg;
    private static SpringAppProcess.Database database;
    private static Connection db;
    private static AdminClient admin;
    private static SpringAppProcess streamer;
    private static int nextTransaction = 1;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<SpringAppProcess> apps = new ArrayList<>();
    private String apiUrl;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0")).withDatabaseName("outbox_necessity");
        mysql.start();
        broker = new KafkaTestBroker();
        pg = new FakePaymentServer();
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

    /** O1. broker 장애가 producer의 전송 기한보다 오래 이어지면? */
    @Test
    void brokerOutageLongerThanDeliveryTimeout() throws Exception {
        Map<String, Object> w6 = brokerOutage("W6", w6App(SHORT_DELIVERY));
        Map<String, Object> w7 = brokerOutage("W7", currentApp(concat(SHORT_DELIVERY, RELAY_ON)));
        print("O1_broker_outage_longer_than_delivery_timeout", w6, w7);

        assertThat((int) w6.get("activities")).as("6주차는 전송 기한이 지나면 사건을 잃는다").isZero();
        assertThat((boolean) w6.get("failureLogged")).isTrue();
        assertThat((int) w7.get("activities")).isEqualTo(3);
    }

    /** O2. broker가 응답하지 않는 동안 주문 앱이 강제 종료되면? */
    @Test
    void appKilledWhileBrokerIsSlow() throws Exception {
        Map<String, Object> w6 = appKilled("W6", w6App(List.of()));
        Map<String, Object> w7 = appKilled("W7", currentApp(RELAY_ON));
        print("O2_app_killed_while_broker_slow", w6, w7);

        assertThat((int) w6.get("activities")).as("6주차는 producer 메모리의 사건이 앱과 함께 사라진다").isZero();
        assertThat((int) w7.get("activities")).isEqualTo(3);
    }

    /** O3. consumer가 강제 종료되는 사고는 Outbox 없이도 회복될까? 6주차 앱으로만 확인한다. */
    @Test
    void consumerCrashRecoversEvenWithoutOutbox() throws Exception {
        ensureStreamer();
        SpringAppProcess app = startApi(w6App(List.of()));
        try {
            ensureCatalog();
            warmUp();
            streamer.kill();
            List<Long> orders = confirmOrders(3, "o3");
            int whileDown = activities(orders);
            streamer = null;
            ensureStreamer();
            long caughtUp = awaitMillis(Duration.ofSeconds(120), () -> activities(orders) == 3);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("activitiesWhileConsumerDown", whileDown);
            result.put("activities", activities(orders));
            result.put("completions", completions(orders));
            result.put("caughtUpAfterRestartMillis", caughtUp);
            print("O3_consumer_crash_without_outbox", result, Map.of());

            assertThat(whileDown).isZero();
            assertThat(activities(orders)).isEqualTo(3);
            assertThat(completions(orders)).isEqualTo(3);
        } finally {
            close(app);
        }
    }

    // ---- 사고 재현 ----

    private Map<String, Object> brokerOutage(String version, AppStarter starter) throws Exception {
        ensureStreamer();
        SpringAppProcess app = startApi(starter);
        try {
            ensureCatalog();
            warmUp(); // producer가 broker 정보를 기억하게 한 뒤 장애를 일으킨다
            broker.stopProcess();
            List<Long> orders;
            try {
                orders = confirmOrders(3, version + "-o1");
                Thread.sleep(Duration.ofSeconds(40).toMillis()); // 전송 기한(15초)보다 길게 내려 둔다
            } finally {
                startBrokerAgain();
            }
            awaitMillis(Duration.ofSeconds(90), () -> activities(orders) == 3);
            Map<String, Object> result = observe(version, orders);
            result.put("failureLogged", app.logContains("Order event publication failed"));
            return result;
        } finally {
            close(app);
        }
    }

    private Map<String, Object> appKilled(String version, AppStarter starter) throws Exception {
        ensureStreamer();
        SpringAppProcess app = startApi(starter);
        List<Long> orders;
        try {
            ensureCatalog();
            warmUp();
            broker.stopProcess();
            try {
                orders = confirmOrders(3, version + "-o2");
                app.kill(); // 보내지 못한 사건을 메모리에 든 채로 강제 종료
            } finally {
                startBrokerAgain();
            }
        } finally {
            close(app);
        }
        SpringAppProcess restarted = startApi(starter); // 같은 버전의 앱을 다시 띄운다
        try {
            awaitMillis(Duration.ofSeconds(90), () -> activities(orders) == 3);
            return observe(version, orders);
        } finally {
            close(restarted);
        }
    }

    /** 내린 broker를 다시 올리고, metadata 조회가 될 때까지 기다린다. */
    private void startBrokerAgain() throws Exception {
        broker.startAgain();
        long ready = awaitMillis(Duration.ofSeconds(90), () -> {
            admin.describeCluster().nodes().get(5, TimeUnit.SECONDS);
            return admin.listTopics().names().get(5, TimeUnit.SECONDS).contains(TOPIC);
        });
        assertThat(ready).as("broker 재기동").isNotNegative();
    }

    private Map<String, Object> observe(String version, List<Long> orders) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version);
        result.put("orders", orders);
        result.put("confirmed", count("SELECT COUNT(*) FROM orders WHERE status = 'CONFIRMED' AND id IN " + in(orders)));
        result.put("outboxRows", tableExists("order_outbox")
            ? count("SELECT COUNT(*) FROM order_outbox WHERE order_id IN " + in(orders)) : 0);
        result.put("activities", activities(orders));
        result.put("completions", completions(orders));
        return result;
    }

    /** 정상 상태에서 한 건을 끝까지 보내 producer의 broker 정보와 consumer 배정을 확인한다. */
    private void warmUp() throws Exception {
        List<Long> warm = confirmOrders(1, "warm");
        awaitMillis(Duration.ofSeconds(60), () -> activities(warm) == 1);
    }

    // ---- 주문 API ----

    private static Long userId;
    private static Long productId;

    private void ensureCatalog() throws Exception {
        if (productId != null) {
            return;
        }
        userId = seedUser();
        Long brandId = post("/api-admin/v1/brands", null, Map.of("name", "비교 브랜드", "description", "합성"), 201)
            .path("data").path("id").asLong();
        productId = post("/api-admin/v1/products", null, Map.of("brandId", brandId, "name", "양말", "price", 1_000L,
            "initialStockQuantity", 1_000), 201).path("data").path("id").asLong();
    }

    private List<Long> confirmOrders(int count, String keyPrefix) throws Exception {
        List<Long> orders = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Long orderId = post("/api/v1/orders", userId, Map.of("items", List.of(Map.of("productId", productId, "quantity", 1))), 201)
                .path("data").path("id").asLong();
            // 결제 요청 키는 주문마다 달라야 한다. 같은 키를 다른 주문에 쓰면 앱이 거절한다.
            post("/api/v1/orders/" + orderId + "/confirm", userId, Map.of("paymentAttemptKey", keyPrefix + "-" + orderId,
                "cardType", "TEST", "cardNo", "0000-0000-0000-0000"), 202);
            pg.changeStatus("TEST-TX-" + nextTransaction++, "SUCCESS");
            post("/api/v1/orders/" + orderId + "/payment/reconcile", userId, Map.of(), 200);
            orders.add(orderId);
        }
        return orders;
    }

    private JsonNode post(String path, Long user, Object body, int expected) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiUrl + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (user != null) {
            builder.header("X-USER-ID", user.toString());
        } else {
            builder.header("X-USER-ROLE", "ADMIN");
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s 응답: %s", path, response.body()).isEqualTo(expected);
        return MAPPER.readTree(response.body());
    }

    private static Long seedUser() throws SQLException {
        try (var users = db.prepareStatement("INSERT INTO users (created_at, updated_at) VALUES (NOW(6), NOW(6))",
            Statement.RETURN_GENERATED_KEYS)) {
            users.executeUpdate();
            ResultSet keys = users.getGeneratedKeys();
            keys.next();
            long id = keys.getLong(1);
            try (var points = db.prepareStatement(
                "INSERT INTO point_balance (user_id, balance, created_at, updated_at) VALUES (?, 0, NOW(6), NOW(6))")) {
                points.setLong(1, id);
                points.executeUpdate();
            }
            return id;
        }
    }

    // ---- 앱 실행 ----

    private interface AppStarter {
        SpringAppProcess start(int port) throws Exception;
    }

    private AppStarter w6App(List<String> arguments) {
        return port -> SpringAppProcess.w6Api(database, broker.bootstrapServers(), TOPIC, port, pg.baseUrl(), arguments);
    }

    private AppStarter currentApp(List<String> arguments) {
        return port -> SpringAppProcess.currentApi(database, broker.bootstrapServers(), TOPIC, port, pg.baseUrl(), arguments);
    }

    private SpringAppProcess startApi(AppStarter starter) throws Exception {
        int port = SpringAppProcess.freePort();
        apiUrl = "http://127.0.0.1:" + port;
        SpringAppProcess app = starter.start(port);
        apps.add(app);
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

    private void close(SpringAppProcess app) throws Exception {
        apps.remove(app);
        app.close();
    }

    private void ensureStreamer() throws Exception {
        if (streamer != null && streamer.isAlive()) {
            return;
        }
        streamer = SpringAppProcess.streamer(database, broker.bootstrapServers(), TOPIC);
        awaitMillis(APP_DEADLINE, this::consumerAssigned);
    }

    private boolean consumerAssigned() throws Exception {
        var group = admin.describeConsumerGroups(List.of(GROUP)).all().get(10, TimeUnit.SECONDS).get(GROUP);
        int assigned = group.members().stream().mapToInt(member -> member.assignment().topicPartitions().size()).sum();
        return group.state() == ConsumerGroupState.STABLE && assigned == PARTITIONS;
    }

    /** 조건이 될 때까지 기다린다. 기한이 지나도 실패하지 않고 돌려준다(사건을 잃는 쪽은 끝내 조건이 되지 않는다). */
    private long awaitMillis(Duration limit, Callable<Boolean> condition) throws Exception {
        long started = System.nanoTime();
        long deadline = started + limit.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.call()) {
                    return Duration.ofNanos(System.nanoTime() - started).toMillis();
                }
            } catch (Exception notYet) {
                // broker·DB가 막 돌아온 직후의 조회 실패는 다시 확인한다
            }
            Thread.sleep(500);
        }
        return -1;
    }

    // ---- 관찰 ----

    private int activities(List<Long> orders) throws SQLException {
        return tableExists("product_purchase_activity")
            ? count("SELECT COUNT(*) FROM product_purchase_activity WHERE order_id IN " + in(orders)) : 0;
    }

    private int completions(List<Long> orders) throws SQLException {
        return tableExists("processed_order_confirmation")
            ? count("SELECT COUNT(*) FROM processed_order_confirmation WHERE order_id IN " + in(orders)) : 0;
    }

    private boolean tableExists(String table) throws SQLException {
        try (var rows = db.getMetaData().getTables(null, null, table, null)) {
            return rows.next();
        }
    }

    private int count(String sql) throws SQLException {
        try (var statement = db.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static String in(List<Long> ids) {
        return ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).map(s -> "(" + s + ")").orElse("(-1)");
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static void print(String scenario, Map<String, Object> w6, Map<String, Object> w7) {
        System.out.println("OUTBOX_NECESSITY " + Map.of("scenario", scenario, "w6", w6, "w7", w7));
    }
}
