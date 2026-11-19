package com.loopers.application.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.support.preservation.PreservedMySql;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.GenericContainer;

final class WeeklyRankingFlowLab implements AutoCloseable {
    static final String TOPIC = "ranking-flow-orders";
    static final String ACTIVITY_GROUP = "ranking-flow-activity";
    private static final List<TopicPartition> PARTITIONS = List.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1));
    // The PG fixture uses DROP callbacks and the API actively reconciles over HTTP.
    private static final String CALLBACK = "http://127.0.0.1:1/api/v1/payments/callback";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Path logs;
    private final String broker;
    private final String redisHost;
    private final int redisPort;
    private final PreservedMySql database;
    private final Admin admin;
    final JdbcTemplate jdbc;

    WeeklyRankingFlowLab(Path logs, PreservedMySql database, String broker, GenericContainer<?> redis) throws SQLException {
        this.logs = logs;
        this.database = database;
        this.broker = broker;
        this.redisHost = redis.getHost();
        this.redisPort = redis.getMappedPort(6379);
        var dataSource = new DriverManagerDataSource(database.jdbcUrl(), "root", "application");
        jdbc = new JdbcTemplate(dataSource);
        prepareSchema();
        admin = Admin.create(Map.of("bootstrap.servers", broker));
    }

    private void prepareSchema() throws SQLException {
        RankingProcessLab.prepare(database);
        try (var connection = database.connectAsAdministrator()) {
            for (String script : List.of("001-pg-schema.sql", "002-pg-transactions.sql")) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(System.getProperty("ranking.flow.schema-directory"), script)));
            }
        }
        jdbc.update("INSERT INTO users (id) VALUES (1)");
        jdbc.update("""
            INSERT INTO brands (id, deleted, created_at, updated_at, name)
            VALUES (1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'Ranking flow')
            """);
        jdbc.update("""
            INSERT INTO products (id, deleted, stock, brand_id, created_at, updated_at, price, name)
            VALUES (7, 0, 20, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 1000, 'Product 7'),
                   (8, 0, 20, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 2000, 'Product 8')
            """);
    }

    void initializeTopic() throws Exception {
        admin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS.size(), (short) 1))).all().get(15, TimeUnit.SECONDS);
    }

    Event eventAt(long order, int offset) {
        var output = jdbc.queryForMap("SELECT event_id, payload FROM order_event_outbox WHERE order_id=?", order);
        String id = (String) output.get("event_id");
        try (var consumer = new KafkaConsumer<>(Map.of("bootstrap.servers", broker, "enable.auto.commit", false),
            new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.assign(PARTITIONS);
            PARTITIONS.forEach(partition -> consumer.seek(partition, offset));
            var result = new ArrayList<Event>();
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (Long.toString(order).equals(record.key()) && record.offset() == offset) {
                        assertThat(record.value()).as("broker payload matches the original Outbox bytes")
                            .isEqualTo((byte[]) output.get("payload"));
                        assertThat(json.readTree(record.value()).path("eventId").asText()).isEqualTo(id);
                        result.add(new Event(id, new TopicPartition(record.topic(), record.partition()), record.offset()));
                    }
                }
                assertThat(result).hasSize(1);
            });
            return result.getFirst();
        }
    }

    RankingProcessLab.App startPg() throws IOException {
        return RankingProcessLab.start(logs, "pg", "ranking.flow.pg-jar", "UTC",
            "--spring.profiles.active=payment-lab", "--server.port=0", "--pg.callback-url=" + CALLBACK,
            "--datasource.mysql-jpa.main.jdbc-url=" + database.jdbcUrl().replace("/loopers", "/loopers_test_pg"));
    }

    RankingProcessLab.App startApi(String name, int pgPort) throws IOException {
        // Match CommerceApiApplication's JVM default from startup, before JDBC initializes.
        return RankingProcessLab.start(logs, name, "ranking.flow.api-jar", "Asia/Seoul", database.arguments(
            "--spring.profiles.active=payment-lab", "--server.address=127.0.0.1", "--server.port=0", "--management.server.port=-1",
            "--pg.client.base-url=http://127.0.0.1:" + pgPort, "--pg.client.callback-url=" + CALLBACK,
            "--payment.recovery.auto-start-enabled=false", "--scheduler.like-count.enabled=false", "--order-events.relay.enabled=true",
            "--spring.kafka.bootstrap-servers=" + broker, "--order-events.topic=" + TOPIC,
            "--REDIS_MASTER_HOST=" + redisHost, "--REDIS_MASTER_PORT=" + redisPort,
            "--datasource.redis.master.host=" + redisHost, "--datasource.redis.master.port=" + redisPort,
            "--datasource.redis.replicas[0].host=" + redisHost, "--datasource.redis.replicas[0].port=" + redisPort));
    }

    long confirmOrder(int apiPort, String items) throws Exception {
        long order = request(apiPort, "/api/v1/orders", "{\"items\":" + items + "}", 201).path("orderId").asLong();
        assertThat(order).isPositive();
        String payment = """
            {"paymentAttemptKey":"%s","cardType":"TEST","cardNo":"0000-0000-0000-0000",
             "testOptions":{"preset":"NORMAL_SUCCESS","overrides":{"callbackMode":"DROP"}}}
            """.formatted(UUID.randomUUID());
        assertThat(request(apiPort, "/api/v1/orders/" + order + "/confirm", payment, 202).path("status").asText())
            .isEqualTo("PAYMENT_PENDING");
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertThat(jdbc.queryForList("""
            SELECT t.status FROM loopers_test_pg.pg_transactions t
            JOIN payment_attempts p ON p.provider_order_id=t.provider_order_id WHERE p.order_id=?
            """, String.class, order)).containsExactly("SUCCESS"));
        assertThat(request(apiPort, "/api/v1/orders/" + order + "/payment/reconcile", "", 200).path("status").asText())
            .isEqualTo("CONFIRMED");
        var paymentFacts = jdbc.queryForMap("""
            SELECT o.status AS order_status, r.payment_method, p.transaction_key, t.status AS pg_status
            FROM orders o JOIN order_records r ON r.order_id=o.id JOIN payment_attempts p ON p.order_id=o.id
            JOIN loopers_test_pg.pg_transactions t ON t.provider_order_id=p.provider_order_id
                AND t.transaction_key=p.transaction_key
            WHERE o.id=?
            """, order);
        assertThat(paymentFacts).containsEntry("order_status", "CONFIRMED")
            .containsEntry("payment_method", "PG").containsEntry("pg_status", "SUCCESS");
        System.out.printf("C07 PG order=%d transaction=%s status=SUCCESS%n", order, paymentFacts.get("transaction_key"));
        return order;
    }

    JsonNode ranking(int apiPort, LocalDate week) throws Exception {
        return request(apiPort, "/api/v1/rankings/weekly?periodStart=" + week, null, 200);
    }

    private JsonNode request(int port, String route, String body, int expectedStatus) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route))
            .header("X-USER-ID", "1").timeout(Duration.ofSeconds(15));
        if (body != null) {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s: %s", route, response.body()).isEqualTo(expectedStatus);
        return json.readTree(response.body()).path("data");
    }

    @Override
    public void close() {
        admin.close(Duration.ofSeconds(3));
        http.close();
    }

    record Event(String id, TopicPartition partition, long offset) {}

}
