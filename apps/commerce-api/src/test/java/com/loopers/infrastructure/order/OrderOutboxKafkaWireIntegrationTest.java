package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.CommerceApiApplication;
import com.loopers.application.order.OrderConfirmedRecorder;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderOutboxAttempt;
import com.loopers.domain.order.OrderOutboxMessageVerifier;
import com.loopers.domain.order.OrderOutboxReceipt;
import com.loopers.domain.order.OrderOutboxSender;
import com.loopers.domain.order.OrderOutboxStoredMessage;
import com.loopers.infrastructure.order.OrderEventTestCleanup.Step;
import com.loopers.testsupport.LocalKafkaTestSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * O5 실제 wire(설계 W7.4-3·W7.4-4 전환표의 발행 전용). 정상 W7 컨텍스트에서 제품 recorder 로 Outbox 를 실제 commit 하고,
 * 새 SQL 로 읽은 key·payload 를 실제 sender 로 공유 로컬 Kafka 의 소유 topic 에 보낸다. broker Future metadata 와 직접 읽은 record 의
 * 위치·key·value bytes·header 를 대조하고, sender 호출이 Outbox 상태를 바꾸지 않음을 확인한다.
 *
 * <p>relay·시도 TX 는 아직 없으므로 시도 snapshot 은 테스트 입력이다. PG·업무 consumer·streamer 를 연결하지 않는다.
 * 자원: 기존 broker 검증과 같은 MySQL 컨테이너 안의 order_event_kafka 임시 스키마(시작 때 만들고 끝에 삭제), 실행 소유 topic·client.
 * 유한 예산은 broker 준비 30초(LocalKafkaTestSupport)·Future/관찰 60초·전체 300초다.
 */
@Timeout(300)
@DisplayName("O5: 저장 원문을 실제 broker 로 재직렬화 없이 보내고, Future metadata 와 실제 record 가 일치하며 Outbox 는 PENDING 으로 남는다.")
class OrderOutboxKafkaWireIntegrationTest {

    private static final String SCHEMA = "order_event_kafka";
    private static final Duration OBSERVE = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static LocalKafkaTestSupport kafka;
    private static String server;
    private static String rootPassword;
    private static boolean schemaOwned;
    private static JdbcTemplate fresh;

    @BeforeAll
    static void prepare() throws Exception {
        kafka = LocalKafkaTestSupport.connect();
        System.out.println("[O5 WIRE] bootstrap=" + kafka.bootstrapServers() + " runId=" + kafka.runId() + " broker=" + kafka.brokerInfo());
        Class.forName("com.loopers.testcontainers.MySqlTestContainersConfig");
        String url = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        rootPassword = System.getProperty("datasource.mysql-jpa.main.password");
        server = url.substring(0, url.lastIndexOf('/'));
        schemaOwned = true;
        try (var root = new SingleConnectionDataSource(server, "root", rootPassword, true)) {
            JdbcTemplate admin = new JdbcTemplate(root);
            admin.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            admin.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            admin.execute("GRANT ALL ON " + SCHEMA + ".* TO 'test'@'%'");
        }
        // 조회마다 새 연결을 여는 DataSource. 앱의 연결 풀·영속성 컨텍스트를 거치지 않는다.
        fresh = new JdbcTemplate(new DriverManagerDataSource(server + "/" + SCHEMA, "test", "test"));
    }

    /** 만든 것만 정리한다. 컨텍스트는 각 테스트가 닫은 뒤이므로 topic 삭제 전에 producer 가 남지 않는다. */
    @AfterAll
    static void release() {
        OrderEventTestCleanup.runAll(List.of(
            new Step("검증 전용 스키마 삭제", () -> {
                if (schemaOwned) {
                    try (var root = new SingleConnectionDataSource(server, "root", rootPassword, true)) {
                        new JdbcTemplate(root).execute("DROP DATABASE IF EXISTS " + SCHEMA);
                    }
                }
            }),
            new Step("Kafka 소유 topic 정리", () -> {
                if (kafka != null) {
                    kafka.close();
                }
            })));
    }

