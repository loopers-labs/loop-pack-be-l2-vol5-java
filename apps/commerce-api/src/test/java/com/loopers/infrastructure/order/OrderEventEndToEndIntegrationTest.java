package com.loopers.infrastructure.order;

import com.loopers.infrastructure.order.support.W6PreparationContextInitializer;
import com.loopers.infrastructure.order.support.W6OrderEventKafkaConfig;
import com.loopers.infrastructure.order.support.W6OrderPublicationProbe;
import com.loopers.config.OrderConfirmationClockConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.CommerceApiApplication;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.fixture.PgCommands;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.fixture.pg.CreateOutcome;
import com.loopers.fixture.pg.PgFixtureServer;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import com.loopers.testsupport.LocalKafkaTestSupport;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * W6 조립 연결(설계 W7.4-4). 제품에서 직접 발행 조립이 빠졌으므로 API 컨텍스트에 W6 준비 컨텍스트 초기화기를 붙여 원래 기대·자원 수명을
 * 그대로 유지한다. 최종 전환(O5/O10 발행 전용, C10 의 O11 흡수)은 후속 단위이며 이 단위에서는 실행하지 않는다.
 *
 * <p>C10: 실제 주문 API·로컬 PG fixture·결과 적용 TX·실제 Kafka adapter → 공유 로컬 Docker Kafka → 별도 JVM 의 streamer bootJar listener →
 * 같은 테스트 MySQL 의 구매 테이블까지 연결한다(소비 설계 7절 「종단 검증의 앱 경계와 자료 보존」).
 *
 * <p>한 재현 안에서 두 사건을 남긴다. 미발행 사건은 commit 뒤 beforeSend 에 통제 실패를 주입한 것이며 OS 강제 종료가 아니다.
 * 정상 사건은 commit 뒤 send 전에 잠시 세워 둔 동안 품목 상품 하나를 기존 삭제 경로로 지운 뒤 보낸다.
 * DB 는 기존 API 테스트 MySQL fixture 의 기본 DB 를 쓴다(발행 검증 전용 스키마 예외를 쓰지 않는다). 부모 API 가 먼저 create 로 스키마를
 * 준비하고, 자식 streamer 는 자기 두 테이블만 create 한다. 자식은 두 사건이 끝날 때까지 한 번만 띄운다.
 * 정리는 자식 JVM → API 컨텍스트(테스트 데이터 정리 포함) → PG fixture → 구매 테이블 → Kafka 소유 group·topic 순으로 모두 시도한다.
 *
 * <p>구현 07(설계 W7.4-4 C10 이관): 이 파일은 독립 JUnit 진입(@Test·@BeforeAll/@AfterAll)을 W6OutboxRecoveryEndToEndIntegrationTest(O11)로 옮긴
 * 지원 코드다. W6 준비 API 기동·A/B 준비·관찰·assertUnpublished/assertConsumedOnce 의 기대는 그대로이며, O11 이 자원 수명을 소유하고
 * 복구 전에 이 검사를 모두 실행한다. 바뀐 것은 진입점(attach·bootW6Api·startStreamerAndAssertReady·reproduceUnpublishedAndConsumedEvents)과
 * 자식 기동의 소유 표시(05a 의 launchChild 사용)뿐이다. 과거 C10 실행 기록은 그 시점 결과다.
 */
class OrderEventEndToEndIntegrationTest {

    private static final Duration DEADLINE = Duration.ofSeconds(60);
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-29T01:02:03.123456789Z"), ZoneId.of("Asia/Seoul"));
    private static final String CONFIRMED_AT = "2026-10-29T01:02:03.123456Z";
    private static final String CONFIRMED_AT_SQL = "2026-10-29 01:02:03.123456";
    private static final Set<String> PURCHASE_TABLES = Set.of("processed_order_confirmation", "product_purchase_activity");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static LocalKafkaTestSupport kafka;
    private static PgFixtureServer pg;
    private static ConfigurableApplicationContext api;
    private static OrderActivityStreamerChild streamer;
    private static JdbcTemplate fresh;
    private static String topic;
    private static String group;
    private static final C10Probe PROBE = new C10Probe();
    /** 주문 ID → 주문한 사용자 ID. 확정 요청의 X-USER-ID 에 쓴다. */
    private static final Map<Long, Long> USERS = new java.util.concurrent.ConcurrentHashMap<>();

