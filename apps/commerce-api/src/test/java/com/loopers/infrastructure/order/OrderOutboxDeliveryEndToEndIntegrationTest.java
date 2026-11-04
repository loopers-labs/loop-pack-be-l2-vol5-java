package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.CommerceApiApplication;
import com.loopers.application.order.OrderOutboxExecutionGate;
import com.loopers.application.order.OrderOutboxExecutionGate.Outcome;
import com.loopers.application.order.OrderOutboxExecutionGate.Result;
import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OrderOutboxRelay.RowOutcome;
import com.loopers.application.order.OrderOutboxRelay.RowResult;
import com.loopers.application.order.OrderOutboxRelayProbe;
import com.loopers.application.order.OrderOutboxRelayTransactionService;
import com.loopers.config.OrderConfirmationClockConfig;
import com.loopers.config.OrderOutboxClockConfig;
import com.loopers.config.OrderOutboxKafkaConfig;
import com.loopers.config.OrderOutboxRelayProperties;
import com.loopers.domain.order.OrderOutboxAttempt;
import com.loopers.domain.order.OrderOutboxMessageVerifier;
import com.loopers.domain.order.OrderOutboxReceipt;
import com.loopers.domain.order.OrderOutboxSender;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.fixture.PgCommands;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.SettableClock;
import com.loopers.fixture.UserFixture;
import com.loopers.fixture.pg.CreateOutcome;
import com.loopers.fixture.pg.PgFixtureServer;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.RequestCounts;
import com.loopers.fixture.pg.ResponsePlan;
import com.loopers.infrastructure.order.OrderEventTestCleanup.Step;
import com.loopers.testsupport.LocalKafkaTestSupport;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * O6→O7·O9 실제 전달 E2E(설계 W7.4-3a). 정상 W7 API 컨텍스트(실제 recorder·relay 협력 bean·String sender, 자동 relay OFF)·로컬 PG fixture·
 * 기존 테스트 MySQL 의 기본 DB·공유 로컬 Kafka 의 실행 소유 2-partition topic/group·실제 streamer bootJar 자식 JVM 하나를 한 실행이 소유한다.
 *
 * <p>D: 최초 relay R1 이 실제 broker 성공 뒤 D 전용 one-shot probe 로 PUBLISHED 저장 전에 멈춘다(통제 중단, OS·JVM 강제 종료가 아님).
 * 첫 record 의 APPLIED 로 O6 를, 새 relay R2 의 재발행 record(다른 offset)·ALREADY_PROCESSED 로 O7 을 확인한다.
 * E: 소유 topic 의 min.insync.replicas 를 복제 계수보다 크게 바꿔 실제 send 실패 한 번을 만들고, 원래 설정으로 되돌린 뒤 같은 행을 다시 보낸다(O9).
 *
 * <p>시간 통제는 outboxRecordClock 만 SettableClock 으로 바꿔 전진한다. 확정 시각·감사 시각·Kafka·deadline 은 실제 시간이다.
 * Outbox 상태·횟수·시각을 SQL 로 바꾸지 않는다. 전체 300초 안에서 단계별 대기는 남은 예산과 broker 준비 30초·관찰 60초 중 작은 값이다.
 */
@Timeout(300)
@DisplayName("W7 실제 전달: 같은 주문의 두 중단·재발행은 구매 활동 한 번으로 수렴하고, 별도 주문의 실제 Kafka 실패는 같은 행 재시도로 회복한다.")
class OrderOutboxDeliveryEndToEndIntegrationTest {

    private static final String LABEL = "W7E2E";
    private static final Duration TOTAL = Duration.ofSeconds(300);
    private static final Duration BROKER_READY = Duration.ofSeconds(30);
    private static final Duration OBSERVE = Duration.ofSeconds(60);
    private static final Set<String> PURCHASE_TABLES = Set.of("processed_order_confirmation", "product_purchase_activity");
    private static final String MIN_ISR = "min.insync.replicas";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final long STARTED = System.nanoTime();
    private static final Instant T0 = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final SettableClock OUTBOX_CLOCK = new SettableClock(T0, ZoneOffset.UTC);
    private static final Map<Long, Long> USERS = new ConcurrentHashMap<>();
    private static final Map<String, Long> TIMES = new ConcurrentHashMap<>();

    private static LocalKafkaTestSupport kafka;
    private static PgFixtureServer pg;
    private static ConfigurableApplicationContext api;
    private static OrderActivityStreamerChild streamer;
    private static JdbcTemplate fresh;
    private static Admin topicAdmin;
    private static String topic;
    private static String group;
    /** 구매 테이블 소유권. 시작 전 부재 확인을 통과한 뒤에만 소유하며, child 준비 실패에도 유지된다. */
    private static final Ownership OWNERSHIP = new Ownership();
    private static ConfigEntry originalIsr;
    private static boolean isrAltered;

    private final RestTemplate http = client();

