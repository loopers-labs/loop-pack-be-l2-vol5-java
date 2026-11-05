package com.loopers.interfaces.scheduler;

import com.loopers.CommerceApiApplication;
import com.loopers.application.order.OrderConfirmedRecorder;
import com.loopers.application.order.OrderOutboxExecutionGate;
import com.loopers.application.order.OrderOutboxExecutionGate.Outcome;
import com.loopers.application.order.OrderOutboxExecutionGate.Result;
import com.loopers.application.order.OrderOutboxOperations;
import com.loopers.application.order.OrderOutboxRelay.RowOutcome;
import com.loopers.application.order.OrderOutboxRelay.RowResult;
import com.loopers.config.OrderOutboxRelayConfig;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderOutboxAttempt;
import com.loopers.domain.order.OrderOutboxReceipt;
import com.loopers.domain.order.OrderOutboxSender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
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
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * O10 자동 회차·context 종료(설계 W7.3-1·W7.3-5). 실제 CommerceApiApplication 을 소유 임시 스키마로 띄워 실제 context.close 를 관찰한다.
 *
 * <p>통제한 것은 sender 하나(즉시 성공 또는 끝나지 않는 Future)와 짧은 lifecycle phase 대기(이 테스트만)다. relay·gate·TX·전용 pool·
 * 진입 lifecycle·전용 producer factory 는 실제 bean 이다. 종료 순서는 {@link OrderOutboxShutdownObserver} 가 각 phase 에서 실제 상태로 기록한다.
 * 실제 broker·producer 의 종료는 OrderOutboxKafkaWireIntegrationTest 가 본다.
 */
@Timeout(180)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("relay 자동 회차는 enabled 일 때만 직렬로 돌고, 자동 실행 여부와 무관하게 context 종료가 gate 를 닫고 진행 작업을 정리한 뒤 pool·producer 를 멈춘다.")
class OrderOutboxRelaySchedulerLifecycleTest {

    private static final String SCHEMA = "order_outbox_lifecycle";
    private static String server;
    private static String rootPassword;
    private static JdbcTemplate fresh;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @BeforeAll
    static void prepareSchema() throws Exception {
        Class.forName("com.loopers.testcontainers.MySqlTestContainersConfig");
        String url = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        rootPassword = System.getProperty("datasource.mysql-jpa.main.password");
        server = url.substring(0, url.lastIndexOf('/'));
        try (var root = new SingleConnectionDataSource(server, "root", rootPassword, true)) {
            JdbcTemplate admin = new JdbcTemplate(root);
            admin.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            admin.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            admin.execute("GRANT ALL ON " + SCHEMA + ".* TO 'test'@'%'");
        }
        fresh = new JdbcTemplate(new DriverManagerDataSource(server + "/" + SCHEMA, "test", "test"));
    }

    /**
     * 테스트마다 소유한 worker 를 interrupt 로 멈추고 실제 종료를 유한 대기로 확인한다(구현 08-1a). 각 테스트의 finally 가 장벽을 먼저 풀므로
     * interrupt 를 무시하는 통제 작업도 여기서 끝난다. 종료되지 않으면 실패로 드러낸다. context 정리 실패와 별개로 항상 실행된다.
     */
    @AfterEach
    void stopWorker(TestInfo test) throws InterruptedException {
        worker.shutdownNow();
        boolean terminated = worker.awaitTermination(15, TimeUnit.SECONDS);
        System.out.println("[LIFECYCLE WORKER] test=" + test.getTestMethod().map(method -> method.getName()).orElse("?")
            + " terminated=" + terminated);
        assertThat(terminated).as("테스트 소유 worker 종료").isTrue();
    }

    @AfterAll
    static void dropSchema() {
        try (var root = new SingleConnectionDataSource(server, "root", rootPassword, true)) {
            new JdbcTemplate(root).execute("DROP DATABASE IF EXISTS " + SCHEMA);
        }
    }