    private final RestTemplate http = client();

    /** O11 이 만든 소유 자원을 연결한다. 기존 @BeforeAll 의 연결·준비 순서는 O11 이 같은 의미로 수행한다. */
    static void attach(LocalKafkaTestSupport ownedKafka, String ownedTopic, String ownedGroup, PgFixtureServer ownedPg,
                       JdbcTemplate ownedFresh) {
        kafka = ownedKafka;
        topic = ownedTopic;
        group = ownedGroup;
        pg = ownedPg;
        fresh = ownedFresh;
    }

    /** 기존 C10 의 W6 준비 API(W6 초기화기·고정 확정 Clock·beforeSend probe, ddl-auto=create)를 띄운다. */
    static ConfigurableApplicationContext bootW6Api() throws Exception {
        api = bootApi();
        return api;
    }

    static OrderActivityStreamerChild streamer() {
        return streamer;
    }

    /** 원래 C10 @Test 본문에서 자식 기동을 뺀 나머지. 모든 기대를 실행한 뒤 O11 의 복구 기준을 돌려준다. */
    C10Cases reproduceUnpublishedAndConsumedEvents(CapturedOutput output) throws Exception {
        warmUpProducer();
        UnpublishedCase missed = confirmWithFailureBeforeSend(output);
        ConsumedCase consumed = confirmWithProductDeletedBeforeSend();
        // A 의 부재도 B 가 끝난 뒤의 고정 구간·DB 로 본다.
        BrokerObservation broker = awaitConsumedRecordAndOffset(missed.start(), consumed.order());
        PurchaseObservation purchase = observePurchase(consumed.order());
        printObservations(missed, consumed, broker, purchase);
        assertAll(
            () -> assertUnpublished(output, missed, broker),
            () -> assertConsumedOnce(output, consumed, broker, purchase)
        );
        return new C10Cases(missed.order(), consumed.order(), consumed.deletedProduct(), broker.record());
    }

    /** O11 이 복구 단계에 쓰는 C10 결과: 미발행 A·소비된 B·B 에서 삭제한 상품·B 의 최초 record. */
    record C10Cases(Long unpublished, Long consumed, Long deletedProduct, ConsumerRecord<String, String> consumedRecord) {
    }

    /** A 처리 직후의 관찰. 고정 구간의 시작 offset 도 여기서 정한다. */
    private record UnpublishedCase(Long order, Map<TopicPartition, Long> start, ResponseEntity<JsonNode> response,
                                   double sendsBefore, double sendsAfter, Map<String, Object> facts) {
    }

    private record ConsumedCase(Long order, Long deletedProduct, ResponseEntity<JsonNode> response) {
    }

    /** [A 시작, B 소비 뒤 끝) 구간 전체와 그중 B 의 record, B 반영 뒤 group 의 committed offset. */
    private record BrokerObservation(Map<TopicPartition, Long> end, List<ConsumerRecord<String, String>> range,
                                     ConsumerRecord<String, String> record, Map<TopicPartition, Long> committed) {
    }

    /** B 반영 뒤 새 조회로 읽은 완료·활동 행과 주문 사실. */
    private record PurchaseObservation(Map<String, Object> processed, List<List<Object>> activities, List<List<Long>> storedItems,
                                       Map<String, Object> facts) {
    }