    @BeforeAll
    static void prepare() throws Exception {
        long begin = System.nanoTime();
        kafka = LocalKafkaTestSupport.connect();
        System.out.println("[" + LABEL + " KAFKA] bootstrap=" + kafka.bootstrapServers() + " runId=" + kafka.runId()
            + " broker=" + kafka.brokerInfo());
        topic = kafka.createTopic("order-outbox-e2e");
        group = kafka.ownGroup("purchase-activity-w7");
        topicAdmin = Admin.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers(),
            AdminClientConfig.CLIENT_ID_CONFIG, "order-outbox-e2e-admin-" + kafka.runId(),
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) BROKER_READY.toMillis()));
        pg = PgFixtureServer.start();
        // 기존 MySQL·Redis fixture 를 먼저 띄워 접속 속성을 확정한다(API 기동 전에 접속 대상을 확인하기 위함).
        Class.forName("com.loopers.testcontainers.MySqlTestContainersConfig");
        Class.forName("com.loopers.testcontainers.RedisTestContainersConfig");
        String url = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        fresh = new JdbcTemplate(new DriverManagerDataSource(url, System.getProperty("datasource.mysql-jpa.main.username"),
            System.getProperty("datasource.mysql-jpa.main.password")));
        System.out.println("[" + LABEL + " DB] url=" + url + " database=" + fresh.queryForObject("SELECT DATABASE()", String.class));
        api = bootApi();
        TIMES.put("prepareMs", elapsedMs(begin));
    }

    /** 새 relay 진입 중단 → topic 설정 복원 → child 종료 확인 → API 데이터·컨텍스트 → PG → 구매 테이블 → 관리 client → Kafka 소유 자원. */
    @AfterAll
    static void releaseResources() {
        long begin = System.nanoTime();
        try {
            release(OWNERSHIP, new CleanupSteps() {
                @Override
                public void stopRelay() throws InterruptedException {
                    if (api != null && api.isActive()) {
                        OrderOutboxExecutionGate gate = api.getBean(OrderOutboxExecutionGate.class);
                        gate.stop();
                        if (!gate.awaitIdle(Duration.ofSeconds(10))) {
                            throw new IllegalStateException("진행 중인 relay 가 10초 안에 끝나지 않았습니다.");
                        }
                    }
                }

                @Override
                public void restoreTopicConfig() throws Exception {
                    if (isrAltered) {
                        restoreIsr();
                    }
                }

                @Override
                public void stopChild() throws InterruptedException {
                    if (streamer != null) {
                        streamer.stop(LABEL, OBSERVE);
                    }
                }

                @Override
                public boolean childExited() {
                    return streamer == null || !streamer.isAlive();
                }

                @Override
                public void truncateApiData() {
                    if (api != null && api.isActive()) {
                        api.getBean(DatabaseCleanUp.class).truncateAllTables();
                        api.getBean(RedisCleanUp.class).truncateAll();
                    }
                }

                @Override
                public void closeApi() {
                    if (api != null) {
                        api.close();
                    }
                }

                @Override
                public void closePg() {
                    if (pg != null) {
                        pg.close();
                    }
                }

                @Override
                public void dropPurchaseTables() {
                    PURCHASE_TABLES.forEach(table -> fresh.execute("DROP TABLE IF EXISTS " + table));
                }

                @Override
                public void closeTopicAdmin() {
                    if (topicAdmin != null) {
                        topicAdmin.close(Duration.ofSeconds(10));
                    }
                }

                @Override
                public void closeKafka() {
                    if (kafka != null) {
                        kafka.close();
                    }
                }
            });
        } finally {
            TIMES.put("cleanupMs", elapsedMs(begin));
            System.out.println("[" + LABEL + " TIME] " + TIMES + " totalMs=" + elapsedMs(STARTED));
        }
    }

    // ---------------------------------------------------------------- 소유권·정리 판단(정리 회귀 테스트가 같은 경로를 쓴다)

    /** 이 실행이 소유한 구매 테이블. 시작 전 부재를 확인한 순간부터 이번 child 가 만들 테이블은 이 실행의 것이다. */
    static final class Ownership {

        private boolean purchaseTables;

        /** 시작 전부터 구매 테이블이 있으면 소유하지 않고 실패한다. 그 테이블은 지우지 않고 원인을 볼 수 있게 남긴다. */
        void claimPurchaseTables(Collection<String> existingTables) {
            assertThat(existingTables).as("자식 기동 전 구매 테이블 없음(이전 실행 잔여면 지우지 않고 실패)")
                .doesNotContainAnyElementsOf(PURCHASE_TABLES);
            purchaseTables = true;
        }

        boolean ownsPurchaseTables() {
            return purchaseTables;
        }
    }

    /** child 를 띄우는 두 단계. 기동 뒤 준비 대기가 실패해도 child 참조와 소유권은 정리에 남는다. */
    interface ChildLaunch {

        void start() throws Exception;

        void awaitReady();
    }

    /** 부재 확인 → 소유 → child 기동 → 준비 대기 순서. 소유 표시는 기동 전에 끝나므로 기동·준비 실패에도 이번 테이블을 정리할 수 있다. */
    static void launchChild(Ownership ownership, Collection<String> existingTables, ChildLaunch launch) throws Exception {
        ownership.claimPurchaseTables(existingTables);
        launch.start();
        launch.awaitReady();
    }

    /** 정리 단계의 실제 행동. 판단은 {@link #release} 가 한다. */
    interface CleanupSteps {

        void stopRelay() throws Exception;

        void restoreTopicConfig() throws Exception;

        void stopChild() throws Exception;

        /** 실제 프로세스가 끝났는지. 정상 종료 여부와 별개로 지금 살아 있는지를 본다. */
        boolean childExited();

        void truncateApiData() throws Exception;

        void closeApi() throws Exception;

        void closePg() throws Exception;

        void dropPurchaseTables() throws Exception;

        void closeTopicAdmin() throws Exception;

        void closeKafka() throws Exception;
    }

    /**
     * 모든 단계를 시도하되, writer(relay·child) 종료가 확인되지 않으면 그 writer 가 쓰는 자원의 파괴적 정리를 건너뛴다.
     * API 데이터 TRUNCATE·구매 테이블 DROP 은 relay idle 과 child 실제 종료가 모두 확인돼야 하고, topic·group 삭제는 child 종료가 필요하다.
     * client·context·PG 종료는 독립적으로 계속한다. 첫 오류를 던지고 후속 오류와 남긴 자원을 suppressed 로 붙인다. 남긴 자원 목록을 반환한다.
     */
    static List<String> release(Ownership ownership, CleanupSteps steps) {
        boolean[] relayIdle = {false};
        List<String> kept = new ArrayList<>();
        try {
            OrderEventTestCleanup.runAll(List.of(
                new Step("새 relay 진입 중단", () -> {
                    steps.stopRelay();
                    relayIdle[0] = true;
                }),
                new Step("소유 topic 의 min.insync.replicas 복원", steps::restoreTopicConfig),
                new Step("streamer 자식 JVM 종료", steps::stopChild),
                new Step("API 테스트 데이터 정리", () -> {
                    if (relayIdle[0] && steps.childExited()) {
                        steps.truncateApiData();
                    } else {
                        kept.add("API 테스트 데이터·Redis(relay idle=" + relayIdle[0] + ", child 종료=" + steps.childExited() + ")");
                    }
                }),
                new Step("API 컨텍스트 종료", steps::closeApi),
                new Step("PG fixture 종료", steps::closePg),
                new Step("이 실행이 만든 구매 테이블 삭제", () -> {
                    if (!ownership.ownsPurchaseTables()) {
                        return;
                    }
                    if (relayIdle[0] && steps.childExited()) {
                        steps.dropPurchaseTables();
                    } else {
                        kept.add("구매 테이블 두 개(relay idle=" + relayIdle[0] + ", child 종료=" + steps.childExited() + ")");
                    }
                }),
                new Step("topic 관리 client 종료", steps::closeTopicAdmin),
                new Step("Kafka 소유 topic·group 정리", () -> {
                    if (steps.childExited()) {
                        steps.closeKafka();
                    } else {
                        kept.add("Kafka 소유 topic·group·관리 client(child 생존)");
                    }
                })));
        } catch (RuntimeException failure) {
            if (!kept.isEmpty()) {
                failure.addSuppressed(new IllegalStateException("writer 종료를 확인하지 못해 남긴 자원: " + kept));
            }
            throw failure;
        } finally {
            System.out.println("[" + LABEL + " CLEANUP] kept=" + kept);
        }
        if (!kept.isEmpty()) {
            throw new IllegalStateException("writer 종료를 확인하지 못해 남긴 자원: " + kept);
        }
        return kept;
    }

    @DisplayName("D 의 첫 record 는 APPLIED·재발행 record 는 다른 offset 의 ALREADY_PROCESSED 이고, E 의 실제 send 실패는 PENDING 저장 뒤 같은 행 재전송으로 APPLIED 된다.")
    @Test
    void deliversThroughInterruptionAndBrokerFailure() throws Exception {
        try {
            long begin = System.nanoTime();
            startStreamerAndAssertReady();
            TIMES.put("childReadyMs", elapsedMs(begin));
            begin = System.nanoTime();
            deliverD();
            TIMES.put("orderDMs", elapsedMs(begin));
            recoverE();
        } catch (Throwable failure) {
            printDiagnostics();
            throw failure;
        }
    }

    // ---------------------------------------------------------------- 준비

    /** API 스키마가 먼저 있고 구매 테이블은 없어야 한다. 자식이 자기 두 테이블만 만든 뒤 두 partition 을 배정받고 0행이어야 주문을 시작한다. */
    private static void startStreamerAndAssertReady() throws Exception {
        Map<String, String> apiTablesBefore = tableCreateTimes();
        launchChild(OWNERSHIP, apiTablesBefore.keySet(), new ChildLaunch() {
            @Override
            public void start() throws Exception {
                streamer = OrderActivityStreamerChild.start(kafka, topic, group, LABEL);
            }

            @Override
            public void awaitReady() {
                streamer.awaitReady(limit(OBSERVE));
            }
        });
        Map<String, String> tablesAfter = tableCreateTimes();
        Map<String, String> apiTablesAfter = tablesAfter.entrySet().stream().filter(entry -> !PURCHASE_TABLES.contains(entry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        var assigned = streamer.awaitAssignment(limit(BROKER_READY));
        System.out.println("[" + LABEL + " CHILD READY] tables=" + tablesAfter.keySet() + " member="
            + OrderActivityStreamerChild.describe(assigned) + " purchaseRows=" + purchaseRows());
        assertAll(
            () -> assertThat(tablesAfter.keySet()).containsAll(PURCHASE_TABLES),
            () -> assertThat(apiTablesAfter).as("API 업무 테이블은 자식 기동 전후 같은 생성 시각").isEqualTo(apiTablesBefore),
            () -> assertThat(purchaseRows()).containsExactly(0L, 0L),
            () -> assertThat(streamer.output()).contains("Started CommerceStreamerApplication")
                .contains("Found 2 JPA repository interfaces").contains("Subscribed to topic(s): " + topic)
                .doesNotContain("demo.internal.topic-v1")
        );
    }

    // ---------------------------------------------------------------- D: O6 → O7

    private void deliverD() throws Exception {
        Long order = confirmNewOrder("D");
        String eventId = eventId(order);
        Map<String, Object> baseline = businessFacts(order);
        RequestCounts pgBaseline = pg.state().counts();
        Map<String, Object> pending = outboxRow(order);
        Map<TopicPartition, Long> start = kafka.endOffsets(topic);
        assertAll(
            () -> assertCommittedConfirmation(baseline),
            () -> assertThat(pending).containsEntry("status", "PENDING").containsEntry("attempt_count", 0L)
                .containsEntry("cycle_attempt_count", 0).containsEntry("last_error", null).containsEntry("published_at", null)
                .containsEntry("next_attempt_at", wall(T0)),
            () -> assertThat(purchaseRowsOf(order)).as("relay 전 D 구매 활동 없음").containsExactly(0L, 0L)
        );

        // R1: 최초 relay. 실제 broker 성공 뒤 D 만 대상인 one-shot probe 가 PUBLISHED 저장 전에 멈춘다.
        OneShotProbe probe = new OneShotProbe(eventId, id(pending));
        OrderOutboxRelay r1 = newRelay(probe);
        assertThatThrownBy(r1::runDueBatch).as("통제 중단 예외가 그대로 전파").isSameAs(probe.stop);
        assertThat(gate().tryExecute(() -> true).outcome()).as("R1 종료·gate 해제").isEqualTo(Outcome.EXECUTED);
        OrderOutboxReceipt first = probe.receiptsOf(eventId).get(0);
        Map<String, Object> afterR1 = outboxRow(order);
        assertAll(
            () -> assertThat(first.topic()).isEqualTo(topic),
            () -> assertThat(afterR1).as("PUBLISHED 저장 전 중단: 시도만 commit").containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 1L).containsEntry("cycle_attempt_count", 1)
                .containsEntry("next_attempt_at", wall(T0.plusSeconds(5))).containsEntry("published_at", null)
                .containsEntry("last_error", null),
            () -> assertFacts(pending, afterR1)
        );

        // O6: 첫 record 가 실제로 소비돼 구매 활동이 생겼는지 본다. 중간 PENDING 은 소비 미완료를 뜻하지 않는다.
        awaitHandled(first, eventId, "APPLIED");
        awaitCommitted(first);
        Map<String, Object> processed = processedRow(order);
        List<Map<String, Object>> activities = activityRows(order);
        List<List<Long>> items = storedItems(order);
        String confirmedAt = orderConfirmedAt(order);
        assertAll(
            () -> assertThat(recordsOf(start, order)).as("첫 record").singleElement().satisfies(record -> {
                assertThat(record.partition()).isEqualTo(first.partition());
                assertThat(record.offset()).isEqualTo(first.offset());
                assertThat(record.value()).isEqualTo(pending.get("payload"));
            }),
            () -> assertThat(processed).containsEntry("event_id", eventId).containsEntry("item_count", items.size()),
            () -> assertThat(activities).extracting(row -> List.of(((Number) row.get("product_id")).longValue(),
                ((Number) row.get("quantity")).longValue())).isEqualTo(items),
            () -> assertThat(items).extracting(item -> item.get(1)).containsExactlyInAnyOrder(2L, 1L),
            () -> assertThat(activities).extracting(row -> String.valueOf(row.get("confirmed_at_text"))).containsOnly(confirmedAt)
        );

        // R2: 새 relay. 저장 nextAttemptAt 전에는 재전송하지 않는다.
        OrderOutboxRelay r2 = newRelay(probe);
        Map<TopicPartition, Long> endAfterFirst = kafka.endOffsets(topic);
        Result<List<RowResult>> early = r2.runDueBatch();
        assertAll(
            () -> assertThat(early.value()).as("nextAttemptAt 전 재전송 없음").contains(List.of()),
            () -> assertThat(kafka.endOffsets(topic)).isEqualTo(endAfterFirst),
            () -> assertThat(outboxRow(order)).isEqualTo(afterR1)
        );
        OUTBOX_CLOCK.set(T0.plusSeconds(5), ZoneOffset.UTC);
        Result<List<RowResult>> republished = r2.runDueBatch();
        OrderOutboxReceipt second = probe.receiptsOf(eventId).get(1);

        // O7: 같은 사건의 새 record 는 다른 offset 이고, 소비자는 같은 사실로 보고 DB 를 바꾸지 않는다.
        awaitHandled(second, eventId, "ALREADY_PROCESSED");
        awaitCommitted(second);
        Map<String, Object> publishedRow = outboxRow(order);
        assertAll(
            () -> assertThat(republished.value().orElseThrow()).extracting(RowResult::outcome).containsExactly(RowOutcome.PUBLISHED),
            () -> assertThat(second.topic()).isEqualTo(topic),
            () -> assertThat(List.<Object>of(second.partition(), second.offset())).as("다른 실제 위치")
                .isNotEqualTo(List.<Object>of(first.partition(), first.offset())),
            () -> assertThat(recordsOf(start, order)).extracting(record -> List.<Object>of(record.partition(), record.offset(), record.value()))
                .containsExactly(List.<Object>of(first.partition(), first.offset(), pending.get("payload")),
                    List.<Object>of(second.partition(), second.offset(), pending.get("payload"))),
            () -> assertThat(processedRow(order)).as("완료 행 전체 불변").isEqualTo(processed),
            () -> assertThat(activityRows(order)).as("모든 활동 행 전체 불변").isEqualTo(activities),
            () -> assertThat(purchaseRows()).containsExactly(1L, 2L),
            () -> assertThat(publishedRow).containsEntry("status", "PUBLISHED").containsEntry("attempt_count", 2L)
                .containsEntry("cycle_attempt_count", 2).containsEntry("published_at", wall(T0.plusSeconds(5)))
                .containsEntry("last_error", null),
            () -> assertFacts(pending, publishedRow),
            () -> assertThat(businessFacts(order)).as("주문·결제·예약·재고·포인트 불변").isEqualTo(baseline),
            () -> assertThat(pg.state().counts()).as("relay 전달 중 PG 추가 요청 없음").isEqualTo(pgBaseline),
            () -> assertThat(probe.fired).as("one-shot probe 는 D 에 한 번").isEqualTo(1)
        );
        System.out.println("[" + LABEL + " D] order=" + order + " eventId=" + eventId + " outboxId=" + id(pending)
            + " providerOrderId=" + PaymentModel.providerOrderIdOf(order) + " items=" + items + " first=" + position(first)
            + " second=" + position(second) + " committed=" + kafka.committedOffsets(group) + " outbox=" + summary(publishedRow)
            + " pgCounts=" + pgBaseline);
        awaitQuiet();
    }

    // ---------------------------------------------------------------- E: O9

    private void recoverE() throws Exception {
        long begin = System.nanoTime();
        Long order = confirmNewOrder("E");
        String eventId = eventId(order);
        Map<String, Object> baseline = businessFacts(order);
        RequestCounts pgBaseline = pg.state().counts();
        Map<String, Object> pending = outboxRow(order);
        Map<TopicPartition, Long> start = kafka.endOffsets(topic);
        Instant recordedAt = T0.plusSeconds(5);
        assertAll(
            () -> assertCommittedConfirmation(baseline),
            () -> assertThat(pending).containsEntry("status", "PENDING").containsEntry("attempt_count", 0L)
                .containsEntry("next_attempt_at", wall(recordedAt)),
            () -> assertThat(purchaseRowsOf(order)).containsExactly(0L, 0L)
        );

        // 실제 실패 1회: 이 실행이 만든 topic 만 min.insync.replicas 를 복제 계수(1)보다 크게 둔다.
        originalIsr = isrEntry();
        alterIsr(AlterConfigOp.OpType.SET, "2");
        isrAltered = true;
        assertThat(isrEntry().value()).isEqualTo("2");
        Map<String, Double> metricsBefore = producerMetrics();
        OneShotProbe probe = new OneShotProbe("none", -1L);
        long sendBegin = System.nanoTime();
        Result<List<RowResult>> failed = newRelay(probe).runDueBatch();
        long failMs = elapsedMs(sendBegin);
        Map<String, Double> metricsAfter = producerMetrics();
        Map<String, Object> afterFailure = outboxRow(order);
        Map<TopicPartition, Long> endAfterFailure = kafka.endOffsets(topic);
        restoreIsr();
        TIMES.put("orderEFailedSendMs", failMs);
        System.out.println("[" + LABEL + " E FAILURE] order=" + order + " eventId=" + eventId + " elapsedMs=" + failMs
            + " result=" + failed.value() + " outbox=" + summary(afterFailure) + " isrOriginal=" + originalIsr.value() + "/"
            + originalIsr.source() + " endBefore=" + start + " endAfter=" + endAfterFailure + " producerBefore=" + metricsBefore
            + " producerAfter=" + metricsAfter);
        assertAll(
            () -> assertThat(failed.value().orElseThrow()).extracting(RowResult::outcome).containsExactly(RowOutcome.RETRY_SCHEDULED),
            () -> assertThat(afterFailure).containsEntry("status", "PENDING").containsEntry("attempt_count", 1L)
                .containsEntry("cycle_attempt_count", 1).containsEntry("published_at", null)
                .containsEntry("next_attempt_at", wall(recordedAt.plusSeconds(5))),
            () -> assertThat((String) afterFailure.get("last_error")).as("실제 Future 실패의 안전 code").startsWith("SEND_COMPLETION_FAILED:"),
            () -> assertThat(metricsAfter.get("record-error-total") - metricsBefore.get("record-error-total"))
                .as("같은 producer 의 실제 record 오류").isEqualTo(1.0),
            () -> assertThat(probe.receipts).as("broker 성공 없음").isEmpty(),
            () -> assertFacts(pending, afterFailure)
        );

        // 원래 설정 복원 뒤 같은 행·같은 producer 로 회복한다.
        OUTBOX_CLOCK.set(recordedAt.plusSeconds(5), ZoneOffset.UTC);
        long recoverBegin = System.nanoTime();
        Result<List<RowResult>> recovered = newRelay(probe).runDueBatch();
        OrderOutboxReceipt receipt = probe.receiptsOf(eventId).get(0);
        awaitHandledOnce(eventId);
        awaitCommitted(receipt);
        TIMES.put("orderERecoverMs", elapsedMs(recoverBegin));
        Map<String, Object> publishedRow = outboxRow(order);
        List<ConsumerRecord<String, String>> records = recordsOf(start, order);
        List<List<Long>> items = storedItems(order);
        List<Map<String, Object>> activities = activityRows(order);
        String handledAtReceipt = handledLine(receipt, eventId);
        assertAll(
            () -> assertThat(recovered.value().orElseThrow()).extracting(RowResult::outcome).containsExactly(RowOutcome.PUBLISHED),
            () -> assertThat(handledAtReceipt).as("회복 record 의 소비 결과").isNotNull(),
            () -> assertThat(records).as("회복 record 는 receipt 위치의 저장 원문").anySatisfy(record -> {
                assertThat(List.<Object>of(record.partition(), record.offset())).isEqualTo(List.<Object>of(receipt.partition(), receipt.offset()));
                assertThat(record.value()).isEqualTo(pending.get("payload"));
            }),
            () -> assertThat(records).allSatisfy(record -> assertThat(record.value()).isEqualTo(pending.get("payload"))),
            () -> assertThat(processedRow(order)).containsEntry("event_id", eventId).containsEntry("item_count", items.size()),
            () -> assertThat(activities).extracting(row -> List.of(((Number) row.get("product_id")).longValue(),
                ((Number) row.get("quantity")).longValue())).isEqualTo(items),
            () -> assertThat(activities).extracting(row -> String.valueOf(row.get("confirmed_at_text"))).containsOnly(orderConfirmedAt(order)),
            () -> assertThat(purchaseRows()).as("D 1건·E 1건, 활동 2+2").containsExactly(2L, 4L),
            () -> assertThat(publishedRow).containsEntry("status", "PUBLISHED").containsEntry("attempt_count", 2L)
                .containsEntry("cycle_attempt_count", 2).containsEntry("last_error", null)
                .containsEntry("published_at", wall(recordedAt.plusSeconds(5))),
            () -> assertFacts(pending, publishedRow),
            () -> assertThat(businessFacts(order)).isEqualTo(baseline),
            () -> assertThat(pg.state().counts()).isEqualTo(pgBaseline),
            () -> assertThat(streamer.output()).doesNotContain("ORDER_ACTIVITY_FAILURE")
        );
        System.out.println("[" + LABEL + " E RECOVERED] order=" + order + " eventId=" + eventId + " receipt=" + position(receipt)
            + " eRecords=" + records.stream().map(record -> record.partition() + "@" + record.offset()).toList()
            + " handled=" + handledAtReceipt + " committed=" + kafka.committedOffsets(group) + " outbox=" + summary(publishedRow)
            + " isrRestored=" + isrEntry().value() + "/" + isrEntry().source());
        TIMES.put("orderEMs", elapsedMs(begin));
    }

    // ---------------------------------------------------------------- relay·probe

    /** 같은 제품 클래스의 새 relay. 실제 TX 프록시·verifier·sender·공유 gate·교체한 outboxRecordClock·앱 설정을 쓴다. */
    private static OrderOutboxRelay newRelay(OrderOutboxRelayProbe probe) {
        return new OrderOutboxRelay(api.getBean(OrderOutboxRelayTransactionService.class), api.getBean(OrderOutboxMessageVerifier.class),
            api.getBean(OrderOutboxSender.class), probe, gate(), outboxClock(), api.getBean(OrderOutboxRelayProperties.class));
    }

    private static OrderOutboxExecutionGate gate() {
        return api.getBean(OrderOutboxExecutionGate.class);
    }

    private static Clock outboxClock() {
        return api.getBean(OrderOutboxClockConfig.OUTBOX_RECORD_CLOCK, Clock.class);
    }

    /** 모든 broker 확인 위치를 기록하고, 지정한 eventId·outboxId 에서만 한 번 예외로 멈춘다. */
    private static final class OneShotProbe implements OrderOutboxRelayProbe {

        private final String targetEventId;
        private final long targetOutboxId;
        private final IllegalStateException stop = new IllegalStateException("통제 중단: broker 확인 뒤 PUBLISHED 저장 전");
        private final List<Map.Entry<String, OrderOutboxReceipt>> receipts = new CopyOnWriteArrayList<>();
        private volatile int fired;

        private OneShotProbe(String targetEventId, long targetOutboxId) {
            this.targetEventId = targetEventId;
            this.targetOutboxId = targetOutboxId;
        }

        List<OrderOutboxReceipt> receiptsOf(String eventId) {
            return receipts.stream().filter(entry -> entry.getKey().equals(eventId)).map(Map.Entry::getValue).toList();
        }

        @Override
        public void afterBrokerConfirmed(OrderOutboxAttempt attempt, OrderOutboxReceipt receipt) {
            receipts.add(Map.entry(attempt.message().eventId(), receipt));
            if (fired == 0 && attempt.outboxId() == targetOutboxId && targetEventId.equals(attempt.message().eventId())) {
                fired++;
                throw stop;
            }
        }
    }

    // ---------------------------------------------------------------- broker·child 관찰

    private static void awaitHandled(OrderOutboxReceipt receipt, String eventId, String result) {
        String line = "ORDER_ACTIVITY_HANDLED_AND_ACK_RETURNED topic=" + receipt.topic() + " partition=" + receipt.partition()
            + " offset=" + receipt.offset() + " eventId=" + eventId + " result=" + result;
        OrderActivityStreamerChild.await("child 처리 " + position(receipt) + " " + result, limit(OBSERVE), () -> {
            assertThat(streamer.isAlive()).as("child 생존").isTrue();
            return streamer.output().contains(line);
        });
    }

    /** E 는 실패 시도의 broker 기록 0 을 단정하지 않는다. 그 사건의 APPLIED 가 정확히 한 번이고 receipt 위치도 처리됐을 때까지 기다린다. */
    private static void awaitHandledOnce(String eventId) {
        OrderActivityStreamerChild.await("child 의 " + eventId + " APPLIED", limit(OBSERVE),
            () -> streamer.output().contains("eventId=" + eventId + " result=APPLIED"));
        assertThat(streamer.output().lines().filter(line -> line.contains("eventId=" + eventId + " result=APPLIED")).count())
            .as(eventId + " APPLIED 한 번").isEqualTo(1L);
    }

    private static String handledLine(OrderOutboxReceipt receipt, String eventId) {
        String prefix = "ORDER_ACTIVITY_HANDLED_AND_ACK_RETURNED topic=" + receipt.topic() + " partition=" + receipt.partition()
            + " offset=" + receipt.offset() + " eventId=" + eventId + " result=";
        return streamer.output().lines().filter(line -> line.contains(prefix)).map(line -> line.substring(line.indexOf(prefix)))
            .findFirst().orElse(null);
    }

    /** ACK 로그는 ACK 호출 반환일 뿐이므로 broker 의 group committed offset 이 처리 위치+1 이상인지 따로 기다린다. */
    private static void awaitCommitted(OrderOutboxReceipt receipt) {
        TopicPartition partition = new TopicPartition(receipt.topic(), receipt.partition());
        OrderActivityStreamerChild.await("committed offset " + position(receipt) + "+1", limit(OBSERVE),
            () -> kafka.committedOffsets(group).getOrDefault(partition, -1L) >= receipt.offset() + 1);
    }

    /** D 이후 소비·전송이 모두 끝났는지: 모든 partition 의 끝 offset 까지 commit, PG idle, gate 비어 있음. */
    private static void awaitQuiet() {
        OrderActivityStreamerChild.await("D 이후 미완료 작업 없음", limit(OBSERVE), () -> {
            Map<TopicPartition, Long> end = kafka.endOffsets(topic);
            Map<TopicPartition, Long> committed = kafka.committedOffsets(group);
            return end.entrySet().stream().allMatch(entry -> entry.getValue().equals(committed.getOrDefault(entry.getKey(), 0L)))
                && pg.state().activeRequests() == 0 && pg.state().activeCallbackJobs() == 0;
        });
        assertThat(gate().tryExecute(() -> true).outcome()).isEqualTo(Outcome.EXECUTED);
    }

    private static List<ConsumerRecord<String, String>> recordsOf(Map<TopicPartition, Long> start, Long order) {
        return kafka.readRange(start, kafka.endOffsets(topic)).stream().filter(record -> record.key().equals(order.toString()))
            .sorted((a, b) -> Long.compare(a.offset(), b.offset())).toList();
    }

    // ---------------------------------------------------------------- topic 설정

    private static ConfigEntry isrEntry() throws Exception {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        return topicAdmin.describeConfigs(List.of(resource)).all().get(BROKER_READY.toSeconds(), TimeUnit.SECONDS).get(resource).get(MIN_ISR);
    }

    private static void alterIsr(AlterConfigOp.OpType op, String value) throws Exception {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        Map<ConfigResource, Collection<AlterConfigOp>> request = Map.of(resource, List.of(new AlterConfigOp(new ConfigEntry(MIN_ISR, value), op)));
        topicAdmin.incrementalAlterConfigs(request).all().get(BROKER_READY.toSeconds(), TimeUnit.SECONDS);
    }

    /** topic 수준 설정이었으면 그 값으로, 상속값이었으면 topic override 를 지워 되돌리고 값·출처가 처음과 같은지 확인한다. */
    private static void restoreIsr() throws Exception {
        if (originalIsr.source() == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG) {
            alterIsr(AlterConfigOp.OpType.SET, originalIsr.value());
        } else {
            alterIsr(AlterConfigOp.OpType.DELETE, originalIsr.value());
        }
        OrderActivityStreamerChild.await("min.insync.replicas 복원", BROKER_READY, () -> {
            try {
                ConfigEntry now = isrEntry();
                return now.value().equals(originalIsr.value()) && now.source() == originalIsr.source();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        isrAltered = false;
    }

    private static Map<String, Double> producerMetrics() {
        KafkaTemplate<?, ?> template = (KafkaTemplate<?, ?>) api.getBean(OrderOutboxKafkaConfig.ORDER_OUTBOX_KAFKA_TEMPLATE);
        Map<String, Double> values = new java.util.TreeMap<>();
        template.metrics().forEach((name, metric) -> {
            if (name.group().equals("producer-metrics")
                && Set.of("record-error-total", "record-retry-total", "record-send-total").contains(name.name())) {
                values.merge(name.name(), ((Number) metric.metricValue()).doubleValue(), Double::sum);
            }
        });
        return values;
    }

    // ---------------------------------------------------------------- API·입력

    /** 정상 W7 컨텍스트. W6 초기화기·발행 대역을 쓰지 않고 outboxRecordClock 정의만 같은 이름의 SettableClock 으로 바꾼다. */
    private static ConfigurableApplicationContext bootApi() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        ConfigurableApplicationContext context = new SpringApplicationBuilder(CommerceApiApplication.class).profiles("test")
            .initializers(ctx -> ((BeanDefinitionRegistry) ctx.getBeanFactory()).registerBeanDefinition(
                "orderOutboxDeliveryE2eClockReplacer", new RootBeanDefinition(ClockReplacer.class, ClockReplacer::new)))
            .run("--server.port=" + port, "--management.server.port=0", "--spring.jpa.hibernate.ddl-auto=create",
                "--commerce.pg.base-url=" + pg.baseUrl(),
                "--commerce.pg.callback-url=http://127.0.0.1:" + port + "/api/v1/payments/callback",
                "--spring.kafka.bootstrap-servers=" + kafka.bootstrapServers(),
                "--spring.kafka.admin.properties.bootstrap.servers=" + kafka.bootstrapServers(),
                "--commerce.order-events.topic=" + topic,
                "--commerce.order-events.relay.enabled=false",
                "--commerce.order-events.producer-properties[client.id]=order-outbox-e2e-" + kafka.runId());
        int actual = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        assertAll(
            () -> assertThat(actual).as("callback URL 의 포트와 실제 포트").isEqualTo(port),
            () -> assertThat(context.getEnvironment().getProperty("commerce.pg.callback-url")).contains(":" + actual + "/"),
            () -> assertThat(context.getBean(OrderOutboxClockConfig.OUTBOX_RECORD_CLOCK, Clock.class)).isSameAs(OUTBOX_CLOCK),
            () -> assertThat(context.getBean(OrderConfirmationClockConfig.ORDER_CONFIRMATION_CLOCK, Clock.class)).isNotSameAs(OUTBOX_CLOCK),
            () -> assertThat(context.getBean(OrderOutboxRelayProperties.class).enabled()).as("자동 relay OFF").isFalse(),
            () -> assertThat(context.getBeansOfType(OrderOutboxSender.class)).containsOnlyKeys("kafkaOrderOutboxSender")
        );
        return context;
    }

    private static final class ClockReplacer implements BeanDefinitionRegistryPostProcessor {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            String name = OrderOutboxClockConfig.OUTBOX_RECORD_CLOCK;
            if (!registry.containsBeanDefinition(name)) {
                throw new IllegalStateException("교체할 bean 정의가 없습니다. [bean = " + name + "]");
            }
            registry.removeBeanDefinition(name);
            registry.registerBeanDefinition(name, new RootBeanDefinition(Clock.class, () -> OUTBOX_CLOCK));
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        }
    }

    /** 상품 둘(수량 2·1)의 주문을 실제 API 로 만들고 PG SUCCESS 로 확정한 뒤 PG 요청·콜백 작업이 끝날 때까지 기다린다. */
    private Long confirmNewOrder(String label) {
        Long user = api.getBean(UserFixture.class).createUserWithPoint().getId();
        ProductFixture products = api.getBean(ProductFixture.class);
        Long first = products.createProduct("W7 " + label + " 운동화", 2_000L, 5L).getId();
        Long second = products.createProduct("W7 " + label + " 양말", 500L, 5L).getId();
        ResponseEntity<JsonNode> created = exchange(user, "/api/v1/orders", Map.of("items",
            List.of(Map.of("productId", second, "quantity", 1), Map.of("productId", first, "quantity", 2))));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        Long order = created.getBody().path("data").path("id").asLong();
        USERS.put(order, user);
        pg.enqueuePlan(PgOperation.CREATE, PaymentModel.providerOrderIdOf(order), ResponsePlan.outcome(CreateOutcome.SUCCESS));
        ResponseEntity<JsonNode> confirmed = exchange(user, "/api/v1/orders/" + order + "/confirm", PgCommands.forOrder(order));
        assertThat(confirmed.getStatusCode().value()).isEqualTo(200);
        assertThat(confirmed.getBody().path("data").path("status").asText()).isEqualTo("CONFIRMED");
        OrderActivityStreamerChild.await("PG 요청·콜백 작업 idle", limit(OBSERVE),
            () -> pg.state().activeRequests() == 0 && pg.state().activeCallbackJobs() == 0);
        return order;
    }

    private ResponseEntity<JsonNode> exchange(Long user, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", user.toString());
        int port = ((ServletWebServerApplicationContext) api).getWebServer().getPort();
        return http.exchange("http://127.0.0.1:" + port + path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    // ---------------------------------------------------------------- 새 DB 조회

    private static Map<String, Object> outboxRow(Long order) {
        return fresh.queryForMap("SELECT id, event_id, order_id, event_type, schema_version, message_key, payload, status, attempt_count,"
            + " cycle_attempt_count, next_attempt_at, last_error, published_at, created_at, updated_at, deleted_at"
            + " FROM order_confirmed_outbox WHERE order_id = ?", order);
    }

    private static Map<String, Object> processedRow(Long order) {
        return fresh.queryForMap("SELECT *, CAST(confirmed_at AS CHAR) AS confirmed_at_text FROM processed_order_confirmation"
            + " WHERE order_id = ?", order);
    }

    private static List<Map<String, Object>> activityRows(Long order) {
        return fresh.queryForList("SELECT *, CAST(confirmed_at AS CHAR) AS confirmed_at_text FROM product_purchase_activity"
            + " WHERE order_id = ? ORDER BY product_id", order);
    }

    private static String orderConfirmedAt(Long order) {
        return fresh.queryForObject("SELECT CAST(confirmed_at AS CHAR) FROM orders WHERE id = ?", String.class, order);
    }

    private static List<Long> purchaseRows() {
        return List.of(
            fresh.queryForObject("SELECT COUNT(*) FROM processed_order_confirmation", Long.class),
            fresh.queryForObject("SELECT COUNT(*) FROM product_purchase_activity", Long.class));
    }

    private static List<Long> purchaseRowsOf(Long order) {
        return List.of(
            fresh.queryForObject("SELECT COUNT(*) FROM processed_order_confirmation WHERE order_id = ?", Long.class, order),
            fresh.queryForObject("SELECT COUNT(*) FROM product_purchase_activity WHERE order_id = ?", Long.class, order));
    }

    private static List<List<Long>> storedItems(Long order) {
        return fresh.query("SELECT product_id, SUM(quantity) AS quantity FROM order_item WHERE order_id = ? GROUP BY product_id "
            + "ORDER BY product_id", (row, index) -> List.of(row.getLong("product_id"), row.getLong("quantity")), order);
    }

    /** 주문·결제·PG 거래 연결·예약 이력·재고·사용자 포인트. relay 전달 전후에 같아야 한다. */
    private static Map<String, Object> businessFacts(Long order) {
        return Map.of(
            "order", fresh.queryForMap("SELECT status, paid_amount, total_amount, point_amount, CAST(confirmed_at AS CHAR) AS confirmed_at,"
                + " CAST(updated_at AS CHAR) AS updated_at FROM orders WHERE id = ?", order),
            "payment", fresh.queryForMap("SELECT status, amount, transaction_key, CAST(updated_at AS CHAR) AS updated_at"
                + " FROM payment WHERE order_id = ?", order),
            "reservations", fresh.queryForObject("SELECT COUNT(*) FROM stock_history WHERE order_id = ? AND cause = 'ORDER_RESERVATION'",
                Long.class, order),
            "releases", fresh.queryForObject("SELECT COUNT(*) FROM stock_history WHERE order_id = ? AND cause = 'ORDER_RELEASE'",
                Long.class, order),
            "stock", fresh.query("SELECT p.id, p.stock_quantity FROM product p JOIN order_item i ON i.product_id = p.id "
                + "WHERE i.order_id = ? ORDER BY p.id", (row, index) -> List.of(row.getLong(1), row.getLong(2)), order),
            "point", fresh.queryForObject("SELECT balance FROM point WHERE user_id = ?", Long.class, USERS.get(order)),
            "pgTransaction", pg.state().transactions().stream()
                .filter(transaction -> transaction.toString().contains(PaymentModel.providerOrderIdOf(order))).toList());
    }

    @SuppressWarnings("unchecked")
    private static void assertCommittedConfirmation(Map<String, Object> facts) {
        Map<String, Object> orderRow = (Map<String, Object>) facts.get("order");
        Map<String, Object> paymentRow = (Map<String, Object>) facts.get("payment");
        assertAll(
            () -> assertThat(orderRow.get("status")).isEqualTo("CONFIRMED"),
            () -> assertThat(orderRow.get("confirmed_at")).isNotNull(),
            () -> assertThat(paymentRow.get("status")).isEqualTo("SUCCESS"),
            () -> assertThat(facts.get("reservations")).isEqualTo(2L),
            () -> assertThat(facts.get("releases")).isEqualTo(0L),
            () -> assertThat((List<?>) facts.get("pgTransaction")).as("PG 거래 하나").hasSize(1)
        );
    }

    private static void assertFacts(Map<String, Object> before, Map<String, Object> after) {
        for (String column : List.of("id", "event_id", "order_id", "event_type", "schema_version", "message_key", "payload",
            "created_at", "deleted_at")) {
            assertThat(after.get(column)).as("Outbox 불변 열 " + column).isEqualTo(before.get(column));
        }
    }

    private static Map<String, String> tableCreateTimes() {
        return fresh.query("SELECT TABLE_NAME, CAST(CREATE_TIME AS CHAR) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
                (row, index) -> Map.entry(row.getString(1), String.valueOf(row.getString(2))))
            .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // ---------------------------------------------------------------- 진단·시간

    /** 실패 단계의 원자료: Outbox·구매 행 수·offset·child 생존·안전 로그 줄·PG 상태. payload 원문은 출력하지 않는다. */
    private static void printDiagnostics() {
        List<String> lines = new ArrayList<>();
        safely(lines, "outbox", () -> fresh.query("SELECT id, event_id, status, attempt_count, cycle_attempt_count, next_attempt_at,"
            + " last_error, published_at FROM order_confirmed_outbox ORDER BY id", (row, index) -> row.getString("event_id") + "/"
            + row.getString("status") + "/" + row.getLong("attempt_count") + "/" + row.getInt("cycle_attempt_count") + "/"
            + row.getString("next_attempt_at") + "/" + row.getString("last_error") + "/" + row.getString("published_at")).toString());
        safely(lines, "purchaseRows", () -> purchaseRows().toString());
        safely(lines, "endOffsets", () -> kafka.endOffsets(topic).toString());
        safely(lines, "committed", () -> kafka.committedOffsets(group).toString());
        safely(lines, "childAlive", () -> String.valueOf(streamer != null && streamer.isAlive()));
        safely(lines, "childLines", () -> streamer == null ? "none" : streamer.output().lines()
            .filter(line -> line.contains("ORDER_ACTIVITY_") || line.contains("ERROR")).map(String::trim).toList().toString());
        safely(lines, "pg", () -> pg.state().counts() + " active=" + pg.state().activeRequests() + "/" + pg.state().activeCallbackJobs());
        safely(lines, "isrAltered", () -> String.valueOf(isrAltered));
        safely(lines, "elapsedMs", () -> String.valueOf(elapsedMs(STARTED)));
        System.out.println("[" + LABEL + " DIAGNOSTICS]\n  " + String.join("\n  ", lines));
    }

    private static void safely(List<String> lines, String name, java.util.function.Supplier<String> value) {
        try {
            lines.add(name + "=" + value.get());
        } catch (RuntimeException failure) {
            lines.add(name + "=unavailable(" + failure.getClass().getName() + ")");
        }
    }

    /** 단계 대기 한도: 기존 단계 deadline 과 전체 300초의 남은 시간 중 작은 값. 남은 시간이 없으면 실패한다. */
    private static Duration limit(Duration stage) {
        Duration remaining = TOTAL.minusNanos(System.nanoTime() - STARTED);
        if (remaining.isNegative() || remaining.isZero()) {
            throw new AssertionError("전체 300초 예산을 모두 썼습니다.");
        }
        return remaining.compareTo(stage) < 0 ? remaining : stage;
    }

    private static long elapsedMs(long begin) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
    }

    private static String position(OrderOutboxReceipt receipt) {
        return receipt.topic() + "/" + receipt.partition() + "@" + receipt.offset();
    }

    private static String summary(Map<String, Object> outbox) {
        return outbox.get("status") + " attempt=" + outbox.get("attempt_count") + " cycle=" + outbox.get("cycle_attempt_count")
            + " next=" + outbox.get("next_attempt_at") + " lastError=" + outbox.get("last_error") + " publishedAt=" + outbox.get("published_at");
    }

    private static long id(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }

    private static LocalDateTime wall(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String eventId(Long order) {
        return "order-confirmed:" + order;
    }

    private static RestTemplate client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return new RestTemplate(factory);
    }
}
