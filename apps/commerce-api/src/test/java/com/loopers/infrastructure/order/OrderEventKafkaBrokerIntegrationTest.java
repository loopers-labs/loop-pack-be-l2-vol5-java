package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.CommerceApiApplication;
import com.loopers.application.order.OrderConfirmedEventListener;
import com.loopers.application.order.OrderPublicationProbe;
import com.loopers.config.OrderEventKafkaConfig;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderEventPublisher;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.fixture.PgCommands;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.fixture.pg.CallbackBatchResult;
import com.loopers.fixture.pg.CallbackDelivery;
import com.loopers.fixture.pg.CreateOutcome;
import com.loopers.fixture.pg.PgFixtureServer;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import com.loopers.infrastructure.order.OrderEventTestCleanup.Step;
import com.loopers.pgsimulator.PaymentStatus;
import com.loopers.testsupport.LocalKafkaTestSupport;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 공유 로컬 Docker Kafka 에서 실제 주문 API·로컬 PG fixture·결과 적용 TX·실제 adapter·전용 producer 를 연결해 P4 wire·P6·P7·P8 을 관찰한다.
 *
 * <p>기록용 fake·template 대역을 쓰지 않는다. 각 시나리오는 실행 소유 topic 을 컨텍스트 기동 전에 준비하고, SpringApplicationBuilder 로
 * 직접 띄운 컨텍스트를 닫은 뒤에만 topic 을 삭제한다. 별도 schema 를 쓰므로 다른 테스트 컨텍스트의 DB 와 섞이지 않는다.
 * 구매 활동 consumer 는 아직 없으므로 소비 DB 완료는 이 테스트의 관찰 대상이 아니다(C10).
 */
@Timeout(180)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("실제 broker 로 주문 확정 사건을 발행하고, commit→send 공백과 정상 종료를 관찰한다.")
class OrderEventKafkaBrokerIntegrationTest {

    private static final String SCHEMA = "order_event_kafka";
    /** 나노초·서울 zone 을 일부러 넣은 고정 확정 시각. 저장·wire 는 UTC 마이크로초여야 한다. */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-29T01:02:03.123456789Z"), ZoneId.of("Asia/Seoul"));
    private static final String CONFIRMED_AT = "2026-10-29T01:02:03.123456Z";
    private static final Pattern SUCCESS_LOG = Pattern.compile(
        "ORDER_CONFIRMED_SEND_SUCCESS eventId=(\\S+) orderId=(\\d+) topic=(\\S+) partition=(\\d+) offset=(\\d+)");
    private static final Duration QUIET = Duration.ofSeconds(2);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String PRODUCER_THREAD_PREFIX = "kafka-producer-network-thread | ";
    private static final AtomicInteger BOOTS = new AtomicInteger();

    private static LocalKafkaTestSupport kafka;
    private static PgFixtureServer pg;
    private static String server;
    /** 이 테스트만 쓰는 이름의 스키마를 만들기 시작했는지. 만들기 전 실패면 정리하지 않는다. */
    private static boolean schemaOwned;
    private static JdbcTemplate fresh;

    private final RestTemplate http = client();
    /** 주문 ID → 주문한 사용자 ID. 확정·reconcile 요청의 X-USER-ID 에 쓴다. */
    private final Map<Long, Long> users = new ConcurrentHashMap<>();
    private ExecutorService workers;

    @BeforeAll
    static void prepareSharedResources() throws Exception {
        kafka = LocalKafkaTestSupport.connect();
        System.out.println("[KAFKA] bootstrap=" + kafka.bootstrapServers() + " runId=" + kafka.runId() + " broker=" + kafka.brokerInfo());
        Class.forName("com.loopers.testcontainers.MySqlTestContainersConfig");
        String url = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        server = url.substring(0, url.lastIndexOf('/'));
        schemaOwned = true;
        try (var root = new SingleConnectionDataSource(server, "root", "test", true)) {
            JdbcTemplate admin = new JdbcTemplate(root);
            admin.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            admin.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            admin.execute("GRANT ALL ON " + SCHEMA + ".* TO 'test'@'%'");
        }
        // 조회마다 새 연결을 여는 DataSource. 앱의 연결 풀·영속성 컨텍스트를 거치지 않는다.
        fresh = new JdbcTemplate(new DriverManagerDataSource(server + "/" + SCHEMA, "test", "test"));
        pg = PgFixtureServer.start();
    }

    /** 준비가 일부만 됐어도 만든 것만 정리한다. 한 단계가 실패해도 다음 단계를 시도한다. */
    @AfterAll
    static void releaseSharedResources() {
        OrderEventTestCleanup.runAll(List.of(
            new Step("PG fixture 종료", () -> {
                if (pg != null) {
                    pg.close();
                }
            }),
            new Step("검증 전용 스키마 삭제", () -> {
                if (schemaOwned) {
                    try (var root = new SingleConnectionDataSource(server, "root", "test", true)) {
                        new JdbcTemplate(root).execute("DROP DATABASE IF EXISTS " + SCHEMA);
                    }
                }
            }),
            new Step("Kafka 소유 topic·group 정리", () -> {
                if (kafka != null) {
                    kafka.close();
                }
            })));
    }