    /**
     * API 스키마가 먼저 있고, 자식은 자기 두 테이블만 만든 뒤 listener 가 두 partition 을 배정받아야 주문을 시작한다.
     * 구매 테이블 부재 확인과 소유 표시는 05a 의 launchChild 가 기동 전에 한다(기동·준비 실패에도 소유가 남음).
     */
    static void startStreamerAndAssertReady(OrderOutboxDeliveryEndToEndIntegrationTest.Ownership ownership) throws Exception {
        Map<String, String> apiTablesBefore = tableCreateTimes();
        OrderOutboxDeliveryEndToEndIntegrationTest.launchChild(ownership, apiTablesBefore.keySet(),
            new OrderOutboxDeliveryEndToEndIntegrationTest.ChildLaunch() {
                @Override
                public void start() throws Exception {
                    startStreamer();
                }

                @Override
                public void awaitReady() {
                    awaitStreamerReady();
                }
            });
        Map<String, String> tablesAfter = tableCreateTimes();
        Map<String, String> apiTablesAfter = tablesAfter.entrySet().stream().filter(entry -> !PURCHASE_TABLES.contains(entry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        ConsumerGroupDescription assigned = awaitListenerAssignment();
        List<Long> purchaseRowsAtStart = purchaseRows();
        String childLog = streamerOutput();
        System.out.println("[C10 CHILD READY] tables=" + tablesAfter.keySet() + " member=" + describe(assigned)
            + " purchaseRows=" + purchaseRowsAtStart);
        assertAll(
            () -> assertThat(tablesAfter.keySet()).containsAll(PURCHASE_TABLES),
            () -> assertThat(apiTablesAfter).as("API 업무 테이블은 자식 기동 전후 같은 생성 시각으로 남아 있다").isEqualTo(apiTablesBefore),
            () -> assertThat(purchaseRowsAtStart).containsExactly(0L, 0L),
            () -> assertThat(childLog).contains("Started CommerceStreamerApplication")
                .contains("Found 2 JPA repository interfaces")
                .contains("Subscribed to topic(s): " + topic)
                .doesNotContain("demo.internal.topic-v1")
        );
    }

    /** A. commit 뒤 send 직전 통제 실패: broker·구매 활동 누락. */
    private UnpublishedCase confirmWithFailureBeforeSend(CapturedOutput output) {
        Long missed = newOrder("미발행");
        PROBE.failFor(missed);
        pg.enqueuePlan(PgOperation.CREATE, PaymentModel.providerOrderIdOf(missed), ResponsePlan.outcome(CreateOutcome.SUCCESS));
        Map<TopicPartition, Long> start = kafka.endOffsets(topic);
        double sendsBefore = sendTotal();
        ResponseEntity<JsonNode> response = confirm(missed);
        await("미발행 사건의 LISTENER_CALL 실패 로그", () -> output.getAll().contains(
            "ORDER_CONFIRMED_SEND_FAILURE eventId=" + eventId(missed) + " orderId=" + missed + " topic=" + topic + " stage=LISTENER_CALL"));
        return new UnpublishedCase(missed, start, response, sendsBefore, sendTotal(), committedFacts(missed));
    }

    /** B. 정상 발행: commit 뒤 send 직전에 세운 동안 품목 상품 하나를 기존 삭제 경로로 지운다. */
    private ConsumedCase confirmWithProductDeletedBeforeSend() throws Exception {
        Long consumed = newOrder("정상");
        Long deletedProduct = orderProducts(consumed).get(0);
        PROBE.holdFor(consumed);
        pg.enqueuePlan(PgOperation.CREATE, PaymentModel.providerOrderIdOf(consumed), ResponsePlan.outcome(CreateOutcome.SUCCESS));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<ResponseEntity<JsonNode>> pending = worker.submit(() -> confirm(consumed));
            assertThat(PROBE.reached.await(30, TimeUnit.SECONDS)).as("정상 사건 commit 뒤 beforeSend 도달").isTrue();
            Map<String, Object> atHold = committedFacts(consumed);
            api.getBean(ProductFacade.class).delete(deletedProduct);
            assertThat(fresh.queryForObject("SELECT deleted_at IS NOT NULL FROM product WHERE id = ?", Boolean.class, deletedProduct))
                .as("send 전에 상품 삭제 완료").isTrue();
            PROBE.release();
            ResponseEntity<JsonNode> response = pending.get(30, TimeUnit.SECONDS);
            assertCommittedConfirmation(atHold);
            return new ConsumedCase(consumed, deletedProduct, response);
        } finally {
            worker.shutdownNow();
        }
    }

    /** streamer 의 APPLIED 뒤 끝 offset 을 고정하고 모든 partition 의 [start, end) 를 끝까지 읽는다. committed offset 은 broker 값을 기다린다. */
    private static BrokerObservation awaitConsumedRecordAndOffset(Map<TopicPartition, Long> start, Long consumed) {
        await("streamer 의 정상 사건 APPLIED 로그", () -> streamerOutput().contains("eventId=" + eventId(consumed) + " result=APPLIED"));
        Map<TopicPartition, Long> end = kafka.endOffsets(topic);
        List<ConsumerRecord<String, String>> range = kafka.readRange(start, end);
        List<ConsumerRecord<String, String>> consumedRecords = range.stream().filter(record -> record.key().equals(consumed.toString())).toList();
        assertThat(consumedRecords).as("정상 사건 record 하나").hasSize(1);
        ConsumerRecord<String, String> record = consumedRecords.get(0);
        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
        await("정상 사건 다음 위치까지 broker committed offset 진행",
            () -> Long.valueOf(record.offset() + 1).equals(kafka.committedOffsets(group).get(partition)));
        return new BrokerObservation(end, range, record, kafka.committedOffsets(group));
    }

    private static PurchaseObservation observePurchase(Long consumed) {
        Map<String, Object> processed = fresh.queryForMap("""
            SELECT event_id, order_id, event_type, schema_version, CAST(confirmed_at AS CHAR) AS confirmed_at, item_count
            FROM processed_order_confirmation WHERE order_id = ?""", consumed);
        List<List<Object>> activities = fresh.query("""
            SELECT product_id, quantity, CAST(confirmed_at AS CHAR) AS confirmed_at FROM product_purchase_activity
            WHERE order_id = ? ORDER BY product_id""", (row, index) -> List.of(row.getLong(1), row.getLong(2), row.getString(3)), consumed);
        return new PurchaseObservation(processed, activities, storedItems(consumed), committedFacts(consumed));
    }

    private static void printObservations(UnpublishedCase missed, ConsumedCase consumed, BrokerObservation broker,
                                          PurchaseObservation purchase) {
        ConsumerRecord<String, String> record = broker.record();
        String childLines = streamerOutput().lines().filter(line -> line.contains("ORDER_ACTIVITY_")).map(String::trim)
            .collect(Collectors.joining("\n  "));
        System.out.println("[C10 A MISSED] order=" + missed.order() + " providerOrderId=" + PaymentModel.providerOrderIdOf(missed.order())
            + " transactionKey=" + transactionKey(missed.order()) + " eventId=" + eventId(missed.order()) + " items=" + storedItems(missed.order())
            + " facts=" + missed.facts() + " response=" + missed.response().getStatusCode().value() + "/" + status(missed.response())
            + " sendTotal=" + missed.sendsBefore() + "->" + missed.sendsAfter() + " probeFailures=" + PROBE.failures.get());
        System.out.println("[C10 B CONSUMED] order=" + consumed.order() + " providerOrderId=" + PaymentModel.providerOrderIdOf(consumed.order())
            + " transactionKey=" + transactionKey(consumed.order()) + " eventId=" + eventId(consumed.order()) + " items=" + purchase.storedItems()
            + " deletedProduct=" + consumed.deletedProduct() + " facts=" + purchase.facts() + " response="
            + consumed.response().getStatusCode().value() + "/" + status(consumed.response()));
        System.out.println("[C10 BROKER] start=" + missed.start() + " end=" + broker.end() + " recordsInRange=" + broker.range().size()
            + " record=" + record.topic() + "/" + record.partition() + "@" + record.offset() + " key=" + record.key() + " value=" + record.value());
        System.out.println("[C10 STREAMER] committed=" + broker.committed() + " processed=" + purchase.processed()
            + " activities=" + purchase.activities() + "\n  " + childLines);
    }

    /** A: 확정·결제·예약은 남고 send 는 없으며, 고정 구간의 모든 partition 과 구매 테이블에 이 사건이 없다. */
    private static void assertUnpublished(CapturedOutput output, UnpublishedCase missed, BrokerObservation broker) {
        Long order = missed.order();
        assertAll(
            () -> assertThat(missed.response().getStatusCode().value()).isEqualTo(200),
            () -> assertThat(status(missed.response())).isEqualTo("CONFIRMED"),
            () -> assertCommittedConfirmation(missed.facts()),
            () -> assertThat(PROBE.failures.get()).as("대상 주문에만 통제 실패 1회").isEqualTo(1),
            () -> assertThat(missed.sendsAfter()).as("adapter send 없음(producer record-send-total 불변)").isEqualTo(missed.sendsBefore()),
            () -> assertThat(output.getAll()).doesNotContain("ORDER_CONFIRMED_SEND_SUCCESS eventId=" + eventId(order) + " "),
            () -> assertThat(broker.range()).as("고정 구간 [start, end) 의 모든 partition").noneMatch(r -> r.key().equals(order.toString())
                || r.value().contains("\"" + eventId(order) + "\"")),
            () -> assertThat(fresh.queryForObject("SELECT COUNT(*) FROM processed_order_confirmation WHERE order_id = ? OR event_id = ?",
                Long.class, order, eventId(order))).isZero(),
            () -> assertThat(fresh.queryForObject("SELECT COUNT(*) FROM product_purchase_activity WHERE order_id = ?", Long.class, order))
                .isZero()
        );
    }

    /** B: 이번 사건의 record·APPLIED·다음 위치 committed offset·완료 1행·모든 활동(삭제 상품 포함)이 저장 품목·시각과 같다. */
    private static void assertConsumedOnce(CapturedOutput output, ConsumedCase consumed, BrokerObservation broker,
                                           PurchaseObservation purchase) throws Exception {
        Long order = consumed.order();
        ConsumerRecord<String, String> record = broker.record();
        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
        JsonNode value = JSON.readTree(record.value());
        List<List<Long>> storedItems = purchase.storedItems();
        assertAll(
            () -> assertThat(consumed.response().getStatusCode().value()).isEqualTo(200),
            () -> assertThat(status(consumed.response())).isEqualTo("CONFIRMED"),
            () -> assertCommittedConfirmation(purchase.facts()),
            () -> assertThat(broker.range()).as("구간의 record 는 정상 사건 하나").hasSize(1),
            () -> assertThat(output.getAll()).contains("ORDER_CONFIRMED_SEND_SUCCESS eventId=" + eventId(order) + " orderId=" + order
                + " topic=" + topic + " partition=" + record.partition() + " offset=" + record.offset()),
            () -> assertThat(value.get("eventId").asText()).isEqualTo(eventId(order)),
            () -> assertThat(value.get("orderId").asLong()).isEqualTo(order),
            () -> assertThat(value.get("confirmedAt").asText()).isEqualTo(CONFIRMED_AT),
            () -> assertThat(itemsOf(value)).isEqualTo(storedItems),
            () -> assertThat(streamerOutput()).contains("ORDER_ACTIVITY_HANDLED_AND_ACK_RETURNED topic=" + topic
                + " partition=" + record.partition() + " offset=" + record.offset() + " eventId=" + eventId(order) + " result=APPLIED"),
            () -> assertThat(broker.committed()).containsEntry(partition, record.offset() + 1),
            () -> assertThat(purchase.processed()).containsAllEntriesOf(Map.of("event_id", eventId(order), "event_type", "OrderConfirmed",
                "schema_version", 1, "confirmed_at", CONFIRMED_AT_SQL, "item_count", storedItems.size())),
            () -> assertThat(((Number) purchase.processed().get("order_id")).longValue()).isEqualTo(order),
            () -> assertThat(purchase.activities()).isEqualTo(storedItems.stream()
                .map(item -> List.<Object>of(item.get(0), item.get(1), CONFIRMED_AT_SQL)).toList()),
            () -> assertThat(purchase.activities()).extracting(row -> row.get(0)).contains(consumed.deletedProduct()),
            () -> assertThat(fresh.queryForObject("SELECT COUNT(*) FROM product_purchase_activity", Long.class))
                .as("구매 활동은 정상 사건 품목뿐").isEqualTo(storedItems.size()),
            () -> assertThat(fresh.queryForObject("SELECT COUNT(*) FROM processed_order_confirmation", Long.class)).isEqualTo(1L),
            () -> assertThat(streamerOutput()).doesNotContain("ORDER_ACTIVITY_FAILURE")
        );
    }

    // ---------------------------------------------------------------- 자식 streamer JVM

    // 자식 JVM 의 기동 인자·기동 완료·배정·종료는 OrderActivityStreamerChild 로 옮겼다(구현 05 의 기계적 공유). 대기 한도는 기존 60초다.
    private static void startStreamer() throws IOException {
        streamer = OrderActivityStreamerChild.start(kafka, topic, group, "C10");
    }

    private static void awaitStreamerReady() {
        streamer.awaitReady(DEADLINE);
    }

    private static ConsumerGroupDescription awaitListenerAssignment() {
        return streamer.awaitAssignment(DEADLINE);
    }

    private static String streamerOutput() {
        return streamer == null ? "" : streamer.output();
    }

    private static String describe(ConsumerGroupDescription description) {
        return OrderActivityStreamerChild.describe(description);
    }

    // ---------------------------------------------------------------- API 컨텍스트·입력

    /** 실제 adapter 를 둔 API 컨텍스트. 기본 테스트 DB 에 create 로 스키마를 준비하고 Clock·beforeSend probe 만 같은 이름의 정의로 바꾼다. */
    private static ConfigurableApplicationContext bootApi() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        return new SpringApplicationBuilder(CommerceApiApplication.class).profiles("test")
            .initializers(new W6PreparationContextInitializer(), context -> ((BeanDefinitionRegistry) context.getBeanFactory()).registerBeanDefinition(
                "orderEventEndToEndTestReplacer", new RootBeanDefinition(Replacer.class, Replacer::new)))
            .run("--server.port=" + port, "--management.server.port=0", "--spring.jpa.hibernate.ddl-auto=create",
                "--commerce.pg.base-url=" + pg.baseUrl(),
                "--commerce.pg.callback-url=http://127.0.0.1:" + port + "/api/v1/payments/callback",
                "--spring.kafka.bootstrap-servers=" + kafka.bootstrapServers(),
                "--spring.kafka.admin.properties.bootstrap.servers=" + kafka.bootstrapServers(),
                "--commerce.order-events.topic=" + topic,
                "--commerce.order-events.producer-properties[client.id]=order-event-c10-" + kafka.runId());
    }