    @DisplayName("OFF: 자동 회차는 없고 조립은 그대로이며, 종료는 진행 중인 수동 실행을 interrupt 해 결과 없이 끝내고 이후 진입을 STOPPING 으로 막는다.")
    @Test
    void offContextStopsManualWork(CapturedOutput output) throws Exception {
        ControlledSender sender = new ControlledSender();
        OrderOutboxShutdownObserver observer = new OrderOutboxShutdownObserver();
        ConfigurableApplicationContext context = boot(false, "3s", sender, observer);
        boolean closed = false;
        try {
            ThreadPoolTaskScheduler pool = (ThreadPoolTaskScheduler) context.getBean(OrderOutboxRelayConfig.RELAY_TASK_SCHEDULER);
            OrderOutboxRelayScheduler scheduler = context.getBean(OrderOutboxRelayScheduler.class);
            OrderOutboxExecutionGate gate = context.getBean(OrderOutboxExecutionGate.class);
            ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) pool.getScheduledExecutor();
            assertAll("조립",
                () -> assertThat(scheduler.isRunning()).isTrue(),
                () -> assertThat(scheduler.getPhase()).isZero(),
                () -> assertThat(ReflectionTestUtils.getField(scheduler, "gate")).isSameAs(gate),
                () -> assertThat(ReflectionTestUtils.getField(scheduler, "taskScheduler")).isSameAs(pool),
                () -> assertThat(pool.getPhase()).isEqualTo(-100),
                () -> assertThat(executor.getCorePoolSize()).as("전용 단일 thread").isEqualTo(1),
                () -> assertThat(ReflectionTestUtils.getField(pool, "acceptTasksAfterContextClose")).isEqualTo(false),
                () -> assertThat(ReflectionTestUtils.getField(pool, "waitForTasksToCompleteOnShutdown")).isEqualTo(false),
                () -> assertThat(executor.getContinueExistingPeriodicTasksAfterShutdownPolicy()).isFalse(),
                () -> assertThat(executor.getExecuteExistingDelayedTasksAfterShutdownPolicy()).isFalse(),
                () -> assertThat(context.getBeanFactory().getBeanDefinition(OrderOutboxRelayConfig.RELAY_TASK_SCHEDULER).isAutowireCandidate())
                    .as("공통 실행기 자동 구성을 바꾸지 않도록 주입 후보가 아님").isFalse(),
                () -> assertThat(context.containsBean("applicationTaskExecutor")).as("Boot 공통 실행기 유지").isTrue()
            );
            long order = 7001L;
            record(context, order);
            await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(2)).until(() -> sender.calls.isEmpty());

            sender.block.set(true);
            Future<Object[]> manual = worker.submit(() -> new Object[] {
                context.getBean(OrderOutboxOperations.class).runEvent("order-confirmed:" + order), Thread.currentThread().isInterrupted()});
            sender.awaitCalls(1);
            context.close();
            closed = true;
            Object[] finished = manual.get(10, TimeUnit.SECONDS);
            @SuppressWarnings("unchecked")
            Result<RowResult> result = (Result<RowResult>) finished[0];

