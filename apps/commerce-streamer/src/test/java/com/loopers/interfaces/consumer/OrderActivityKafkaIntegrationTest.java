package com.loopers.interfaces.consumer;

import com.loopers.application.purchaseactivity.PurchaseActivityHandler;
import com.loopers.application.purchaseactivity.PurchaseActivityResult;
import com.loopers.config.OrderActivityKafkaConfig;
import com.loopers.config.OrderActivityKafkaProperties;
import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.interfaces.consumer.order.OrderConfirmedParser;
import com.loopers.testsupport.LocalKafkaTestSupport;
import com.loopers.testsupport.OrderActivityKafkaTestConfig;
import com.loopers.testsupport.PurchaseActivityDbTestConfig;
import com.loopers.testsupport.PurchaseActivityDbTestConfig.ControllableProbe;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.BatchMessageListener;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.loopers.testsupport.PurchaseActivityDbTestConfig.ACTIVITY;
import static com.loopers.testsupport.PurchaseActivityDbTestConfig.PROCESSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * C6~C8 을 공유 로컬 Docker Kafka 와 기존 MySQL fixture 로 확인한다(소비 설계 6·7절).
 *
 * <p>topic·group 은 이 실행이 컨텍스트 기동 전에 만든 것만 쓴다. listener 는 autoStartup=false 로 두고 registry 로 직접 시작·정지한다.
 * 컨텍스트는 직접 만들고 닫아, 정리 순서(listener·producer·컨텍스트 → group 비활성 확인·삭제 → topic 삭제)를 이 클래스가 소유한다.
 * committed offset 은 AdminClient 로 broker 값을 읽는다. consumer position·poll·로그로 대신하지 않는다.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("구매 활동 listener 는 DB TX 성공 뒤에만 ack 하고, 실패하면 중단·offset 유지 뒤 같은 group 재시작으로 회복하며, DB commit 뒤 offset 공백의 재전달은 no-op 이다.")
class OrderActivityKafkaIntegrationTest {

    private static final Duration DEADLINE = Duration.ofSeconds(30);
    private static final String SECRET = "PAYLOAD-SECRET-8814";
    /** 공통 client-id(spring.application.name) 에 container 가 번호를 붙인 listener consumer 의 client.id 접두어. */
    private static final String LISTENER_CLIENT_ID_PREFIX = "commerce-streamer-";

    private static LocalKafkaTestSupport kafka;
    private static KafkaProducer<String, byte[]> producer;
    private static ConfigurableApplicationContext context;
    private static String listenerTopic;
    private static String listenerGroup;
    private static String swapTopic;
    private static String swapGroup;

