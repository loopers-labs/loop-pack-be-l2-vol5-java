package com.loopers.infrastructure.order;

import com.loopers.CommerceApiApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.payment.ControllableProbe;
import com.loopers.application.payment.PaymentTransactionProbe;
import com.loopers.config.OrderOutboxKafkaConfig;
import com.loopers.domain.order.OrderItemCommand;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.fixture.PgCommands;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.fixture.pg.CreateOutcome;
import com.loopers.fixture.pg.PgFixtureServer;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import java.util.concurrent.CountDownLatch;
import com.loopers.application.order.OrderConfirmedRecorder;
import com.loopers.application.order.OrderOutboxExecutionGate.Result;
import com.loopers.application.order.OrderOutboxOperations;
import com.loopers.application.order.OrderOutboxRelay.RowOutcome;
import com.loopers.application.order.OrderOutboxRelay.RowResult;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.infrastructure.order.OrderEventTestCleanup.Step;
import com.loopers.interfaces.scheduler.OrderOutboxShutdownObserver;
import com.loopers.testsupport.LocalKafkaTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * W7 발행 전용 broker 검증(설계 W7.4-4 전환표, 구현 08-2). 기존 W6 직접 발행 4개 시나리오(P4·P6·P7·P8)의 책임 중
 * 다른 테스트가 실제 근거로 보호하지 않는 것, 즉 실제 broker 로 진행 중인 전송이 있을 때의 context 종료·producer close 예산과,
 * graceful 종료 중 진행 HTTP 확정의 완료·commit 보존(구현 08-2a)을 여기서 본다.
 * 나머지 대응은 verification/08-2-w7-publication-regressions.md 의 대응표를 따른다(W6 실행 기록은 그 시점 결과로 보존).
 *
 * <p>소유 order_event_kafka 임시 스키마·실행 소유 topic·client·로컬 PG fixture 를 쓴다. 업무 consumer·streamer 는 연결하지 않는다. 자동 relay 는 끈다.
 * 예산 시나리오는 제품 recorder 로 행을 commit 하고 명시 relay 가 실제 W7 전용 producer 로 보내며, 실패 통제는 그 topic 의 min.insync.replicas=2 뿐이다.
 * graceful 시나리오는 실제 HTTP 확정을 기존 결제 TX seam(PaymentTransactionProbe, 테스트 구현 ControllableProbe)의 commit 직전에서 세운다.
 */
@Timeout(180)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("실제 broker 로 보내는 중인 relay 가 있을 때 context 를 닫으면 결과를 저장하지 않고 PENDING 으로 남기며, 전용 producer close 가 15초 예산을 쓴 뒤 IO thread 가 끝난다.")
class OrderEventKafkaBrokerIntegrationTest {

    private static final String SCHEMA = "order_event_kafka";

    private static LocalKafkaTestSupport kafka;
    private static PgFixtureServer pg;
    private static String server;
    private static String rootPassword;
    /** 이 테스트만 쓰는 이름의 스키마를 만들기 시작했는지. 만들기 전 실패면 정리하지 않는다. */
    private static boolean schemaOwned;
    private static JdbcTemplate fresh;

    private final ExecutorService worker = Executors.newFixedThreadPool(2);

    @BeforeAll
    static void prepareSharedResources() throws Exception {
        kafka = LocalKafkaTestSupport.connect();
        System.out.println("[KAFKA] bootstrap=" + kafka.bootstrapServers() + " runId=" + kafka.runId() + " broker=" + kafka.brokerInfo());
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
        fresh = new JdbcTemplate(new DriverManagerDataSource(server + "/" + SCHEMA, "test", "test"));
        pg = PgFixtureServer.start();
    }

    /** 컨텍스트·worker 는 테스트 안에서 끝낸 뒤 스키마·소유 topic 을 정리한다. 한 단계가 실패해도 다음 단계를 시도한다. */
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

    @AfterEach
    void stopWorker() throws InterruptedException {
        worker.shutdownNow();
        assertThat(worker.awaitTermination(15, TimeUnit.SECONDS)).as("테스트 소유 worker 종료").isTrue();
    }