    private static final class Replacer implements BeanDefinitionRegistryPostProcessor {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            replace(registry, OrderConfirmationClockConfig.ORDER_CONFIRMATION_CLOCK, new RootBeanDefinition(Clock.class, () -> FIXED_CLOCK));
            replace(registry, "noOpOrderPublicationProbe", new RootBeanDefinition(W6OrderPublicationProbe.class, () -> PROBE));
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

    /** 미발행 대상은 beforeSend 에서 통제 실패, 정상 대상은 테스트가 풀 때까지 세운다. 다른 주문은 통과한다. */
    private static final class C10Probe implements W6OrderPublicationProbe {

        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicInteger failures = new AtomicInteger();
        private volatile long failTarget = -1;
        private volatile long holdTarget = -1;

        void failFor(long orderId) {
            this.failTarget = orderId;
        }

        void holdFor(long orderId) {
            this.holdTarget = orderId;
        }

        void release() {
            released.countDown();
        }

        @Override
        public void beforeSend(OrderConfirmed event) {
            if (event.orderId() == failTarget) {
                failures.incrementAndGet();
                throw new IllegalStateException("통제된 beforeSend 실패");
            }
            if (event.orderId() == holdTarget) {
                reached.countDown();
                try {
                    if (!released.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("beforeSend gate 가 풀리지 않았습니다.");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        }
    }

    /** 실제 쓸 전용 producer 를 별도 준비용 topic 으로 먼저 연결한다. 업무 topic 에는 보내지 않는다. */
    @SuppressWarnings("unchecked")
    private static void warmUpProducer() throws Exception {
        String prep = kafka.createTopic("order-events-c10-prep");
        KafkaTemplate<String, Object> template =
            (KafkaTemplate<String, Object>) api.getBean(W6OrderEventKafkaConfig.ORDER_EVENT_KAFKA_TEMPLATE);
        assertThat(template.send(prep, "warm-up", "warm-up").get(30, TimeUnit.SECONDS).getRecordMetadata().topic()).isEqualTo(prep);
    }

    private static double sendTotal() {
        KafkaTemplate<?, ?> template = (KafkaTemplate<?, ?>) api.getBean(W6OrderEventKafkaConfig.ORDER_EVENT_KAFKA_TEMPLATE);
        return template.metrics().entrySet().stream()
            .filter(entry -> entry.getKey().group().equals("producer-metrics") && entry.getKey().name().equals("record-send-total"))
            .mapToDouble(entry -> ((Number) entry.getValue().metricValue()).doubleValue())
            .sum();
    }

    /** 상품 둘(수량 2·1)의 주문을 실제 주문 API 로 만든다. 주문 ID 와 사용자 ID 를 함께 기억한다. */
    private Long newOrder(String label) {
        Long user = api.getBean(UserFixture.class).createUserWithPoint().getId();
        ProductFixture products = api.getBean(ProductFixture.class);
        Long first = products.createProduct("C10 " + label + " 운동화", 2_000L, 5L).getId();
        Long second = products.createProduct("C10 " + label + " 양말", 500L, 5L).getId();
        ResponseEntity<JsonNode> created = exchange(user, "/api/v1/orders", Map.of("items",
            List.of(Map.of("productId", second, "quantity", 1), Map.of("productId", first, "quantity", 2))));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        Long order = created.getBody().path("data").path("id").asLong();
        USERS.put(order, user);
        return order;
    }

    private ResponseEntity<JsonNode> confirm(Long order) {
        return exchange(USERS.get(order), "/api/v1/orders/" + order + "/confirm", PgCommands.forOrder(order));
    }

    private ResponseEntity<JsonNode> exchange(Long user, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", user.toString());
        int port = ((ServletWebServerApplicationContext) api).getWebServer().getPort();
        return http.exchange("http://127.0.0.1:" + port + path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static String status(ResponseEntity<JsonNode> response) {
        return response.getBody().path("data").path("status").asText();
    }

    // ---------------------------------------------------------------- 새 DB 조회

    /** 기본 테스트 DB 의 테이블 → 생성 시각. 생성 시각이 그대로면 drop·재생성되지 않은 것이다. */
    private static Map<String, String> tableCreateTimes() {
        return fresh.query("SELECT TABLE_NAME, CAST(CREATE_TIME AS CHAR) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
                (row, index) -> Map.entry(row.getString(1), String.valueOf(row.getString(2))))
            .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static List<Long> purchaseRows() {
        return List.of(
            fresh.queryForObject("SELECT COUNT(*) FROM processed_order_confirmation", Long.class),
            fresh.queryForObject("SELECT COUNT(*) FROM product_purchase_activity", Long.class));
    }

    private static List<Long> orderProducts(Long order) {
        return fresh.queryForList("SELECT product_id FROM order_item WHERE order_id = ? ORDER BY product_id", Long.class, order);
    }

    private static List<List<Long>> storedItems(Long order) {
        return fresh.query("SELECT product_id, SUM(quantity) AS quantity FROM order_item WHERE order_id = ? GROUP BY product_id "
            + "ORDER BY product_id", (row, index) -> List.of(row.getLong("product_id"), row.getLong("quantity")), order);
    }

    private static String transactionKey(Long order) {
        return fresh.queryForObject("SELECT transaction_key FROM payment WHERE order_id = ?", String.class, order);
    }

    /** 주문·결제·예약 이력과 품목 상품의 현재 재고. 예약 소비는 선차감·ORDER_RESERVATION 유지·ORDER_RELEASE 없음으로 본다. */
    private static Map<String, Object> committedFacts(Long order) {
        return Map.of(
            "order", fresh.queryForMap("SELECT status, paid_amount, total_amount, "
                + "DATE_FORMAT(confirmed_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS confirmed_at FROM orders WHERE id = ?", order),
            "payment", fresh.queryForMap("SELECT status, amount FROM payment WHERE order_id = ?", order),
            "reservations", fresh.queryForObject("SELECT COUNT(*) FROM stock_history WHERE order_id = ? AND cause = 'ORDER_RESERVATION'",
                Long.class, order),
            "releases", fresh.queryForObject("SELECT COUNT(*) FROM stock_history WHERE order_id = ? AND cause = 'ORDER_RELEASE'",
                Long.class, order),
            "stock", fresh.query("SELECT p.id, p.stock_quantity FROM product p JOIN order_item i ON i.product_id = p.id "
                + "WHERE i.order_id = ? ORDER BY p.id", (row, index) -> List.of(row.getLong(1), row.getLong(2)), order));
    }

    @SuppressWarnings("unchecked")
    private static void assertCommittedConfirmation(Map<String, Object> facts) {
        Map<String, Object> orderRow = (Map<String, Object>) facts.get("order");
        Map<String, Object> paymentRow = (Map<String, Object>) facts.get("payment");
        List<List<Long>> stock = (List<List<Long>>) facts.get("stock");
        assertAll(
            () -> assertThat(orderRow.get("status")).isEqualTo("CONFIRMED"),
            () -> assertThat(orderRow.get("confirmed_at")).isEqualTo(CONFIRMED_AT),
            () -> assertThat(((Number) orderRow.get("paid_amount")).longValue()).isEqualTo(((Number) orderRow.get("total_amount")).longValue()),
            () -> assertThat(paymentRow.get("status")).isEqualTo("SUCCESS"),
            () -> assertThat(((Number) paymentRow.get("amount")).longValue()).isEqualTo(((Number) orderRow.get("total_amount")).longValue()),
            () -> assertThat(facts.get("reservations")).isEqualTo(2L),
            () -> assertThat(facts.get("releases")).isEqualTo(0L),
            () -> assertThat(stock).extracting(row -> row.get(1)).containsExactlyInAnyOrder(3L, 4L)
        );
    }

    private static List<List<Long>> itemsOf(JsonNode json) {
        List<List<Long>> items = new ArrayList<>();
        json.get("items").forEach(item -> items.add(List.of(item.get("productId").asLong(), item.get("quantity").asLong())));
        return items;
    }

    private static String eventId(Long order) {
        return "order-confirmed:" + order;
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(what + " 가 deadline(" + DEADLINE.toSeconds() + "초) 안에 관찰되지 않았습니다.");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static RestTemplate client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return new RestTemplate(factory);
    }
}