    @BeforeEach
    void prepare() {
        pg.reset();
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void finish() throws InterruptedException {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).as("작업 스레드 종료").isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> pg.state().activeRequests() == 0 && pg.state().activeCallbackJobs() == 0);
    }

    // ---------------------------------------------------------------- P4·P6

    @DisplayName("P4·P6: 즉시 성공·callback·reconcile 각각 commit 뒤 실제 broker 에 record 하나가 쓰이고, key·JSON·시각·품목·metadata 가 새 DB 조회와 맞으며 반복 요청은 더 보내지 않는다.")
    @Test
    void publishesToRealBroker(CapturedOutput output) throws Exception {
        String topic = kafka.createTopic("order-events");
        try (ConfigurableApplicationContext context = boot(topic, "create", null)) {
            assertRealWiring(context, topic);
            warmUp(context);
            double sendsBefore = sendTotal(context);

            Long immediate = newOrder(context);
            pg.enqueuePlan(PgOperation.CREATE, external(immediate), ResponsePlan.outcome(CreateOutcome.SUCCESS));
            assertConfirmedResponse(confirm(context, immediate), 200);

            Long callback = pendingOrder(context);
            CallbackBatchResult delivered = pg.sendCallbacks(key(callback), 1).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertThat(delivered.deliveries()).singleElement().satisfies(this::assertCallbackAcknowledged);
            assertThat(orderStatus(callback)).isEqualTo("CONFIRMED");

            Long reconcile = pendingOrder(context);
            assertConfirmedResponse(call(context, reconcile, "/payment/reconcile", HttpMethod.POST, null), 200);

            List<Long> orders = List.of(immediate, callback, reconcile);
            Map<Long, SuccessLog> sent = awaitSuccessLogs(output, orders);
            Map<TopicPartition, Long> endAfterFirst = kafka.endOffsets(topic);
            List<ConsumerRecord<String, String>> records = kafka.readAll(topic);
            for (Long order : orders) {
                assertPublishedRecord(order, topic, sent.get(order), records);
            }
            assertThat(records).as("업무 topic 의 record 는 세 주문뿐이고 준비 메시지가 없다").hasSize(3);
            assertThat(sendTotal(context) - sendsBefore).as("producer record-send-total 증가").isEqualTo(3.0);
            System.out.println("[P6] records=" + records.stream().map(OrderEventKafkaBrokerIntegrationTest::describe).toList());

            for (Long order : orders) {
                CallbackBatchResult repeated = pg.sendCallbacks(key(order), 1).toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertThat(repeated.deliveries()).singleElement().satisfies(this::assertCallbackAcknowledged);
                assertConfirmedResponse(call(context, order, "/payment/reconcile", HttpMethod.POST, null), 200);
            }
            Thread.sleep(QUIET.toMillis());
            assertAll(
                () -> assertThat(sendTotal(context) - sendsBefore).as("반복 callback·reconcile 뒤 추가 send 없음").isEqualTo(3.0),
                () -> assertThat(kafka.endOffsets(topic)).as("반복 뒤 끝 offset 불변").isEqualTo(endAfterFirst),
                () -> orders.forEach(order -> assertThat(count(output.getAll(), "ORDER_CONFIRMED_SEND_SUCCESS eventId=" + eventId(order) + " "))
                    .as("성공 로그 1회").isEqualTo(1)),
                () -> assertThat(pg.state().counts().createReceived()).as("PG create 수신").isEqualTo(3L)
            );
        }
        assertGroupLifecycle(topic);
    }

    /** 실행 소유 group 으로 구독해 record 를 읽고 닫는다. 정리 단계가 이 group 의 비활성 확인·삭제를 맡는다. */
    private static void assertGroupLifecycle(String topic) {
        String group = kafka.ownGroup("purchase-activity");
        Map<String, Object> config = kafka.consumerConfig(group);
        assertThat(config).containsEntry("enable.auto.commit", false).containsEntry("allow.auto.create.topics", false);
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(config)) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> seen = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (seen.size() < 3 && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(seen::add);
            }
            assertThat(seen).as("소유 group 의 earliest 구독").hasSize(3);
        }
    }

    // ---------------------------------------------------------------- P7

    @DisplayName("P7: commit 뒤 beforeSend 에서 대상 주문만 통제 실패시키면 확정·시각·결제·예약 소비는 남고 send 는 없으며, 모든 partition 의 고정 구간에 그 eventId 가 없다.")
    @Test
    void failureBeforeSendLeavesNoRecord(CapturedOutput output) throws Exception {
        String topic = kafka.createTopic("order-events");
        GateProbe probe = new GateProbe(true);
        Long order;
        Map<TopicPartition, Long> start;
        try (ConfigurableApplicationContext context = boot(topic, "create", probe)) {
            assertRealWiring(context, topic);
            warmUp(context);
            order = newOrder(context);
            probe.target(order);
            pg.enqueuePlan(PgOperation.CREATE, external(order), ResponsePlan.outcome(CreateOutcome.SUCCESS));
            start = kafka.endOffsets(topic);
            double sendsBefore = sendTotal(context);

            Long target = order;
            Future<ResponseEntity<JsonNode>> response = workers.submit(() -> confirm(context, target));
            assertThat(probe.reached.await(10, TimeUnit.SECONDS)).as("commit 뒤 beforeSend 도달").isTrue();
            Map<String, Object> atHook = committedFacts(order);
            assertThat(response.isDone()).as("hook 대기 중 HTTP 미완료").isFalse();
            assertCommittedConfirmation(atHook);
            probe.release();
            assertConfirmedResponse(response.get(10, TimeUnit.SECONDS), 200);

            await().atMost(Duration.ofSeconds(5)).until(() -> output.getAll().contains(
                "ORDER_CONFIRMED_SEND_FAILURE eventId=" + eventId(order) + " orderId=" + order + " topic=" + topic + " stage=LISTENER_CALL"));
            CallbackBatchResult repeated = pg.sendCallbacks(key(order), 1).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertThat(repeated.deliveries()).singleElement().satisfies(this::assertCallbackAcknowledged);
            assertConfirmedResponse(call(context, order, "/payment/reconcile", HttpMethod.POST, null), 200);
            Thread.sleep(QUIET.toMillis());

            assertAll(
                () -> assertThat(sendTotal(context)).as("adapter send 없음(producer record-send-total 불변)").isEqualTo(sendsBefore),
                () -> assertThat(output.getAll()).doesNotContain("ORDER_CONFIRMED_SEND_SUCCESS eventId=" + eventId(order) + " ")
                    .doesNotContain("eventId=" + eventId(order) + " orderId=" + order + " topic=" + topic + " stage=SEND_"),
                () -> assertThat(committedFacts(order)).as("반복 callback·reconcile 뒤 새 조회").isEqualTo(atHook),
                () -> assertThat(pg.state().counts().createReceived()).as("PG create 수신").isEqualTo(1L)
            );
        }
        // 재시작: 같은 schema·topic 으로 새 컨텍스트를 띄워 reconcile 해도 다시 발행하지 않는다.
        try (ConfigurableApplicationContext restarted = boot(topic, "validate", null)) {
            assertRealWiring(restarted, topic);
            assertConfirmedResponse(call(restarted, order, "/payment/reconcile", HttpMethod.POST, null), 200);
            Thread.sleep(QUIET.toMillis());
        }
        Map<TopicPartition, Long> end = kafka.endOffsets(topic);
        List<ConsumerRecord<String, String>> range = kafka.readRange(start, end);
        assertAll(
            () -> assertThat(range).as("고정 구간 [start, end) 전체").noneMatch(record -> record.value().contains(eventId(order) + "\"")),
            () -> assertThat(range).noneMatch(record -> record.key().equals(Long.toString(order))),
            () -> assertThat(output.getAll()).doesNotContain("ORDER_CONFIRMED_SEND_SUCCESS eventId=" + eventId(order) + " ")
        );
        System.out.println("[P7] order=" + order + " eventId=" + eventId(order) + " start=" + start + " end=" + end
            + " recordsInRange=" + range.size() + " facts=" + committedFacts(order));
    }

    // ---------------------------------------------------------------- P8

    @DisplayName("P8 정상 완료: 진행 중인 HTTP 요청의 send 가 graceful 종료 뒤·producer close 전에 끝나고, close 15초·phase 30초가 실제 적용되며 IO 스레드가 정리된다.")
    @Test
    void gracefulShutdownSendsInFlightEventBeforeProducerClose(CapturedOutput output) throws Exception {
        String topic = kafka.createTopic("order-events");
        GateProbe probe = new GateProbe(false);
        ConfigurableApplicationContext context = boot(topic, "create", probe);
        Long order;
        Thread ioThread;
        Duration closeTook;
        try {
            assertRealWiring(context, topic);
            assertShutdownSettings(context);
            warmUp(context);
            ioThread = producerIoThread(context);
            order = newOrder(context);
            probe.target(order);
            pg.enqueuePlan(PgOperation.CREATE, external(order), ResponsePlan.outcome(CreateOutcome.SUCCESS));

            Long target = order;
            Future<ResponseEntity<JsonNode>> response = workers.submit(() -> confirm(context, target));
            assertThat(probe.reached.await(10, TimeUnit.SECONDS)).as("commit 뒤 send 직전 도달").isTrue();
            Future<Duration> closing = workers.submit(() -> timed(context::close));
            await().atMost(Duration.ofSeconds(10)).until(() -> output.getAll().contains("Commencing graceful shutdown"));
            assertThat(response.isDone()).as("graceful 종료 시작 시 요청 진행 중").isFalse();
            probe.release();

            assertConfirmedResponse(response.get(30, TimeUnit.SECONDS), 200);
            closeTook = closing.get(60, TimeUnit.SECONDS);
        } finally {
            context.close();
        }
        ioThread.join(Duration.ofSeconds(5));
        String all = output.getAll();
        String success = "ORDER_CONFIRMED_SEND_SUCCESS eventId=" + eventId(order) + " ";
        int commencing = all.indexOf("Commencing graceful shutdown");
        int sentAt = all.indexOf(success);
        int gracefulDone = all.indexOf("Graceful shutdown complete");
        int producerClose = all.indexOf("Closing the Kafka producer with timeoutMillis = 15000 ms");
        SuccessLog log = successLog(all, order);
        List<ConsumerRecord<String, String>> records = kafka.readAll(topic);
        assertAll(
            () -> assertThat(commencing).as("graceful 종료 시작 로그").isNotNegative(),
            () -> assertThat(sentAt).as("send 성공 로그는 graceful 시작 뒤").isGreaterThan(commencing),
            () -> assertThat(gracefulDone).as("graceful 완료 로그").isGreaterThan(commencing),
            () -> assertThat(producerClose).as("producer close(15000ms)는 send 성공·graceful 완료 뒤").isGreaterThan(sentAt).isGreaterThan(gracefulDone),
            () -> assertThat(closeTook).as("정상 완료는 close 예산을 다 쓰지 않음").isLessThan(Duration.ofSeconds(15)),
            () -> assertThat(ioThread.isAlive()).as("producer IO 스레드 정리").isFalse(),
            () -> assertThat(orderStatus(order)).isEqualTo("CONFIRMED"),
            () -> assertPublishedRecord(order, topic, log, records)
        );
        System.out.println("[P8-normal] order=" + order + " closeTook=" + closeTook.toMillis() + "ms io=" + ioThread.getName()
            + " record=" + describe(records.get(0)));
    }

    @DisplayName("P8 예산 소진: 소유 topic 의 min.insync.replicas=2 로 전송을 미완료로 두고 닫으면 close 가 15초 예산을 쓰고 SEND_COMPLETION 실패가 관측되며 확정은 유지된다.")
    @Test
    void closeBudgetExhaustionFailsPendingSend(CapturedOutput output) throws Exception {
        // 통제 실패: 이 실행이 만든 topic 에만 복제 계수(1)보다 큰 min.insync.replicas 를 주어 acks=all 쓰기를 재시도 가능한 오류로 막는다.
        String topic = kafka.createTopic("order-events-isr", Map.of("min.insync.replicas", "2"));
        assertThat(kafka.topicConfig(topic, "min.insync.replicas")).isEqualTo("2");
        ConfigurableApplicationContext context = boot(topic, "create", null);
        Long order;
        Thread ioThread;
        Duration closeTook;
        try {
            assertRealWiring(context, topic);
            warmUp(context);
            ioThread = producerIoThread(context);
            order = newOrder(context);
            pg.enqueuePlan(PgOperation.CREATE, external(order), ResponsePlan.outcome(CreateOutcome.SUCCESS));
            assertConfirmedResponse(confirm(context, order), 200);
            await().atMost(Duration.ofSeconds(10)).until(() -> output.getAll().contains("NOT_ENOUGH_REPLICAS"));
            assertThat(output.getAll()).as("close 전에는 결과 로그 없음").doesNotContain("eventId=" + eventId(order) + " ");
            closeTook = timed(context::close);
        } finally {
            context.close();
        }
        ioThread.join(Duration.ofSeconds(5));
        String all = output.getAll();
        Matcher failure = Pattern.compile("ORDER_CONFIRMED_SEND_FAILURE eventId=" + Pattern.quote(eventId(order))
            + " orderId=" + order + " topic=" + Pattern.quote(topic) + " stage=SEND_COMPLETION exceptionType=(\\S+) causeType=(\\S+)").matcher(all);
        assertAll(
            () -> assertThat(failure.find()).as("SEND_COMPLETION 실패 로그").isTrue(),
            () -> assertThat(all.indexOf("Closing the Kafka producer with timeoutMillis = 15000 ms")).isNotNegative(),
            () -> assertThat(closeTook).as("close 가 15초 예산을 소진").isGreaterThanOrEqualTo(Duration.ofSeconds(14))
                .isLessThan(Duration.ofSeconds(30)),
            () -> assertThat(ioThread.isAlive()).as("producer IO 스레드 정리").isFalse(),
            () -> assertThat(all).doesNotContain("ORDER_CONFIRMED_SEND_SUCCESS eventId=" + eventId(order) + " "),
            () -> assertCommittedConfirmation(committedFacts(order))
        );
        failure.reset();
        System.out.println("[P8-budget] order=" + order + " closeTook=" + closeTook.toMillis() + "ms log="
            + (failure.find() ? failure.group() : "none") + " recordsInTopic=" + kafka.readAll(topic).size());
    }

    // ---------------------------------------------------------------- 연결·설정 확인

    private static void assertRealWiring(ConfigurableApplicationContext context, String topic) {
        Object port = context.getBean(OrderEventPublisher.class);
        KafkaTemplate<?, ?> template = (KafkaTemplate<?, ?>) context.getBean(OrderEventKafkaConfig.ORDER_EVENT_KAFKA_TEMPLATE);
        DefaultKafkaProducerFactory<?, ?> factory =
            (DefaultKafkaProducerFactory<?, ?>) context.getBean(OrderEventKafkaConfig.ORDER_EVENT_PRODUCER_FACTORY);
        Map<String, Object> producer = factory.getConfigurationProperties();
        assertAll(
            () -> assertThat(context.getBeansOfType(OrderEventPublisher.class)).containsOnlyKeys("kafkaOrderEventPublisher"),
            () -> assertThat(port).isInstanceOf(KafkaOrderEventPublisher.class),
            () -> assertThat(ReflectionTestUtils.getField(context.getBean(OrderConfirmedEventListener.class), "publisher")).isSameAs(port),
            () -> assertThat(ReflectionTestUtils.getField(port, "template")).isSameAs(template),
            () -> assertThat(ReflectionTestUtils.getField(port, "topic")).isEqualTo(topic),
            () -> assertThat(template.getProducerFactory()).isSameAs(factory),
            () -> assertThat(String.valueOf(producer.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG))).contains(kafka.bootstrapServers()),
            () -> assertThat(producer).doesNotContainKey(ProducerConfig.RETRIES_CONFIG).containsEntry(ProducerConfig.ACKS_CONFIG, "all"),
            () -> assertThat(producer.get(ProducerConfig.CLIENT_ID_CONFIG)).as("실행·컨텍스트 고유 client.id 실제 적용")
                .isEqualTo(context.getEnvironment().getProperty("commerce.order-events.producer-properties[client.id]"))
                .asString().startsWith("order-event-kafka-test-" + kafka.runId() + "-"),
            () -> assertThat(String.valueOf(context.getBean(KafkaAdmin.class).getConfigurationProperties()
                .get("bootstrap.servers"))).as("admin bootstrap").contains(kafka.bootstrapServers()).doesNotContain("kafka:9092"),
            () -> assertThat(context.getEnvironment().getProperty("spring.kafka.admin.properties.bootstrap.servers"))
                .isEqualTo(kafka.bootstrapServers()),
            () -> assertThat(context.getBean(OrderEventKafkaConfig.ORDER_CONFIRMATION_CLOCK, Clock.class)).isSameAs(FIXED_CLOCK)
        );
    }

    private static void assertShutdownSettings(ConfigurableApplicationContext context) {
        DefaultKafkaProducerFactory<?, ?> factory =
            (DefaultKafkaProducerFactory<?, ?>) context.getBean(OrderEventKafkaConfig.ORDER_EVENT_PRODUCER_FACTORY);
        Object lifecycle = context.getBean("lifecycleProcessor");
        assertAll(
            () -> assertThat(ReflectionTestUtils.getField(factory, "physicalCloseTimeout")).isEqualTo(Duration.ofSeconds(15)),
            () -> assertThat(factory.getPhase()).isEqualTo(Integer.MIN_VALUE),
            () -> assertThat(ReflectionTestUtils.getField(lifecycle, "timeoutPerShutdownPhase")).isEqualTo(30_000L),
            () -> assertThat(context.getEnvironment().getProperty("server.shutdown")).isEqualTo("graceful")
        );
    }

    /** 실제 쓸 전용 producer 를 별도 준비용 topic 으로 먼저 연결해 초기 metadata·전송 준비를 마친다. 업무 topic 에는 보내지 않는다. */
    @SuppressWarnings("unchecked")
    private static void warmUp(ConfigurableApplicationContext context) throws Exception {
        String prep = kafka.createTopic("order-events-prep");
        KafkaTemplate<String, Object> template =
            (KafkaTemplate<String, Object>) context.getBean(OrderEventKafkaConfig.ORDER_EVENT_KAFKA_TEMPLATE);
        SendResult<String, Object> result = template.send(prep, "warm-up", "warm-up").get(30, TimeUnit.SECONDS);
        assertThat(result.getRecordMetadata().topic()).isEqualTo(prep);
    }

    private static double sendTotal(ConfigurableApplicationContext context) {
        KafkaTemplate<?, ?> template = (KafkaTemplate<?, ?>) context.getBean(OrderEventKafkaConfig.ORDER_EVENT_KAFKA_TEMPLATE);
        return template.metrics().entrySet().stream()
            .filter(entry -> entry.getKey().group().equals("producer-metrics") && entry.getKey().name().equals("record-send-total"))
            .mapToDouble(entry -> ((Number) entry.getValue().metricValue()).doubleValue())
            .sum();
    }

    /** 이 컨텍스트의 전용 producer client.id(factory 가 뒤에 "-번호"를 붙인다)로 자신의 IO 스레드만 찾는다. 다른 producer 는 무시한다. */
    private static Thread producerIoThread(ConfigurableApplicationContext context) {
        String clientId = producerClientId(context);
        Set<Thread> threads = Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().startsWith(PRODUCER_THREAD_PREFIX + clientId + "-") && thread.isAlive())
            .collect(Collectors.toSet());
        assertThat(threads).as("client.id %s 의 전용 producer IO 스레드 하나", clientId).hasSize(1);
        return threads.iterator().next();
    }

    private static String producerClientId(ConfigurableApplicationContext context) {
        DefaultKafkaProducerFactory<?, ?> factory =
            (DefaultKafkaProducerFactory<?, ?>) context.getBean(OrderEventKafkaConfig.ORDER_EVENT_PRODUCER_FACTORY);
        return String.valueOf(factory.getConfigurationProperties().get(ProducerConfig.CLIENT_ID_CONFIG));
    }

    // ---------------------------------------------------------------- broker 관찰

    private record SuccessLog(String eventId, long orderId, String topic, int partition, long offset) {
    }

    private static SuccessLog successLog(String output, Long order) {
        Matcher matcher = SUCCESS_LOG.matcher(output);
        while (matcher.find()) {
            if (matcher.group(1).equals(eventId(order))) {
                return new SuccessLog(matcher.group(1), Long.parseLong(matcher.group(2)), matcher.group(3),
                    Integer.parseInt(matcher.group(4)), Long.parseLong(matcher.group(5)));
            }
        }
        return null;
    }

    private static Map<Long, SuccessLog> awaitSuccessLogs(CapturedOutput output, List<Long> orders) {
        await().atMost(Duration.ofSeconds(30)).until(() -> orders.stream().allMatch(order -> successLog(output.getAll(), order) != null));
        return orders.stream().collect(Collectors.toMap(order -> order, order -> successLog(output.getAll(), order)));
    }

    /** broker 에서 읽은 record 를 성공 metadata·새 DB 조회와 비교한다. Java 객체가 아니라 wire 문자열을 해석한다. */
    private static void assertPublishedRecord(Long order, String topic, SuccessLog log, List<ConsumerRecord<String, String>> records)
        throws Exception {
        List<ConsumerRecord<String, String>> matching = records.stream()
            .filter(record -> Long.toString(order).equals(record.key())).toList();
        assertThat(matching).as("주문의 record 하나").hasSize(1);
        ConsumerRecord<String, String> record = matching.get(0);
        JsonNode json = JSON.readTree(record.value());
        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);
        List<String> headers = new ArrayList<>();
        for (Header header : record.headers()) {
            headers.add(header.key());
        }
        int expectedPartition = Utils.toPositive(Utils.murmur2(Long.toString(order).getBytes(StandardCharsets.UTF_8)))
            % LocalKafkaTestSupport.PARTITIONS;
        assertAll(
            () -> assertThat(log).as("성공 로그").isNotNull(),
            () -> assertThat(log.topic()).isEqualTo(topic),
            () -> assertThat(record.topic()).isEqualTo(log.topic()),
            () -> assertThat(record.partition()).as("metadata partition").isEqualTo(log.partition()),
            () -> assertThat(record.offset()).as("metadata offset").isEqualTo(log.offset()),
            () -> assertThat(record.partition()).as("producer 기본 partitioner 의 key hash 선택").isEqualTo(expectedPartition),
            () -> assertThat(record.key()).isEqualTo(Long.toString(order)),
            () -> assertThat(headers).as("type header 없음").noneMatch(key -> key.startsWith("__")),
            () -> assertThat(fields).containsExactlyInAnyOrder("eventType", "schemaVersion", "eventId", "orderId", "confirmedAt", "items"),
            () -> assertThat(json.get("eventType").isTextual() && json.get("eventType").asText().equals("OrderConfirmed")).isTrue(),
            () -> assertThat(json.get("schemaVersion").isInt() && json.get("schemaVersion").asInt() == 1).isTrue(),
            () -> assertThat(json.get("eventId").isTextual() && json.get("eventId").asText().equals(eventId(order))).isTrue(),
            () -> assertThat(json.get("orderId").isIntegralNumber() && json.get("orderId").asLong() == order).isTrue(),
            () -> assertThat(json.get("confirmedAt").isTextual()).isTrue(),
            () -> assertThat(json.get("confirmedAt").asText()).isEqualTo(CONFIRMED_AT).isEqualTo(storedConfirmedAt(order) + "Z"),
            () -> assertThat(Instant.parse(json.get("confirmedAt").asText()))
                .isEqualTo(FIXED_CLOCK.instant().truncatedTo(ChronoUnit.MICROS)),
            () -> assertThat(json.get("items").isArray()).isTrue(),
            () -> assertThat(itemsOf(json)).isEqualTo(storedItems(order)),
            () -> json.get("items").forEach(item -> {
                List<String> itemFields = new ArrayList<>();
                item.fieldNames().forEachRemaining(itemFields::add);
                assertThat(itemFields).containsExactlyInAnyOrder("productId", "quantity");
                assertThat(item.get("productId").isIntegralNumber() && item.get("quantity").isIntegralNumber()).isTrue();
            })
        );
    }

    private static List<List<Long>> itemsOf(JsonNode json) {
        List<List<Long>> items = new ArrayList<>();
        json.get("items").forEach(item -> items.add(List.of(item.get("productId").asLong(), item.get("quantity").asLong())));
        return items;
    }

    private static String describe(ConsumerRecord<String, String> record) {
        return record.topic() + "/" + record.partition() + "@" + record.offset() + " key=" + record.key() + " value=" + record.value();
    }

    // ---------------------------------------------------------------- 새 DB 조회

    private static String storedConfirmedAt(Long order) {
        return fresh.queryForObject("SELECT DATE_FORMAT(confirmed_at, '%Y-%m-%dT%H:%i:%s.%f') FROM orders WHERE id = ?", String.class, order);
    }

    private static List<List<Long>> storedItems(Long order) {
        return fresh.query("SELECT product_id, SUM(quantity) AS quantity FROM order_item WHERE order_id = ? GROUP BY product_id "
            + "ORDER BY product_id", (row, index) -> List.of(row.getLong("product_id"), row.getLong("quantity")), order);
    }

    private static String orderStatus(Long order) {
        return fresh.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order);
    }

    private static Map<String, Object> committedFacts(Long order) {
        return Map.of(
            "order", fresh.queryForMap("SELECT status, paid_amount, total_amount, "
                + "DATE_FORMAT(confirmed_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS confirmed_at FROM orders WHERE id = ?", order),
            "payment", fresh.queryForMap("SELECT status, amount, transaction_key FROM payment WHERE order_id = ?", order),
            "reservations", fresh.queryForObject("SELECT COUNT(*) FROM stock_history WHERE order_id = ? AND cause = 'ORDER_RESERVATION'",
                Long.class, order),
            "releases", fresh.queryForObject("SELECT COUNT(*) FROM stock_history WHERE order_id = ? AND cause = 'ORDER_RELEASE'",
                Long.class, order));
    }

    @SuppressWarnings("unchecked")
    private static void assertCommittedConfirmation(Map<String, Object> facts) {
        Map<String, Object> orderRow = (Map<String, Object>) facts.get("order");
        Map<String, Object> paymentRow = (Map<String, Object>) facts.get("payment");
        assertAll(
            () -> assertThat(orderRow.get("status")).isEqualTo("CONFIRMED"),
            () -> assertThat(orderRow.get("confirmed_at")).isEqualTo(CONFIRMED_AT),
            () -> assertThat(((Number) orderRow.get("paid_amount")).longValue()).isEqualTo(((Number) orderRow.get("total_amount")).longValue()),
            () -> assertThat(paymentRow.get("status")).isEqualTo("SUCCESS"),
            () -> assertThat(((Number) paymentRow.get("amount")).longValue()).isEqualTo(((Number) orderRow.get("total_amount")).longValue()),
            () -> assertThat(facts.get("reservations")).isEqualTo(2L),
            () -> assertThat(facts.get("releases")).isEqualTo(0L)
        );
    }

    // ---------------------------------------------------------------- 업무 입력

    /** 상품 둘(수량 2·1)의 주문을 실제 주문 API 로 만든다. */
    private Long newOrder(ConfigurableApplicationContext context) {
        Long user = context.getBean(UserFixture.class).createUserWithPoint().getId();
        ProductFixture products = context.getBean(ProductFixture.class);
        Long first = products.createProduct("발행 검증 운동화", 2_000L, 5L).getId();
        Long second = products.createProduct("발행 검증 양말", 500L, 5L).getId();
        ResponseEntity<JsonNode> created = exchange(context, user, "/api/v1/orders", HttpMethod.POST, Map.of("items",
            List.of(Map.of("productId", second, "quantity", 1), Map.of("productId", first, "quantity", 2))));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        Long order = created.getBody().path("data").path("id").asLong();
        users.put(order, user);
        return order;
    }

    /** PG 기본 응답(대기)으로 확정을 시작하고 PG 거래를 SUCCESS 로 바꿔 둔다. 콜백은 자동으로 가지 않는다. */
    private Long pendingOrder(ConfigurableApplicationContext context) {
        Long order = newOrder(context);
        assertThat(confirm(context, order).getStatusCode().value()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(5)).until(() -> pg.state().activeRequests() == 0);
        assertThat(orderStatus(order)).isEqualTo("PAYMENT_PENDING");
        pg.setStatus(key(order), PaymentStatus.SUCCESS);
        return order;
    }

    private ResponseEntity<JsonNode> confirm(ConfigurableApplicationContext context, Long order) {
        return call(context, order, "/confirm", HttpMethod.POST, PgCommands.forOrder(order));
    }

    private ResponseEntity<JsonNode> call(ConfigurableApplicationContext context, Long order, String suffix, HttpMethod method,
                                          Object body) {
        return exchange(context, users.get(order), "/api/v1/orders/" + order + suffix, method, body);
    }

    private ResponseEntity<JsonNode> exchange(ConfigurableApplicationContext context, Long user, String path, HttpMethod method,
                                              Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", user.toString());
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        return http.exchange("http://127.0.0.1:" + port + path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static void assertConfirmedResponse(ResponseEntity<JsonNode> response, int status) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().path("data").path("status").asText()).isEqualTo("CONFIRMED");
    }

    private void assertCallbackAcknowledged(CallbackDelivery delivery) {
        assertThat(delivery.sent()).isTrue();
        assertThat(delivery.failure()).as("PG callback client(1초 응답 제한) 실패 없음").isNull();
        assertThat(delivery.httpStatus()).isEqualTo(200);
    }

    private static String key(Long order) {
        return fresh.queryForObject("SELECT transaction_key FROM payment WHERE order_id = ?", String.class, order);
    }

    private static String external(Long order) {
        return PaymentModel.providerOrderIdOf(order);
    }

    private static String eventId(Long order) {
        return "order-confirmed:" + order;
    }

    private static long count(String text, String token) {
        return Pattern.compile(Pattern.quote(token)).matcher(text).results().count();
    }

    private static Duration timed(Runnable work) {
        long started = System.nanoTime();
        work.run();
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private static RestTemplate client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return new RestTemplate(factory);
    }

    // ---------------------------------------------------------------- 컨텍스트

    /**
     * 실제 adapter 를 그대로 둔 커머스 컨텍스트를 직접 띄운다. 일반·admin bootstrap·topic·전용 producer client.id 는 이 실행의 값으로 덮고,
     * 확정 시각 Clock 과(주어지면) beforeSend probe 만 같은 이름의 정의로 바꾼다.
     */
    private static ConfigurableApplicationContext boot(String topic, String ddl, OrderPublicationProbe probe) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        String clientId = "order-event-kafka-test-" + kafka.runId() + "-" + BOOTS.incrementAndGet();
        return new SpringApplicationBuilder(CommerceApiApplication.class).profiles("test")
            .initializers(new ReplaceBeans(probe)).run(
                "--server.port=" + port, "--management.server.port=0", "--spring.jpa.hibernate.ddl-auto=" + ddl,
                "--datasource.mysql-jpa.main.jdbc-url=" + server + "/" + SCHEMA,
                "--commerce.pg.base-url=" + pg.baseUrl(),
                "--commerce.pg.callback-url=http://127.0.0.1:" + port + "/api/v1/payments/callback",
                "--spring.kafka.bootstrap-servers=" + kafka.bootstrapServers(),
                "--spring.kafka.admin.properties.bootstrap.servers=" + kafka.bootstrapServers(),
                "--commerce.order-events.topic=" + topic,
                "--commerce.order-events.producer-properties[client.id]=" + clientId);
    }

    /** Clock·probe 정의를 같은 이름으로 바꾼다. bean 덮어쓰기 허용 설정은 쓰지 않는다. */
    private static final class ReplaceBeans implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        private final OrderPublicationProbe probe;

        private ReplaceBeans(OrderPublicationProbe probe) {
            this.probe = probe;
        }

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            ((BeanDefinitionRegistry) context.getBeanFactory()).registerBeanDefinition("orderEventKafkaBrokerTestReplacer",
                new RootBeanDefinition(Replacer.class, () -> new Replacer(probe)));
        }
    }

    private static final class Replacer implements BeanDefinitionRegistryPostProcessor {

        private final OrderPublicationProbe probe;

        private Replacer(OrderPublicationProbe probe) {
            this.probe = probe;
        }

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            replace(registry, OrderEventKafkaConfig.ORDER_CONFIRMATION_CLOCK, new RootBeanDefinition(Clock.class, () -> FIXED_CLOCK));
            if (probe != null) {
                replace(registry, "noOpOrderPublicationProbe", new RootBeanDefinition(OrderPublicationProbe.class, () -> probe));
            }
        }

        private static void replace(BeanDefinitionRegistry registry, String name, RootBeanDefinition definition) {
            if (!registry.containsBeanDefinition(name)) {
                throw new IllegalStateException("교체할 bean 정의가 없습니다. [bean = " + name + "]");
            }
            registry.removeBeanDefinition(name);
            registry.registerBeanDefinition(name, definition);
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        }
    }

    /** 대상 주문의 beforeSend 에서 테스트가 풀 때까지 멈추고, 설정에 따라 통제 실패를 던진다. 다른 주문은 그대로 통과한다. */
    private static final class GateProbe implements OrderPublicationProbe {

        private final boolean failAfterRelease;
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile long target = -1;

        private GateProbe(boolean failAfterRelease) {
            this.failAfterRelease = failAfterRelease;
        }

        void target(long orderId) {
            this.target = orderId;
        }

        void release() {
            released.countDown();
        }

        @Override
        public void beforeSend(OrderConfirmed event) {
            if (event.orderId() != target) {
                return;
            }
            reached.countDown();
            try {
                if (!released.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("beforeSend gate 가 풀리지 않았습니다.");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            if (failAfterRelease) {
                throw new IllegalStateException("통제된 beforeSend 실패");
            }
        }
    }
}