    @DisplayName("min.insync.replicas=2 topic 으로 보내는 relay 가 기다리는 중 close 하면 INTERRUPTED·PENDING 1회·오류 없음이고, producer close 가 예산을 다 쓰며 늦은 실패도 행을 바꾸지 않는다.")
    @Test
    void closeBudgetExhaustionLeavesInFlightAttemptPending(CapturedOutput output) throws Exception {
        // 통제 실패: 이 실행이 만든 topic 에만 복제 계수(1)보다 큰 min.insync.replicas 를 주어 acks=all 쓰기를 재시도 가능한 오류로 막는다.
        String topic = kafka.createTopic("order-events-isr", Map.of("min.insync.replicas", "2"));
        assertThat(kafka.topicConfig(topic, "min.insync.replicas")).isEqualTo("2");
        String clientId = "order-event-kafka-test-" + kafka.runId() + "-budget";
        OrderConfirmed event = new OrderConfirmed(5151L, ZonedDateTime.of(2026, 11, 5, 3, 0, 0, 0, ZoneOffset.UTC),
            List.of(new OrderConfirmed.Item(31L, 2L)));
        OrderOutboxShutdownObserver observer = new OrderOutboxShutdownObserver();
        ConfigurableApplicationContext context = boot(topic, clientId, observer, false);
        Future<Object[]> running;
        Object adminBootstrap;
        Thread ioThread;
        long closeTook;
        try {
            // 실제 KafkaAdmin bean 의 유효 bootstrap. 기동 인자가 아니라 bean 설정 값을 본다.
            adminBootstrap = context.getBean(KafkaAdmin.class).getConfigurationProperties().get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG);
            OrderConfirmedRecorder recorder = context.getBean(OrderConfirmedRecorder.class);
            new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> recorder.record(event));
            OrderOutboxOperations operations = context.getBean(OrderOutboxOperations.class);
            running = worker.submit(() -> new Object[] {operations.runEvent(event.eventId()), Thread.currentThread().isInterrupted()});
            await("broker 의 NOT_ENOUGH_REPLICAS 재시도와 시도 commit").atMost(Duration.ofSeconds(30))
                .until(() -> output.getAll().contains("NOT_ENOUGH_REPLICAS") && Long.valueOf(1L).equals(row(event).get("attempt_count")));
            ioThread = producerIoThread(clientId);
            long begin = System.nanoTime();
            context.close();
            closeTook = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        } finally {
            context.close();
        }
        Object[] finished = running.get(10, TimeUnit.SECONDS);
        @SuppressWarnings("unchecked")
        Result<RowResult> result = (Result<RowResult>) finished[0];
        ioThread.join(Duration.ofSeconds(5));
        Map<String, Object> after = row(event);
        String all = output.getAll();
        System.out.println("[O10 BUDGET] eventId=" + event.eventId() + " closeTookMs=" + closeTook + " row=" + after
            + " recordsInTopic=" + kafka.readAll(topic).size() + " snapshots=" + observer.snapshots());

        assertAll(
            () -> assertThat(String.valueOf(adminBootstrap)).as("KafkaAdmin 실제 bootstrap: 실행 broker, 개발 기본 kafka:9092 아님")
                .contains(kafka.bootstrapServers()).doesNotContain("kafka:9092"),
            () -> assertThat(result.value()).map(RowResult::outcome).as("종료 관측으로 결과 TX 없이 끝남").contains(RowOutcome.INTERRUPTED),
            () -> assertThat(finished[1]).as("interrupt 플래그 복원").isEqualTo(true),
            () -> assertThat(after).as("결과를 저장하지 않음(늦은 Future 실패 포함)").containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 1L).containsEntry("last_error", null).containsEntry("published_at", null),
            () -> assertThat(all).doesNotContain("ORDER_OUTBOX_SEND_FAILED").doesNotContain("ORDER_OUTBOX_PUBLISHED"),
            () -> assertThat(all.indexOf("Closing the Kafka producer with timeoutMillis = 15000 ms")).isNotNegative(),
            () -> assertThat(closeTook).as("close 가 producer 15초 예산을 소진").isGreaterThanOrEqualTo(14_000L).isLessThan(30_000L),
            () -> assertThat(ioThread.isAlive()).as("producer IO thread 정리").isFalse(),
            () -> assertThat(observer.at("phase-50").gateIdle()).as("진입 정지가 relay finally 해제를 기다림").isTrue(),
            () -> assertThat(observer.at("phase-150").producerFactoryRunning()).as("producer 는 relay·pool 뒤에 닫힘").isTrue(),
            () -> assertThat(observer.at("destroy").producerFactoryRunning()).isFalse(),
            () -> assertThat(OrderOutboxShutdownObserver.relayThreadsAlive()).isZero()
        );
    }

    /**
     * 구현 08-2a: W6 P8 정상 종료의 유효 기대(진행 중 HTTP 요청이 graceful 종료 안에 끝나고 확정이 유지되며 producer 가 그 뒤 닫힘)를 W7 로 옮긴다.
     * W7 의 HTTP 확정은 send 하지 않으므로 "send 성공 뒤 close" 대신 "확정·Outbox commit 이 graceful 종료에서 보존" 을 본다.
     */
    @DisplayName("진행 중인 HTTP 확정이 commit 직전에 멈춘 채 close 가 시작되면 graceful 이 요청을 기다려 200·확정·Outbox 1행 commit 을 남기고, 그 뒤 relay·pool·producer 가 닫힌다.")
    @Test
    void gracefulShutdownCompletesInFlightConfirmation(CapturedOutput output) throws Exception {
        String topic = kafka.createTopic("order-events-graceful");
        String clientId = "order-event-kafka-test-" + kafka.runId() + "-graceful";
        OrderOutboxShutdownObserver observer = new OrderOutboxShutdownObserver();
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pg.reset();
        ConfigurableApplicationContext context = boot(topic, clientId, observer, true);
        Long user;
        Long product;
        Long order;
        Map<String, Object> beforeCommit;
        ResponseEntity<JsonNode> response;
        long closeTook;
        boolean requestPendingAtGraceful;
        try {
            DefaultKafkaProducerFactory<?, ?> factory =
                (DefaultKafkaProducerFactory<?, ?>) context.getBean(OrderOutboxKafkaConfig.ORDER_OUTBOX_PRODUCER_FACTORY);
            assertAll("종료 설정의 실제 적용",
                () -> assertThat(factory.getPhysicalCloseTimeout()).isEqualTo(Duration.ofSeconds(15)),
                () -> assertThat(factory.getPhase()).isEqualTo(Integer.MIN_VALUE),
                () -> assertThat(ReflectionTestUtils.getField(context.getBean("lifecycleProcessor"), "timeoutPerShutdownPhase")).isEqualTo(30_000L),
                () -> assertThat(context.getEnvironment().getProperty("server.shutdown")).isEqualTo("graceful")
            );
            user = context.getBean(UserFixture.class).createUserWithPoint().getId();
            product = context.getBean(ProductFixture.class).createProduct("graceful 확정 상품", 2_000L, 5L).getId();
            order = context.getBean(OrderFacade.class).create(user, List.of(new OrderItemCommand(product, 2L))).id();
            pg.enqueuePlan(PgOperation.CREATE, PaymentModel.providerOrderIdOf(order), ResponsePlan.outcome(CreateOutcome.SUCCESS));
            Long target = order;
            // 기존 결제 TX seam 의 commit 직전(Order·Payment·Outbox flush 뒤)에서 세운다. 장벽은 유한하며 finally 에서 푼다.
            context.getBean(ControllableProbe.class).on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", target, () -> {
                reached.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("graceful 시험 장벽이 풀리지 않았습니다.");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            });
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            Future<ResponseEntity<JsonNode>> confirming = worker.submit(() -> confirm(port, user, target));
            assertThat(reached.await(10, TimeUnit.SECONDS)).as("HTTP 확정이 commit 직전 장벽 도달").isTrue();
            beforeCommit = committedFacts(order);
            Future<Long> closing = worker.submit(() -> {
                long begin = System.nanoTime();
                context.close();
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
            });
            await("graceful 종료 시작").atMost(Duration.ofSeconds(10)).until(() -> output.getAll().contains("Commencing graceful shutdown"));
            requestPendingAtGraceful = !confirming.isDone();
            release.countDown();
            response = confirming.get(30, TimeUnit.SECONDS);
            closeTook = closing.get(60, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            context.close();
        }
        Map<String, Object> afterClose = committedFacts(order);
        String all = output.getAll();
        int commencing = all.indexOf("Commencing graceful shutdown");
        int gracefulDone = all.indexOf("Graceful shutdown complete");
        int relayStopped = all.indexOf("ORDER_OUTBOX_RELAY_STOPPED gateIdle=true");
        int producerStopped = all.indexOf("[SHUTDOWN OBSERVER] Snapshot[point=destroy");
        System.out.println("[GRACEFUL W7] order=" + order + " closeTookMs=" + closeTook + " beforeCommit=" + beforeCommit
            + " afterClose=" + afterClose + " pg=" + pg.state().counts());

        assertAll(
            () -> assertThat(requestPendingAtGraceful).as("graceful 시작 때 HTTP 요청 진행 중").isTrue(),
            () -> assertThat(beforeCommit.get("order")).as("장벽 시점: 다른 연결에는 아직 commit 전")
                .isEqualTo(Map.of("status", "PAYMENT_PENDING")),
            () -> assertThat(beforeCommit.get("outbox")).isEqualTo(0L),
            () -> assertThat(response.getStatusCode().value()).isEqualTo(200),
            () -> assertThat(response.getBody().path("data").path("status").asText()).isEqualTo("CONFIRMED"),
            () -> assertThat(afterClose).as("close 뒤 새 연결: 확정·결제·예약·Outbox 1행 commit")
                .containsEntry("order", Map.of("status", "CONFIRMED")).containsEntry("payment", "SUCCESS")
                .containsEntry("confirmedAt", true).containsEntry("reservations", 1L).containsEntry("releases", 0L)
                .containsEntry("outbox", 1L).containsEntry("outboxRow", List.of("PENDING", 0L)),
            () -> assertThat(pg.state().counts().createReceived()).as("PG create 1회, 재확정·조회 없음").isEqualTo(1L),
            () -> assertThat(pg.state().counts().lookupByKeyReceived() + pg.state().counts().lookupByOrderReceived()).isZero(),
            () -> assertThat(kafka.readAll(topic)).as("자동 relay OFF: 닫힌 context 에서 send 없음").isEmpty(),
            () -> assertThat(commencing).isNotNegative(),
            () -> assertThat(gracefulDone).as("graceful 완료는 시작 뒤").isGreaterThan(commencing),
            () -> assertThat(relayStopped).as("relay 진입 정지는 graceful 완료 뒤").isGreaterThan(gracefulDone),
            () -> assertThat(producerStopped).as("전용 producer factory 정지는 relay 정지 뒤").isGreaterThan(relayStopped),
            () -> assertThat(observer.at("phase-150").producerFactoryRunning()).isTrue(),
            () -> assertThat(observer.at("destroy").producerFactoryRunning()).isFalse(),
            () -> assertThat(closeTook).as("정상 완료는 phase·close 예산을 다 쓰지 않음").isLessThan(15_000L),
            () -> assertThat(producerThreadAlive(clientId)).as("send 가 없어 producer network thread 는 만들어지지 않음(종료 근거 아님)").isFalse()
        );
    }

    // ---------------------------------------------------------------- 도구

    private ResponseEntity<JsonNode> confirm(int port, Long user, Long order) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", user.toString());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return new RestTemplate(factory).exchange("http://127.0.0.1:" + port + "/api/v1/orders/" + order + "/confirm", HttpMethod.POST,
            new HttpEntity<>(PgCommands.forOrder(order), headers), JsonNode.class);
    }

    /** 주문·결제·예약·Outbox 를 새 연결로 읽는다. */
    private static Map<String, Object> committedFacts(Long order) {
        Map<String, Object> facts = new java.util.HashMap<>();
        facts.put("order", Map.of("status", fresh.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order)));
        facts.put("confirmedAt", fresh.queryForObject("SELECT confirmed_at IS NOT NULL FROM orders WHERE id = ?", Boolean.class, order));
        facts.put("payment", fresh.queryForList("SELECT status FROM payment WHERE order_id = ?", String.class, order).stream()
            .findFirst().orElse("none"));
        facts.put("reservations", fresh.queryForObject("SELECT COUNT(*) FROM stock_history WHERE order_id = ? AND cause = 'ORDER_RESERVATION'",
            Long.class, order));
        facts.put("releases", fresh.queryForObject("SELECT COUNT(*) FROM stock_history WHERE order_id = ? AND cause = 'ORDER_RELEASE'",
            Long.class, order));
        facts.put("outbox", fresh.queryForObject("SELECT COUNT(*) FROM order_confirmed_outbox WHERE order_id = ?", Long.class, order));
        facts.put("outboxRow", fresh.query("SELECT status, attempt_count FROM order_confirmed_outbox WHERE order_id = ?",
            (row, index) -> List.<Object>of(row.getString(1), row.getLong(2)), order).stream().findFirst().orElse(List.of()));
        return facts;
    }

    private static boolean producerThreadAlive(String clientId) {
        return Thread.getAllStackTraces().keySet().stream()
            .anyMatch(thread -> thread.isAlive() && thread.getName().startsWith("kafka-producer-network-thread | " + clientId + "-"));
    }

    private static Map<String, Object> row(OrderConfirmed event) {
        return fresh.queryForMap("SELECT status, attempt_count, cycle_attempt_count, last_error, published_at FROM order_confirmed_outbox"
            + " WHERE event_id = ?", event.eventId());
    }

    /** Spring Kafka 는 설정한 client.id 뒤에 "-번호" 를 붙인 producer 를 만든다. */
    private static Thread producerIoThread(String clientId) {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.isAlive() && thread.getName().startsWith("kafka-producer-network-thread | " + clientId + "-"))
            .findFirst().orElseThrow(() -> new AssertionError("전용 producer IO thread 가 없습니다."));
    }

    /**
     * 정상 W7 컨텍스트(자동 relay OFF). bootstrap·topic·전용 producer client.id·PG 주소만 이 실행의 값으로 덮는다.
     * controllableProbe 면 기존 결제 TX seam 의 no-op 정의를 같은 이름의 ControllableProbe 로 바꾼다(제품 probe 추가 아님).
     */
    private static ConfigurableApplicationContext boot(String topic, String clientId, OrderOutboxShutdownObserver observer,
                                                       boolean controllableProbe) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        SpringApplicationBuilder builder = new SpringApplicationBuilder(CommerceApiApplication.class).profiles("test").initializers(observer);
        if (controllableProbe) {
            builder.initializers(context -> ((BeanDefinitionRegistry) context.getBeanFactory()).registerBeanDefinition(
                "gracefulProbeReplacer", new RootBeanDefinition(ProbeReplacer.class, ProbeReplacer::new)));
        }
        return builder.run(
            "--server.port=" + port, "--management.server.port=0", "--spring.jpa.hibernate.ddl-auto=create",
            "--commerce.pg.base-url=" + pg.baseUrl(),
            "--commerce.pg.callback-url=http://127.0.0.1:" + port + "/api/v1/payments/callback",
            "--datasource.mysql-jpa.main.jdbc-url=" + server + "/" + SCHEMA,
            "--spring.kafka.bootstrap-servers=" + kafka.bootstrapServers(),
            "--spring.kafka.admin.properties.bootstrap.servers=" + kafka.bootstrapServers(),
            "--commerce.order-events.topic=" + topic,
            "--commerce.order-events.relay.enabled=false",
            "--commerce.order-events.producer-properties[client.id]=" + clientId);
    }

    /** 결제 TX seam 의 no-op 정의를 같은 이름의 ControllableProbe 로 바꾼다. 다른 정의는 그대로다. */
    private static final class ProbeReplacer implements BeanDefinitionRegistryPostProcessor {

        private static final String NO_OP = "noOpPaymentTransactionProbe";

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            if (!registry.containsBeanDefinition(NO_OP)) {
                throw new IllegalStateException("교체할 결제 TX probe 정의가 없습니다.");
            }
            registry.removeBeanDefinition(NO_OP);
            registry.registerBeanDefinition(NO_OP, new RootBeanDefinition(ControllableProbe.class));
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        }
    }
}