            assertAll(
                () -> assertThat(sender.calls).as("자동 회차 없음, 수동 send 1회").hasSize(1),
                () -> assertThat(sender.calls.get(0)).as("수동 실행은 호출 thread").doesNotStartWith("order-outbox-relay-"),
                () -> assertThat(result.value()).map(RowResult::outcome).contains(RowOutcome.INTERRUPTED),
                () -> assertThat(finished[1]).as("interrupt 플래그 복원").isEqualTo(true),
                () -> assertThat(observer.at("phase1").gateStopping()).as("진입 제어 전에는 gate 열림").isFalse(),
                () -> assertThat(observer.at("phase-50").gateStopping()).isTrue(),
                () -> assertThat(observer.at("phase-50").gateIdle()).as("진입 제어 정지가 finally 해제까지 기다림").isTrue(),
                () -> assertThat(gate.tryExecute(() -> 1).outcome()).as("종료 뒤 진입").isEqualTo(Outcome.STOPPING),
                () -> assertThat(outbox(order)).containsEntry("status", "PENDING").containsEntry("attempt_count", 1L)
                    .containsEntry("last_error", null).containsEntry("published_at", null),
                () -> assertThat(output.getAll()).contains("ORDER_OUTBOX_RELAY_SCHEDULER_STARTED enabled=false")
                    .contains("ORDER_OUTBOX_RELAY_STOPPED gateIdle=true")
            );
        } finally {
            if (!closed) {
                context.close();
            }
        }
    }

    @DisplayName("ON: 회차는 전용 단일 thread 에서 fixedDelay 로 직렬 실행되고, 수동 점유 중 회차는 BUSY·회차 점유 중 수동 진입은 BUSY 이며, 종료는 early shutdown → 진입 정지 → pool → producer 순서다.")
    @Test
    void onContextRunsSeriallyAndStopsInOrder(CapturedOutput output) throws Exception {
        ControlledSender sender = new ControlledSender();
        OrderOutboxShutdownObserver observer = new OrderOutboxShutdownObserver();
        ConfigurableApplicationContext context = boot(true, "3s", sender, observer);
        boolean closed = false;
        CountDownLatch release = new CountDownLatch(1);
        try {
            OrderOutboxExecutionGate gate = context.getBean(OrderOutboxExecutionGate.class);
            for (long order = 8001L; order <= 8003L; order++) {
                record(context, order);
            }
            await().atMost(Duration.ofSeconds(20)).until(() -> published(8001L, 8003L) == 3);
            List<String> serialThreads = List.copyOf(sender.calls);
            List<Long> gapsMs = sender.gapsMs();

            // 수동이 gate 를 잡은 동안 자동 회차는 BUSY. 실패해도 장벽은 finally 에서 푼다.
            Future<Result<Void>> holder = worker.submit(() -> gate.tryRun(() -> awaitQuietly(release)));
            await().atMost(Duration.ofSeconds(10)).until(() -> output.getAll().contains("ORDER_OUTBOX_SCHEDULED_ROUND outcome=BUSY"));
            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.EXECUTED);

            // 자동 회차가 send 를 기다리는 동안 수동 run·resume·recover 는 DB 변경 없이 BUSY.
            sender.block.set(true);
            long blockedOrder = 8004L;
            record(context, blockedOrder);
            sender.awaitCalls(serialThreads.size() + 1);
            OrderOutboxOperations operations = context.getBean(OrderOutboxOperations.class);
            Map<String, Object> blockedRow = outbox(blockedOrder);
            List<Outcome> manual = List.of(operations.runEvent("order-confirmed:" + blockedOrder).outcome(),
                operations.resumeBlocked("order-confirmed:" + blockedOrder).outcome(),
                operations.recoverOrders(List.of(blockedOrder)).outcome());
            Map<String, Object> afterManual = outbox(blockedOrder);
            int callsBeforeClose = sender.calls.size();

            context.close();
            closed = true;

            assertAll(
                () -> assertThat(serialThreads).as("전용 단일 thread").containsOnly("order-outbox-relay-1").hasSize(3),
                () -> assertThat(gapsMs).as("앞 회차가 끝난 뒤 fixedDelay(100ms) 이상 지나 다음 send").allSatisfy(gap -> assertThat(gap)
                    .isGreaterThanOrEqualTo(100L)),
                () -> assertThat(manual).containsOnly(Outcome.BUSY),
                () -> assertThat(afterManual).as("BUSY 는 DB 를 바꾸지 않음").isEqualTo(blockedRow),
                () -> assertThat(blockedRow).containsEntry("status", "PENDING").containsEntry("attempt_count", 1L),
                // 1) ContextClosedEvent 의 early shutdown 은 lifecycle 정지보다 먼저다. 진행 회차는 아직 gate 를 쥐고 있다.
                () -> assertThat(observer.at("phase1")).satisfies(snapshot -> {
                    assertThat(snapshot.poolShutdown()).as("early shutdown 시작").isTrue();
                    assertThat(snapshot.gateStopping()).isFalse();
                    assertThat(snapshot.gateIdle()).as("진행 회차가 gate 를 점유").isFalse();
                    assertThat(snapshot.poolLifecycleRunning()).isTrue();
                }),
                // 2) phase 0 진입 제어가 gate 를 닫고 진행 회차의 finally 해제를 기다렸다. early shutdown 뒤 유일한 작업이 끝나
                //    executor 가 이미 종료됐다(실제 관찰). 그래서 phase -100 에서 pool 은 running 이 아니며 그 stop 은 호출되지 않는다.
                () -> assertThat(observer.at("phase-50")).satisfies(snapshot -> {
                    assertThat(snapshot.gateStopping()).isTrue();
                    assertThat(snapshot.gateIdle()).isTrue();
                    assertThat(snapshot.poolTerminated()).as("진행 회차 종료·executor 종료").isTrue();
                    assertThat(snapshot.poolLifecycleRunning()).isFalse();
                    assertThat(snapshot.relayThreadsAlive()).isZero();
                    assertThat(snapshot.producerFactoryRunning()).isTrue();
                }),
                // 3) pool(-100) 다음 순서에서도 producer factory(MIN_VALUE)는 아직 실행 중이다.
                () -> assertThat(observer.at("phase-150")).satisfies(snapshot -> {
                    assertThat(snapshot.poolLifecycleRunning()).isFalse();
                    assertThat(snapshot.producerFactoryRunning()).isTrue();
                }),
                // 4) bean 파괴 단계에서는 producer factory 도 정지했다.
                () -> assertThat(observer.at("destroy").producerFactoryRunning()).isFalse(),
                () -> assertThat(output.getAll()).contains("ORDER_OUTBOX_SEND_INTERRUPTED").contains("ORDER_OUTBOX_RELAY_STOPPED gateIdle=true"),
                () -> assertThat(outbox(blockedOrder)).as("종료 중 결과 TX 없음").containsEntry("status", "PENDING")
                    .containsEntry("attempt_count", 1L).containsEntry("last_error", null),
                () -> assertThat(OrderOutboxShutdownObserver.relayThreadsAlive()).as("relay thread 종료").isZero(),
                () -> assertThat(sender.calls).as("종료 중 새 회차 send 없음").hasSize(callsBeforeClose)
            );
        } finally {
            release.countDown();
            if (!closed) {
                context.close();
            }
        }
    }

    @DisplayName("진행 작업이 phase 대기 안에 끝나지 않으면 미완료로 기록하고 완료로 보고하지 않으며, 다시 stop 해도 안전하다.")
    @Test
    void reportsIncompleteStop(CapturedOutput output) throws Exception {
        OrderOutboxShutdownObserver observer = new OrderOutboxShutdownObserver();
        ConfigurableApplicationContext context = boot(false, "1s", new ControlledSender(), observer);
        OrderOutboxExecutionGate gate = context.getBean(OrderOutboxExecutionGate.class);
        OrderOutboxRelayScheduler scheduler = context.getBean(OrderOutboxRelayScheduler.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        try {
            // interrupt 를 받아도 장벽이 풀릴 때까지 끝나지 않는 통제 작업.
            worker.submit(() -> gate.tryRun(() -> {
                held.countDown();
                while (true) {
                    try {
                        if (release.await(10, TimeUnit.SECONDS)) {
                            return;
                        }
                    } catch (InterruptedException ignored) {
                        interrupted.set(true);
                    }
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            context.close();
            boolean idleAfterClose = gate.awaitIdle(Duration.ZERO);
            release.countDown();
            boolean idleAfterRelease = gate.awaitIdle(Duration.ofSeconds(5));
            scheduler.stop();

            assertAll(
                () -> assertThat(interrupted).as("stop 이 interrupt 를 전달").isTrue(),
                () -> assertThat(observer.at("phase-50").gateIdle()).as("대기 한도 안에 해제되지 않음").isFalse(),
                () -> assertThat(output.getAll()).contains("ORDER_OUTBOX_RELAY_STOP_INCOMPLETE gateIdle=false waitedMs=1000"),
                () -> assertThat(idleAfterClose).isFalse(),
                () -> assertThat(idleAfterRelease).isTrue(),
                () -> assertThat(output.getAll()).as("두 번째 stop 은 오류 없이 idle 확인").contains("ORDER_OUTBOX_RELAY_STOPPED gateIdle=true")
            );
        } finally {
            release.countDown();
            if (context.isActive()) {
                context.close();
            }
        }
    }

    // ---------------------------------------------------------------- 도구

    private static ConfigurableApplicationContext boot(boolean enabled, String phaseTimeout, ControlledSender sender,
                                                       OrderOutboxShutdownObserver observer) {
        return new SpringApplicationBuilder(CommerceApiApplication.class).profiles("test")
            .initializers(observer, new SenderReplacer(sender))
            .run("--server.port=0", "--management.server.port=0", "--spring.jpa.hibernate.ddl-auto=create",
                "--datasource.mysql-jpa.main.jdbc-url=" + server + "/" + SCHEMA,
                "--commerce.order-events.relay.enabled=" + enabled,
                "--commerce.order-events.relay.fixed-delay=100ms",
                "--commerce.order-events.relay.batch-size=1",
                "--spring.lifecycle.timeout-per-shutdown-phase=" + phaseTimeout);
    }

    private static void record(ConfigurableApplicationContext context, long orderId) {
        OrderConfirmed event = new OrderConfirmed(orderId, ZonedDateTime.of(2026, 11, 5, 1, 0, 0, 0, ZoneOffset.UTC),
            List.of(new OrderConfirmed.Item(11L, 1L)));
        OrderConfirmedRecorder recorder = context.getBean(OrderConfirmedRecorder.class);
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> recorder.record(event));
    }

    private static Map<String, Object> outbox(long orderId) {
        return fresh.queryForMap("SELECT status, attempt_count, cycle_attempt_count, next_attempt_at, last_error, published_at,"
            + " updated_at FROM order_confirmed_outbox WHERE order_id = ?", orderId);
    }

    private static long published(long from, long to) {
        Long count = fresh.queryForObject("SELECT COUNT(*) FROM order_confirmed_outbox WHERE status = 'PUBLISHED' AND order_id BETWEEN ? AND ?",
            Long.class, from, to);
        return count == null ? 0L : count;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 실제 sender 정의만 같은 이름의 통제 대역으로 바꾼다. 다른 bean 은 그대로다. */
    private static final class SenderReplacer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        private final ControlledSender sender;

        private SenderReplacer(ControlledSender sender) {
            this.sender = sender;
        }

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            ((BeanDefinitionRegistry) context.getBeanFactory()).registerBeanDefinition("lifecycleTestSenderReplacer",
                new RootBeanDefinition(Replace.class, () -> new Replace(sender)));
        }
    }

    private static final class Replace implements BeanDefinitionRegistryPostProcessor {

        private final ControlledSender sender;

        private Replace(ControlledSender sender) {
            this.sender = sender;
        }

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            if (!registry.containsBeanDefinition("kafkaOrderOutboxSender")) {
                throw new IllegalStateException("교체할 sender 정의가 없습니다.");
            }
            registry.removeBeanDefinition("kafkaOrderOutboxSender");
            registry.registerBeanDefinition("kafkaOrderOutboxSender", new RootBeanDefinition(OrderOutboxSender.class, () -> sender));
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        }
    }

    /** 호출 thread 이름·시각을 기록한다. block 이면 끝나지 않는 Future, 아니면 즉시 성공한다. 실제 broker 가 아니다. */
    static final class ControlledSender implements OrderOutboxSender {

        final List<String> calls = new CopyOnWriteArrayList<>();
        final AtomicBoolean block = new AtomicBoolean();
        private final List<Long> times = new CopyOnWriteArrayList<>();
        private final Semaphore sent = new Semaphore(0);

        void awaitCalls(int count) throws InterruptedException {
            assertThat(sent.tryAcquire(count, 20, TimeUnit.SECONDS)).as("send 호출 " + count + "회").isTrue();
        }

        List<Long> gapsMs() {
            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < times.size(); i++) {
                gaps.add(TimeUnit.NANOSECONDS.toMillis(times.get(i) - times.get(i - 1)));
            }
            return gaps;
        }

        @Override
        public CompletionStage<OrderOutboxReceipt> send(OrderOutboxAttempt attempt) {
            calls.add(Thread.currentThread().getName());
            if (!block.get()) {
                times.add(System.nanoTime());
            }
            sent.release();
            return block.get() ? new CompletableFuture<>()
                : CompletableFuture.completedFuture(new OrderOutboxReceipt("order-events-test", 0, calls.size()));
        }
    }
}