    @Test
    void sendsStoredPayloadVerbatim() throws Exception {
        String topic = kafka.createTopic("order-outbox");
        OrderConfirmed event = new OrderConfirmed(4242L, ZonedDateTime.of(2026, 11, 4, 1, 2, 3, 123_450_000, ZoneOffset.UTC),
            List.of(new OrderConfirmed.Item(11L, 2L), new OrderConfirmed.Item(12L, 1L)));

        try (ConfigurableApplicationContext context = boot(topic)) {
            OrderConfirmedRecorder recorder = context.getBean(OrderConfirmedRecorder.class);
            new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> recorder.record(event));

            Map<String, Object> before = row(event.eventId());
            OrderOutboxStoredMessage stored = new OrderOutboxStoredMessage((String) before.get("event_id"),
                ((Number) before.get("order_id")).longValue(), (String) before.get("event_type"),
                ((Number) before.get("schema_version")).intValue(), (String) before.get("message_key"), (String) before.get("payload"));
            assertThat(context.getBean(OrderOutboxMessageVerifier.class).parseAndValidate(stored)).as("저장 원문은 유효 v1").isEqualTo(event);
            Map<TopicPartition, Long> endBefore = kafka.endOffsets(topic);

            OrderOutboxAttempt attempt = new OrderOutboxAttempt(((Number) before.get("id")).longValue(), 1L, 1, stored);
            OrderOutboxReceipt receipt = context.getBean(OrderOutboxSender.class).send(attempt)
                .toCompletableFuture().get(OBSERVE.toSeconds(), TimeUnit.SECONDS);
            System.out.println("[O5 WIRE] receipt=" + receipt + " endBefore=" + endBefore);

            ConsumerRecord<byte[], byte[]> record = readAt(receipt);
            byte[] expected = stored.payload().getBytes(StandardCharsets.UTF_8);
            JsonNode value = JSON.readTree(record.value());
            System.out.println("[O5 WIRE] record topic=" + record.topic() + " partition=" + record.partition() + " offset=" + record.offset()
                + " key=" + new String(record.key(), StandardCharsets.UTF_8) + " valueBytes=" + record.value().length
                + " headers=" + record.headers().toArray().length + " payloadEqual=" + Arrays.equals(record.value(), expected));
            Map<String, Object> after = row(event.eventId());

            assertAll(
                () -> assertThat(receipt.topic()).isEqualTo(topic),
                () -> assertThat(record.topic()).isEqualTo(receipt.topic()),
                () -> assertThat(record.partition()).isEqualTo(receipt.partition()),
                () -> assertThat(record.offset()).isEqualTo(receipt.offset()),
                () -> assertThat(receipt.offset()).as("새 topic 의 첫 record").isEqualTo(endBefore.get(new TopicPartition(topic, receipt.partition()))),
                () -> assertThat(new String(record.key(), StandardCharsets.UTF_8)).isEqualTo(stored.messageKey()).isEqualTo("4242"),
                () -> assertThat(record.value()).as("value bytes = 저장 원문 UTF-8").isEqualTo(expected),
                () -> assertThat(value.isObject()).as("인용된 JSON 문자열이 아닌 object").isTrue(),
                () -> assertThat(value.path("eventId").asText()).isEqualTo("order-confirmed:4242"),
                () -> assertThat(record.headers().toArray()).as("type header 등 header 없음").isEmpty(),
                () -> assertThat(kafka.readAll(topic)).as("topic 의 record 는 이 하나").hasSize(1),
                () -> assertThat(before.get("status")).isEqualTo("PENDING"),
                () -> assertThat(before).as("비교 대상은 deleted_at 을 포함한 전체 16개 열").hasSize(16)
                    .containsKey("deleted_at").containsEntry("deleted_at", null),
                () -> assertThat(after).as("sender 는 Outbox 행을 바꾸지 않음").isEqualTo(before),
                () -> assertThat(after.get("published_at")).isNull(),
                () -> assertThat(after.get("deleted_at")).isNull()
            );
        }
    }

    /** receipt 위치의 record 를 group 없이 byte 그대로 읽는다. 관찰 예산 안에 읽지 못하면 실패다. */
    private static ConsumerRecord<byte[], byte[]> readAt(OrderOutboxReceipt receipt) {
        Map<String, Object> config = kafka.consumerConfig(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        TopicPartition partition = new TopicPartition(receipt.topic(), receipt.partition());
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(config)) {
            consumer.assign(List.of(partition));
            consumer.seek(partition, receipt.offset());
            long deadline = System.nanoTime() + OBSERVE.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    if (record.offset() == receipt.offset()) {
                        return record;
                    }
                }
            }
        }
        throw new IllegalStateException("receipt 위치의 record 를 관찰 예산 안에 읽지 못했습니다. [receipt = " + receipt + "]");
    }

    private static Map<String, Object> row(String eventId) {
        return fresh.queryForMap("SELECT id, event_id, order_id, event_type, schema_version, message_key, payload, status, attempt_count,"
            + " cycle_attempt_count, next_attempt_at, last_error, published_at, created_at, updated_at, deleted_at"
            + " FROM order_confirmed_outbox WHERE event_id = ?", eventId);
    }

    /** 정상 W7 컨텍스트. bootstrap·topic·전용 producer client.id 만 이 실행의 값으로 덮는다. W6 초기화기를 쓰지 않는다. */
    private static ConfigurableApplicationContext boot(String topic) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        return new SpringApplicationBuilder(CommerceApiApplication.class).profiles("test").run(
            "--server.port=" + port, "--management.server.port=0", "--spring.jpa.hibernate.ddl-auto=create",
            "--datasource.mysql-jpa.main.jdbc-url=" + server + "/" + SCHEMA,
            "--spring.kafka.bootstrap-servers=" + kafka.bootstrapServers(),
            "--spring.kafka.admin.properties.bootstrap.servers=" + kafka.bootstrapServers(),
            "--commerce.order-events.topic=" + topic,
            "--commerce.order-events.producer-properties[client.id]=order-outbox-wire-test-" + kafka.runId());
    }
}