    @BeforeAll
    static void prepare() {
        kafka = LocalKafkaTestSupport.connect();
        System.out.println("[KAFKA] bootstrap=" + kafka.bootstrapServers() + " runId=" + kafka.runId() + " broker=" + kafka.brokerInfo());
        listenerTopic = kafka.createTopic("order-activity");
        listenerGroup = kafka.ownGroup("purchase-activity");
        swapTopic = kafka.createTopic("order-activity-swap");
        swapGroup = kafka.ownGroup("purchase-activity-swap");
        producer = new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers(),
            ProducerConfig.ACKS_CONFIG, "all",
            ProducerConfig.CLIENT_ID_CONFIG, "order-activity-test-producer-" + kafka.runId()),
            new StringSerializer(), new ByteArraySerializer());
        context = new SpringApplicationBuilder(OrderActivityKafkaTestConfig.class)
            .web(WebApplicationType.NONE)
            .run("--spring.main.web-application-type=none",
                "--spring.kafka.bootstrap-servers=" + kafka.bootstrapServers(),
                "--spring.kafka.admin.properties.bootstrap.servers=" + kafka.bootstrapServers(),
                "--commerce.order-activity.topic=" + listenerTopic,
                "--commerce.order-activity.group-id=" + listenerGroup,
                "--commerce.order-activity.auto-startup=false");
    }

    /** listener·producer·컨텍스트를 먼저 닫고 Kafka 소유 자원을 정리한다. 한 단계가 실패해도 나머지를 시도하고 첫 오류를 던진다. */
    @AfterAll
    static void cleanUp() {
        List<RuntimeException> failures = new ArrayList<>();
        attempt(failures, () -> {
            if (context != null) {
                stopAndAwait(listener());
            }
        });
        attempt(failures, () -> {
            if (producer != null) {
                producer.close(Duration.ofSeconds(10));
            }
        });
        attempt(failures, () -> {
            if (context != null) {
                context.close();
            }
        });
        attempt(failures, () -> {
            if (kafka != null) {
                kafka.close();
            }
        });
        if (!failures.isEmpty()) {
            RuntimeException first = failures.get(0);
            failures.stream().skip(1).forEach(first::addSuppressed);
            throw first;
        }
    }

    private static void attempt(List<RuntimeException> failures, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
    }

    private static ConcurrentMessageListenerContainer<?, ?> listener() {
        return (ConcurrentMessageListenerContainer<?, ?>) context.getBean(KafkaListenerEndpointRegistry.class)
            .getListenerContainer(OrderActivityKafkaConfig.LISTENER_ID);
    }

    /** 정지 callback 완료까지 기다린다. 이미 정지했으면 그대로 둔다. */
    private static void stopAndAwait(ConcurrentMessageListenerContainer<?, ?> container) {
        if (!container.isRunning()) {
            return;
        }
        CountDownLatch stopped = new CountDownLatch(1);
        container.stop(stopped::countDown);
        try {
            if (!stopped.await(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("listener 정지가 deadline 안에 끝나지 않았습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(what + " 가 deadline(" + DEADLINE.toSeconds() + "초) 안에 관찰되지 않았습니다.");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static boolean groupHasNoMember(String group) {
        var description = kafka.describeGroup(group);
        return description.members().isEmpty()
            && (description.state() == ConsumerGroupState.EMPTY || description.state() == ConsumerGroupState.DEAD);
    }

    private static String orderConfirmed(long orderId, String items) {
        return """
            {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:%d","orderId":%d,
             "confirmedAt":"2026-10-29T01:02:03.123456Z","items":%s,"memo":"%s"}
            """.formatted(orderId, orderId, items, SECRET);
    }

    private static RecordMetadata send(String topic, long orderId, String value) throws Exception {
        return producer.send(new ProducerRecord<>(topic, Long.toString(orderId), value.getBytes(StandardCharsets.UTF_8)))
            .get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 업무 parser 계약에 맞춰 value 를 byte[] 로 받는 테스트 consumer 설정. helper 의 String value 설정을 덮어쓴다. */
    private static Map<String, Object> byteConsumerConfig(String group) {
        Map<String, Object> config = kafka.consumerConfig(group);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return config;
    }

    private static JdbcTemplate jdbc() {
        return context.getBean(JdbcTemplate.class);
    }

    private static List<Long> counts() {
        return List.of(
            jdbc().queryForObject("SELECT COUNT(*) FROM " + PROCESSED, Long.class),
            jdbc().queryForObject("SELECT COUNT(*) FROM " + ACTIVITY, Long.class));
    }

    /** 두 테이블의 저장 컬럼 전체. 시각은 세션 time zone 변환 없이 열 값 그대로 읽는다. */
    record StoredRows(List<Map<String, Object>> processed, List<Map<String, Object>> activities) {
    }

    private static StoredRows storedRows() {
        return new StoredRows(
            jdbc().queryForList("""
                SELECT id, event_id, order_id, event_type, schema_version, CAST(confirmed_at AS CHAR) AS confirmed_at,
                       item_count, CAST(created_at AS CHAR) AS created_at, CAST(updated_at AS CHAR) AS updated_at,
                       CAST(deleted_at AS CHAR) AS deleted_at
                FROM processed_order_confirmation ORDER BY id
                """),
            jdbc().queryForList("""
                SELECT id, order_id, product_id, quantity, CAST(confirmed_at AS CHAR) AS confirmed_at,
                       CAST(created_at AS CHAR) AS created_at, CAST(updated_at AS CHAR) AS updated_at,
                       CAST(deleted_at AS CHAR) AS deleted_at
                FROM product_purchase_activity ORDER BY id
                """));
    }

    private static List<String> tableColumns(String table) {
        return jdbc().queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
            String.class, table);
    }

    /** Kafka client 가 실제 consumer 를 만들 때 남긴 ConsumerConfig 블록 중 이 group 의 listener consumer 것. 같은 group 의 준비용 consumer 는 제외한다. */
    private static String consumerConfigBlock(String output, String group) {
        String[] blocks = output.split("ConsumerConfig values:");
        for (int i = 1; i < blocks.length; i++) {
            // 설정 줄은 탭으로 시작한다. 탭으로 시작하지 않는 첫 줄(다음 로그 줄)에서 블록이 끝난다.
            StringBuilder lines = new StringBuilder();
            for (String line : blocks[i].split("\\R", -1)) {
                if (line.startsWith("\t")) {
                    lines.append(line).append('\n');
                } else if (!lines.isEmpty()) {
                    break;
                }
            }
            String body = lines.toString();
            if (body.contains("group.id = " + group + "\n") && body.contains("client.id = " + LISTENER_CLIENT_ID_PREFIX)) {
                return body;
            }
        }
        throw new AssertionError("group 의 ConsumerConfig 로그가 없습니다: " + group);
    }

    @DisplayName("C6: 실제 listener 에 전용 record factory·consumer factory·중단형 error handler 가 연결되고, 공통 batch 설정·demo 는 바뀌거나 섞이지 않는다.")
    @Test
    void effectiveConfiguration() {
        KafkaListenerEndpointRegistry registry = context.getBean(KafkaListenerEndpointRegistry.class);
        ConcurrentMessageListenerContainer<?, ?> container = listener();
        ContainerProperties properties = container.getContainerProperties();
        DefaultKafkaConsumerFactory<?, ?> dedicated =
            context.getBean(OrderActivityKafkaConfig.CONSUMER_FACTORY, DefaultKafkaConsumerFactory.class);
        Map<String, Object> config = dedicated.getConfigurationProperties();
        DefaultKafkaConsumerFactory<?, ?> common = context.getBean("consumerFactory", DefaultKafkaConsumerFactory.class);
        ConcurrentKafkaListenerContainerFactory<?, ?> batch =
            context.getBean(KafkaConfig.BATCH_LISTENER, ConcurrentKafkaListenerContainerFactory.class);
        System.out.println("[C6 CONFIG] dedicated=" + new java.util.TreeMap<>(config));
        System.out.println("[C6 CONTAINER] ids=" + registry.getListenerContainerIds() + " concurrency=" + container.getConcurrency()
            + " topics=" + List.of(properties.getTopics()) + " group=" + container.getGroupId() + " ackMode=" + properties.getAckMode()
            + " syncCommits=" + properties.isSyncCommits() + " asyncAcks=" + properties.isAsyncAcks()
            + " assignmentCommit=" + properties.getAssignmentCommitOption() + " errorHandler=" + container.getCommonErrorHandler().getClass().getName()
            + " listener=" + properties.getMessageListener().getClass().getName());

        assertAll(
            () -> assertThat(registry.getListenerContainerIds()).containsExactly(OrderActivityKafkaConfig.LISTENER_ID),
            () -> assertThat(context.getBeanNamesForType(DemoKafkaConsumer.class)).isEmpty(),
            () -> assertThat(context.getEnvironment().getProperty("spring.application.name")).isEqualTo("commerce-streamer"),
            () -> assertThat(context.getBean(OrderActivityKafkaProperties.class))
                .isEqualTo(new OrderActivityKafkaProperties(listenerTopic, listenerGroup, false)),
            () -> assertThat(ReflectionTestUtils.getField(container, "consumerFactory")).isSameAs(dedicated),
            () -> assertThat(container.getConcurrency()).isEqualTo(1),
            () -> assertThat(properties.getTopics()).containsExactly(listenerTopic),
            () -> assertThat(container.getGroupId()).isEqualTo(listenerGroup),
            () -> assertThat(container.isAutoStartup()).isFalse(),
            () -> assertThat(properties.getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE),
            () -> assertThat(properties.isSyncCommits()).isTrue(),
            () -> assertThat(properties.isAsyncAcks()).isFalse(),
            () -> assertThat(properties.getAssignmentCommitOption()).isEqualTo(ContainerProperties.AssignmentCommitOption.NEVER),
            () -> assertThat(container.getCommonErrorHandler()).isInstanceOf(CommonContainerStoppingErrorHandler.class),
            () -> assertThat(properties.getMessageListener()).isNotInstanceOf(BatchMessageListener.class),
            () -> assertThat(config).containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class)
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .containsEntry(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false)
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1)
                .containsEntry(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1)
                .containsEntry(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500)
                .containsEntry(ConsumerConfig.CLIENT_ID_CONFIG, "commerce-streamer"),
            () -> assertThat(String.valueOf(config.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG))).contains(kafka.bootstrapServers()),
            () -> assertThat(common.getConfigurationProperties()).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                .doesNotContainKey(ConsumerConfig.MAX_POLL_RECORDS_CONFIG),
            () -> assertThat(batch.isBatchListener()).isTrue(),
            () -> assertThat(ReflectionTestUtils.getField(batch, "concurrency")).isEqualTo(3),
            () -> assertThat(batch.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL),
            () -> assertThat(AopUtils.isAopProxy(context.getBean(OrderActivityKafkaConsumer.class))).isFalse()
        );
    }

    @DisplayName("C7: DB commit 전 실패 → 완료·활동 rollback·committed offset n 유지·container 비정상 중단 → 정지·member 해제 확인 뒤 같은 group 재시작 → 같은 record 정상 반영·offset n+1")
    @Test
    void listenerFailureStopsThenSameGroupRecovers(CapturedOutput output) throws Exception {
        ConcurrentMessageListenerContainer<?, ?> container = listener();
        ControllableProbe probe = context.getBean(ControllableProbe.class);
        PurchaseActivityDbTestConfig.resetTables(jdbc());

        RecordMetadata target = send(listenerTopic, 42L, orderConfirmed(42L,
            "[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]"));
        TopicPartition partition = new TopicPartition(listenerTopic, target.partition());
        long n = target.offset();
        // 업무 시작 전 group 기준 위치를 target 의 offset n 으로 둔다. member 로 참여하지 않는 assign 전용 commit 이다.
        try (KafkaConsumer<String, byte[]> preparer = new KafkaConsumer<>(byteConsumerConfig(listenerGroup))) {
            preparer.assign(List.of(partition));
            preparer.commitSync(Map.of(partition, new OffsetAndMetadata(n)), DEADLINE);
        }
        Map<TopicPartition, Long> prepared = kafka.committedOffsets(listenerGroup);

        AtomicInteger injected = new AtomicInteger();
        AtomicReference<String> handlerThread = new AtomicReference<>();
        probe.onBeforeCommit(orderId -> {
            injected.incrementAndGet();
            throw new IllegalStateException("주입: DB commit 전 실패 " + SECRET);
        });
        container.start();
        List<? extends KafkaMessageListenerContainer<?, ?>> children = container.getContainers();
        await("주입 실패 1회", () -> injected.get() >= 1);
        await("child 정지", () -> children.stream().noneMatch(KafkaMessageListenerContainer::isRunning));
        await("실패 뒤 group member 해제", () -> groupHasNoMember(listenerGroup));
        // consumer close·group 이탈 뒤에 child 정지 통지가 부모에 도착한다. running 플래그가 아니라 정지 child 수가 0 이 될 때까지 본다.
        await("부모가 child 정지 통지를 받음", () -> !container.isChildRunning());
        Map<TopicPartition, Long> afterFailure = kafka.committedOffsets(listenerGroup);
        List<Long> countsAfterFailure = counts();
        boolean parentRunningAfterFailure = container.isRunning();
        boolean parentExpectedAfterFailure = container.isInExpectedState();
        boolean childExpectedAfterFailure = children.get(0).isInExpectedState();
        boolean childRunningAfterFailure = container.isChildRunning();
        System.out.println("[C7 FAILURE] target=" + partition + "@" + n + " prepared=" + prepared + " afterFailure=" + afterFailure
            + " db=" + countsAfterFailure + " attempts=" + injected.get() + " children=" + children.size()
            + " childRunning=" + children.get(0).isRunning() + " childExpected=" + childExpectedAfterFailure
            + " parentRunning=" + parentRunningAfterFailure + " parentExpected=" + parentExpectedAfterFailure
            + " parentChildRunning=" + childRunningAfterFailure + " parentChildren=" + container.getContainers().size());

        stopAndAwait(container);
        var groupBeforeRestart = kafka.describeGroup(listenerGroup);
        boolean parentRunningBeforeRestart = container.isRunning();
        boolean childRunningBeforeRestart = container.isChildRunning();
        probe.reset();
        probe.onAfterActivitySaved((orderId, productId) -> handlerThread.compareAndSet(null, Thread.currentThread().getName()));
        container.start();
        await("재시작 뒤 committed offset n+1", () -> Long.valueOf(n + 1).equals(kafka.committedOffsets(listenerGroup).get(partition)));
        Map<TopicPartition, Long> afterRecovery = kafka.committedOffsets(listenerGroup);
        List<Long> countsAfterRecovery = counts();
        stopAndAwait(container);
        await("복구 뒤 group member 해제", () -> groupHasNoMember(listenerGroup));
        probe.reset();
        String handled = "ORDER_ACTIVITY_HANDLED_AND_ACK_RETURNED topic=" + listenerTopic + " partition=" + partition.partition()
            + " offset=" + n + " eventId=order-confirmed:42 result=APPLIED";
        String failedLog = "ORDER_ACTIVITY_FAILURE topic=" + listenerTopic + " partition=" + partition.partition() + " offset=" + n
            + " stage=HANDLE code=NONE exceptionType=java.lang.IllegalStateException";
        String clientConfig = consumerConfigBlock(output.getAll(), listenerGroup);
        System.out.println("[C7 RECOVERY] groupBeforeRestart=" + groupBeforeRestart.state() + "/members=" + groupBeforeRestart.members().size()
            + " parentRunning=" + parentRunningBeforeRestart + " childRunning=" + childRunningBeforeRestart
            + " afterRecovery=" + afterRecovery + " db=" + countsAfterRecovery + " handlerThread=" + handlerThread.get());
        System.out.println("[C7 CLIENT CONFIG]" + clientConfig.lines()
            .filter(line -> line.matches("\\s+(allow.auto.create.topics|auto.offset.reset|enable.auto.commit|fetch.max.wait.ms|fetch.min.bytes"
                + "|group.id|key.deserializer|max.poll.records|value.deserializer|bootstrap.servers|client.id) = .*"))
            .map(String::trim).toList());

        assertAll(
            () -> assertThat(prepared).containsExactly(Map.entry(partition, n)),
            () -> assertThat(injected.get()).isEqualTo(1),
            () -> assertThat(countsAfterFailure).containsExactly(0L, 0L),
            () -> assertThat(afterFailure).containsExactly(Map.entry(partition, n)),
            () -> assertThat(children).hasSize(1),
            () -> assertThat(children.get(0).isRunning()).isFalse(),
            () -> assertThat(childExpectedAfterFailure).as("child 는 비정상 정지").isFalse(),
            () -> assertThat(childRunningAfterFailure).isFalse(),
            () -> assertThat(parentRunningBeforeRestart).isFalse(),
            () -> assertThat(childRunningBeforeRestart).isFalse(),
            () -> assertThat(groupBeforeRestart.members()).isEmpty(),
            () -> assertThat(afterRecovery).containsEntry(partition, n + 1),
            () -> assertThat(countsAfterRecovery).containsExactly(1L, 2L),
            () -> assertThat(handlerThread.get()).startsWith(OrderActivityKafkaConfig.LISTENER_ID + "-0-C-"),
            () -> assertThat(output.getAll()).contains(failedLog).contains(handled),
            () -> assertThat(output.getAll()).doesNotContain(SECRET),
            () -> assertThat(clientConfig).contains("allow.auto.create.topics = false", "auto.offset.reset = earliest",
                "enable.auto.commit = false", "fetch.max.wait.ms = 500", "fetch.min.bytes = 1", "max.poll.records = 1",
                "group.id = " + listenerGroup, "value.deserializer = class org.apache.kafka.common.serialization.ByteArrayDeserializer",
                "key.deserializer = class org.apache.kafka.common.serialization.StringDeserializer")
        );
    }

    @DisplayName("C8: 첫 consumer 가 DB commit 후 offset 을 commit 하지 않고 닫히면, 같은 group 의 새 consumer 가 seek 없이 같은 record 를 다시 받아 no-op 이고 그 뒤 n+1 을 commit 한다.")
    @Test
    void dbCommitThenOffsetGapRedeliversSameRecord() throws Exception {
        OrderConfirmedParser parser = context.getBean(OrderConfirmedParser.class);
        PurchaseActivityHandler handler = context.getBean(PurchaseActivityHandler.class);
        PurchaseActivityDbTestConfig.resetTables(jdbc());

        RecordMetadata target = send(swapTopic, 43L, orderConfirmed(43L, "[{\"productId\":7,\"quantity\":3}]"));
        TopicPartition partition = new TopicPartition(swapTopic, target.partition());
        long n = target.offset();

        int discarded = 0;
        ConsumerRecord<String, byte[]> firstDelivery = null;
        PurchaseActivityResult firstResult;
        StoredRows afterFirst;
        Map<TopicPartition, Long> committedBeforeClose;
        try (KafkaConsumer<String, byte[]> first = new KafkaConsumer<>(byteConsumerConfig(swapGroup))) {
            first.subscribe(List.of(swapTopic));
            long deadline = System.nanoTime() + DEADLINE.toNanos();
            while (!first.assignment().contains(partition)) {
                assertThat(System.nanoTime()).as("첫 consumer 배정 deadline").isLessThan(deadline);
                discarded += first.poll(Duration.ofMillis(200)).count(); // 준비 poll 이 받은 record 는 처리·commit 하지 않는다.
            }
            first.commitSync(Map.of(partition, new OffsetAndMetadata(n)), DEADLINE);
            first.seek(partition, n);
            assertThat(kafka.committedOffsets(swapGroup)).containsEntry(partition, n);
            while (firstDelivery == null) {
                assertThat(System.nanoTime()).as("첫 consumer target 수신 deadline").isLessThan(deadline);
                for (ConsumerRecord<String, byte[]> record : first.poll(Duration.ofMillis(200))) {
                    if (record.partition() == partition.partition() && record.offset() == n) {
                        firstDelivery = record;
                    }
                }
            }
            firstResult = handler.consume(parser.parseAndValidate(firstDelivery.key(), firstDelivery.value()));
            afterFirst = storedRows();
            committedBeforeClose = kafka.committedOffsets(swapGroup);
            first.close(Duration.ofSeconds(10)); // auto commit 꺼짐, commitAsync·종료 callback 없음
        }
        await("첫 consumer member 해제", () -> groupHasNoMember(swapGroup));
        Map<TopicPartition, Long> beforeSwap = kafka.committedOffsets(swapGroup);

        ConsumerRecord<String, byte[]> redelivered = null;
        PurchaseActivityResult secondResult;
        StoredRows afterSecond;
        Map<TopicPartition, Long> afterCommit;
        try (KafkaConsumer<String, byte[]> second = new KafkaConsumer<>(byteConsumerConfig(swapGroup))) {
            second.subscribe(List.of(swapTopic)); // seek·offset reset 없음
            long deadline = System.nanoTime() + DEADLINE.toNanos();
            while (redelivered == null) {
                assertThat(System.nanoTime()).as("두 번째 consumer 재전달 deadline").isLessThan(deadline);
                for (ConsumerRecord<String, byte[]> record : second.poll(Duration.ofMillis(200))) {
                    if (record.partition() == partition.partition() && redelivered == null) {
                        redelivered = record;
                    }
                }
            }
            secondResult = handler.consume(parser.parseAndValidate(redelivered.key(), redelivered.value()));
            afterSecond = storedRows();
            second.commitSync(Map.of(partition, new OffsetAndMetadata(n + 1)), DEADLINE);
            afterCommit = kafka.committedOffsets(swapGroup);
        }
        await("두 번째 consumer member 해제", () -> groupHasNoMember(swapGroup));
        System.out.println("[C8] target=" + partition + "@" + n + " discardedPrepRecords=" + discarded + " first=" + firstResult
            + " committedBeforeClose=" + committedBeforeClose + " beforeSwap=" + beforeSwap
            + " redelivered=" + redelivered.topic() + "-" + redelivered.partition() + "@" + redelivered.offset()
            + " second=" + secondResult + " afterCommit=" + afterCommit);
        System.out.println("[C8 ROWS] afterFirst=" + afterFirst + " afterSecond=" + afterSecond);

        ConsumerRecord<String, byte[]> again = redelivered;
        assertAll(
            () -> assertThat(firstResult).isEqualTo(PurchaseActivityResult.APPLIED),
            () -> assertThat(afterFirst.processed()).singleElement().satisfies(row ->
                assertThat(row.keySet()).containsExactlyInAnyOrderElementsOf(tableColumns(PROCESSED))),
            () -> assertThat(afterFirst.activities()).singleElement().satisfies(row ->
                assertThat(row.keySet()).containsExactlyInAnyOrderElementsOf(tableColumns(ACTIVITY))),
            () -> assertThat(committedBeforeClose).containsEntry(partition, n),
            () -> assertThat(beforeSwap).containsEntry(partition, n),
            () -> assertThat(again.topic()).isEqualTo(swapTopic),
            () -> assertThat(again.offset()).isEqualTo(n),
            () -> assertThat(secondResult).isEqualTo(PurchaseActivityResult.ALREADY_PROCESSED),
            () -> assertThat(afterSecond.processed()).isEqualTo(afterFirst.processed()),
            () -> assertThat(afterSecond.activities()).isEqualTo(afterFirst.activities()),
            () -> assertThat(afterCommit).containsEntry(partition, n + 1)
        );
    }
}
