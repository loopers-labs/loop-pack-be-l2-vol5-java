package com.loopers.application.order;

import com.github.dockerjava.api.DockerClient;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.payment.ResilientPaymentProvider;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.utils.DatabaseCleanUp;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PG가 죽거나 느려지고, DB가 멈추는 장애를 실제 HTTP 가짜 PG와 MySQL 컨테이너로 만든다.
 * 장애 중에는 "지켜야 할 것"(새 결제 0, 결과를 모르면 예약 유지, 대기 한도)을, 장애를 걷어 낸 뒤에는
 * 배치가 PG의 실제 결과로 수렴시키는지를 본다. 무작위 장애는 시드를 고정해 다시 돌릴 수 있게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentChaosTest {

    private static final FakePaymentServer PG = startPg();
    private static final int INITIAL_STOCK = 100;
    private static final Duration OPEN_WAIT = Duration.ofSeconds(10);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
        // DB가 멈췄을 때 요청이 무한정 걸리지 않도록 이 테스트에서만 소켓 대기 한도를 둔다.
        registry.add("datasource.mysql-jpa.main.jdbc-url", PaymentChaosTest::mySqlUrl);
        // 앱 기본 드라이버 timeout(10초·3초)보다 짧게 두어 DB 정지를 빨리 드러낸다. 같은 키로 덮어써야 앱 설정보다 우선한다.
        registry.add("datasource.mysql-jpa.main.data-source-properties.socketTimeout", () -> "3000");
        registry.add("datasource.mysql-jpa.main.data-source-properties.connectTimeout", () -> "2000");
    }

    @Autowired private MockMvc mvc;
    @Autowired private PaymentRecoveryBatch batch;
    @Autowired private OrderFacade orderFacade;
    @Autowired private UserRegistrationService registration;
    @Autowired private ResilientPaymentProvider resilientPaymentProvider;
    @Autowired private BrandJpaRepository brandJpaRepository;
    @Autowired private ProductFacade productFacade;
    @Autowired private ProductJpaRepository productJpaRepository;
    @Autowired private OrderJpaRepository orderJpaRepository;
    @Autowired private PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    @Autowired private TransactionTemplate transaction;
    @Autowired private EntityManager entityManager;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private Long userId;
    private Long productId;

    @BeforeEach
    void setUp() throws IOException {
        PG.reset();
        circuit().reset();
        userId = registration.register().getId();
        Long brandId = brandJpaRepository.save(new BrandModel("나이키", null)).getId();
        // 상품 조회 API도 부르므로 좋아요 카운터까지 만드는 서비스로 등록한다.
        productId = productFacade.create(brandId, "양말", 1_000L, INITIAL_STOCK).id();
    }

    @AfterEach
    void tearDown() throws IOException {
        unpauseDb();
        PG.up();
        databaseCleanUp.truncateAllTables();
    }

    @AfterAll
    static void stopPg() throws InterruptedException {
        PG.close();
    }

    @Test
    void pgDownKeepsReservationsAnswersFastOnceCircuitOpensAndRecoversAfterRestart() throws Exception {
        Long paid = confirmed(3);
        PG.changeStatus("TEST-TX-1", "SUCCESS");

        PG.down();

        // 장애 직후 첫 결제: 앞 결제가 열어 둔 연결을 재사용하다 끊겼다. 요청을 쓴 뒤라 PG에 닿지 않았다고 증명할 수 없어
        // 결과를 모르는 채로 예약을 유지한다.
        Long cutOff = draft(3);
        OrderPaymentInfo cutOffInfo = confirm(cutOff);
        assertThat(cutOffInfo.paymentStatus()).isEqualTo("UNKNOWN");
        assertThat(cutOffInfo.checked()).isFalse();
        // 다음 결제: 새 연결이 거부돼 PG에 요청이 닿지 않았다. 거래가 생길 수 없으므로 바로 실패로 닫고 재고를 돌려준다.
        Long refused = draft(3);
        OrderPaymentInfo refusedInfo = confirm(refused);
        assertThat(refusedInfo.paymentStatus()).isEqualTo("FAILED");
        assertThat(refusedInfo.checked()).isTrue();
        assertThat(PG.createCalls()).isEqualTo(1);

        // 장애 중 결제 확인: 처음엔 GET을 시도하다가, 실패가 쌓여 회로가 열리면 묻지 않고 바로 답한다.
        assertThat(orderFacade.reconcile(userId, paid).checked()).isFalse();
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit().getMetrics().getNumberOfFailedCalls()).isEqualTo(3);
        orderFacade.reconcile(userId, paid);
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        long openedAt = System.nanoTime();
        Timed<OrderPaymentInfo> whileOpen = timed(() -> orderFacade.reconcile(userId, paid));
        assertThat(whileOpen.value().checked()).isFalse();
        assertThat(whileOpen.elapsed()).isLessThan(Duration.ofMillis(200));

        // PG 장애가 다른 API로 번지지 않는다.
        Timed<Integer> productRead = timed(() -> productDetailStatus());
        assertThat(productRead.value()).isEqualTo(200);
        assertThat(productRead.elapsed()).isLessThan(Duration.ofSeconds(1));

        assertOrder(paid, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertOrder(cutOff, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertOrder(refused, OrderStatus.PAYMENT_FAILED, ReservationStatus.RELEASED);
        assertThat(stock()).isEqualTo(INITIAL_STOCK - 6);

        // PG가 다시 뜨고 OPEN 대기가 지나면, 배치가 시험 조회로 회로를 닫고 결과를 반영한다.
        // 결과를 모르는 결제는 31분이 지나고 조회가 0건이어도 예약을 유지해야 한다.
        PG.up();
        sleepUntil(openedAt, OPEN_WAIT.plusMillis(500));
        age(paid, Duration.ofMinutes(11));
        age(cutOff, Duration.ofMinutes(31));

        // 연결이 거부된 결제는 이미 끝나 배치 대상이 아니다. 끊긴 결제의 결과는 여전히 모른다.
        assertThat(batch.run()).isEqualTo(new PaymentRecoveryBatch.Result(1, 1, 0));
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertOrder(paid, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
        assertThat(attemptStatus(cutOff)).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertOrder(cutOff, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertThat(attemptStatus(refused)).isEqualTo(PaymentAttemptStatus.FAILED);
        assertOrder(refused, OrderStatus.PAYMENT_FAILED, ReservationStatus.RELEASED);
        assertThat(stock()).isEqualTo(INITIAL_STOCK - 6);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
    }

    @Test
    void slowPgIsCutByTimeoutsAndBudgetThenAnsweredWithoutWaitingOnceCircuitOpens() throws Exception {
        PG.delayEveryResponse(Duration.ofMillis(1_500));
        Long orderId = draft(3);

        // 생성 응답 대기 1초에서 끊긴다. PG는 늦게나마 거래를 만든다.
        Timed<OrderPaymentInfo> confirm = timed(() -> confirm(orderId));
        assertThat(confirm.value().paymentStatus()).isEqualTo("UNKNOWN");
        assertThat(confirm.value().checked()).isFalse();
        assertThat(confirm.elapsed()).isLessThan(Duration.ofMillis(2_500));
        awaitTransactions(1);

        // 조회는 500ms에서 끊기고, 예산 때문에 2번에서 멈춘다.
        Timed<OrderPaymentInfo> first = timed(() -> orderFacade.reconcile(userId, orderId));
        assertThat(first.value().checked()).isFalse();
        assertThat(first.elapsed()).isLessThan(Duration.ofMillis(2_500));
        assertThat(circuit().getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
        orderFacade.reconcile(userId, orderId);
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        long openedAt = System.nanoTime();
        assertThat(timed(() -> orderFacade.reconcile(userId, orderId)).elapsed()).isLessThan(Duration.ofMillis(200));
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);

        PG.delayEveryResponse(null);
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        sleepUntil(openedAt, OPEN_WAIT.plusMillis(500));
        age(orderId);

        assertThat(batch.run()).isEqualTo(new PaymentRecoveryBatch.Result(1, 0, 0));
        assertOrder(orderId, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
        assertThat(stock()).isEqualTo(INITIAL_STOCK - 3);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @Test
    void dbDownAfterPgAnswersDoesNotOpenPgCircuitAndBatchAppliesTheSameResultOnceAfterRecovery() throws Exception {
        Long orderId = confirmed(3);
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        PG.beforeNextLookupReply(PaymentChaosTest::pauseDb);

        // PG는 SUCCESS라고 답했지만 그 결과를 저장할 DB가 멈췄다. 요청은 무한정 걸리지 않고 오류로 끝난다.
        long started = System.nanoTime();
        assertThatThrownBy(() -> orderFacade.reconcile(userId, orderId)).isInstanceOf(RuntimeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
        // DB 장애는 PG 장애가 아니다.
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit().getMetrics().getNumberOfFailedCalls()).isZero();

        // DB가 멈춘 동안 온 callback에는 성공(2xx)을 주지 않는다. PG가 다시 보낼 수 있다.
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"providerOrderId\":\"%s\",\"transactionKey\":\"TEST-TX-1\",\"status\":\"SUCCESS\"}"
                    .formatted(providerOrderId(orderId))))
            .andExpect(status().is5xxServerError());

        unpauseDb();
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        age(orderId);

        assertThat(batch.run()).isEqualTo(new PaymentRecoveryBatch.Result(1, 0, 0));
        assertOrder(orderId, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
        assertThat(stock()).isEqualTo(INITIAL_STOCK - 3);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @Test
    void seededFaultsWithConcurrentCallbackReconcileAndBatchKeepInvariantsAndConvergeToPgResults() throws Exception {
        Random random = new Random(20251021L);
        int orderCount = 20;
        List<Planned> planned = new ArrayList<>();

        // 1. 생성 장애를 섞어 확정한다: 정상, 응답 유실(거래는 있음), 503(거래 없음).
        for (int i = 0; i < orderCount; i++) {
            int quantity = 1 + random.nextInt(3);
            CreateFault fault = CreateFault.values()[random.nextInt(CreateFault.values().length)];
            Long orderId = draft(quantity);
            if (fault == CreateFault.DROP_RESPONSE) {
                PG.dropNextCreateResponse();
            } else if (fault == CreateFault.SERVER_ERROR) {
                PG.replyNext(503, "");
            }
            confirm(orderId);
            String transactionKey = fault == CreateFault.SERVER_ERROR ? null : "TEST-TX-" + (i + 1);
            String pgResult = transactionKey == null ? null : List.of("SUCCESS", "FAILED", "PENDING").get(random.nextInt(3));
            if (transactionKey != null) {
                PG.changeStatus(transactionKey, pgResult);
            }
            planned.add(new Planned(orderId, quantity, fault, transactionKey, pgResult));
        }
        // 시드가 바뀌어도 세 가지 생성 장애와 세 가지 PG 결과가 모두 섞였는지 확인한다.
        assertThat(planned).extracting(Planned::fault).contains(CreateFault.values());
        assertThat(planned).extracting(Planned::pgResult).contains("SUCCESS", "FAILED", "PENDING");
        planned.forEach(order -> age(order.orderId()));
        for (int i = 0; i < 4; i++) {
            PG.replyNext(503, "");
        }

        // 2. callback·reconcile·배치·같은 키 confirm을 여러 스레드에서 동시에 보낸다.
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Planned target = planned.get(random.nextInt(orderCount));
            switch (random.nextInt(4)) {
                case 0 -> calls.add(() -> orderFacade.handleCallback(providerOrderId(target.orderId())));
                case 1 -> calls.add(() -> orderFacade.reconcile(userId, target.orderId()));
                case 2 -> calls.add(() -> batch.run());
                default -> calls.add(() -> confirm(target.orderId()));
            }
        }
        System.out.printf("chaos plan: faults=%s, pgResults=%s%n",
            planned.stream().collect(groupingBy(Planned::fault, counting())),
            planned.stream().filter(order -> order.pgResult() != null)
                .collect(groupingBy(Planned::pgResult, counting())));
        assertThat(runTogether(calls, 8)).isEmpty();
        assertInvariants(planned);

        // 3. 장애를 걷어 내고 배치를 돌리면 PG의 실제 결과로 수렴한다.
        PG.clearQueuedFaults();
        if (circuit().getState() != CircuitBreaker.State.CLOSED) {
            Thread.sleep(OPEN_WAIT.plusMillis(500));
        }
        batch.run();
        batch.run();

        for (Planned order : planned) {
            if ("SUCCESS".equals(order.pgResult())) {
                assertOrder(order.orderId(), OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
            } else if ("FAILED".equals(order.pgResult())) {
                assertOrder(order.orderId(), OrderStatus.PAYMENT_FAILED, ReservationStatus.RELEASED);
            } else {
                assertOrder(order.orderId(), OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
            }
        }
        assertInvariants(planned);
    }

    /** 장애 중에도, 복구 뒤에도 항상 지켜야 하는 것. */
    private void assertInvariants(List<Planned> planned) {
        // 주문마다 생성 POST는 한 번뿐이고, 거래는 503으로 막힌 주문을 뺀 수만큼만 생긴다.
        assertThat(PG.createCalls()).isEqualTo(planned.size());
        assertThat(PG.transactionCount()).isEqualTo((int) planned.stream().filter(order -> order.transactionKey() != null).count());

        int held = 0;
        for (Planned order : planned) {
            OrderModel stored = orderJpaRepository.findById(order.orderId()).orElseThrow();
            PaymentAttemptStatus attempt = attemptStatus(order.orderId());
            if (stored.getStatus() == OrderStatus.CONFIRMED) {
                assertThat(attempt).isEqualTo(PaymentAttemptStatus.SUCCESS);
                assertThat(stored.getReservationStatus()).isEqualTo(ReservationStatus.CONSUMED);
            }
            if (stored.getStatus() == OrderStatus.PAYMENT_FAILED) {
                assertThat(attempt).isEqualTo(PaymentAttemptStatus.FAILED);
                assertThat(stored.getReservationStatus()).isEqualTo(ReservationStatus.RELEASED);
            }
            // PG가 말한 결과와 반대로 반영된 주문은 없다.
            if (attempt == PaymentAttemptStatus.SUCCESS || attempt == PaymentAttemptStatus.FAILED) {
                assertThat(attempt.name()).isEqualTo(order.pgResult());
            }
            if (stored.getReservationStatus() != ReservationStatus.RELEASED) {
                held += order.quantity();
            }
        }
        // 가용 재고 = 처음 재고 − (확정 수량 + 아직 예약 중인 수량)
        assertThat(stock()).isEqualTo(INITIAL_STOCK - held);
    }

    private List<Throwable> runTogether(List<Callable<Object>> calls, int threads) throws InterruptedException {
        var executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (Callable<Object> call : calls) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        call.call();
                    } catch (Throwable error) {
                        errors.add(error);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                } catch (Exception error) {
                    errors.add(error);
                }
            }
            return List.copyOf(errors);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Long draft(int quantity) {
        return orderFacade.create(userId, List.of(new OrderFacade.OrderLine(productId, quantity))).id();
    }

    private Long confirmed(int quantity) {
        Long orderId = draft(quantity);
        confirm(orderId);
        return orderId;
    }

    private OrderPaymentInfo confirm(Long orderId) {
        return orderFacade.confirm(userId, orderId,
            new OrderFacade.PaymentCommand("attempt-" + orderId, "TEST", "0000-0000-0000-0000"));
    }

    private int productDetailStatus() {
        try {
            return mvc.perform(get("/api/v1/products/" + productId)).andReturn().getResponse().getStatus();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 결제 시도의 생성 시각만 과거로 옮겨 배치의 10분 조건을 만든다. */
    private void age(Long orderId) {
        age(orderId, Duration.ofMinutes(11));
    }

    private void age(Long orderId, Duration age) {
        transaction.executeWithoutResult(status -> entityManager
            .createNativeQuery("update payment_attempt set created_at = :createdAt where order_id = :orderId")
            .setParameter("createdAt", ZonedDateTime.now().minus(age))
            .setParameter("orderId", orderId)
            .executeUpdate());
    }

    private void awaitTransactions(int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (PG.transactionCount() < count && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(PG.transactionCount()).isEqualTo(count);
    }

    private static void sleepUntil(long startedNanos, Duration duration) throws InterruptedException {
        long remaining = duration.toNanos() - (System.nanoTime() - startedNanos);
        if (remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(remaining);
        }
    }

    private static <T> Timed<T> timed(Supplier<T> call) {
        long started = System.nanoTime();
        T value = call.get();
        return new Timed<>(value, Duration.ofNanos(System.nanoTime() - started));
    }

    private CircuitBreaker circuit() {
        return resilientPaymentProvider.lookupCircuitBreaker();
    }

    private void assertOrder(Long orderId, OrderStatus status, ReservationStatus reservation) {
        OrderModel order = orderJpaRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).as("order %d status", orderId).isEqualTo(status);
        assertThat(order.getReservationStatus()).as("order %d reservation", orderId).isEqualTo(reservation);
    }

    private PaymentAttemptStatus attemptStatus(Long orderId) {
        return paymentAttemptJpaRepository.findByOrderId(orderId).map(PaymentAttempt::getStatus).orElseThrow();
    }

    private int stock() {
        return productJpaRepository.findById(productId).orElseThrow().getStockQuantity();
    }

    private static String providerOrderId(Long orderId) {
        return "LP-ORD-%010d".formatted(orderId);
    }

    /** 다른 테스트와 같은 MySQL 컨테이너다. 컨테이너 설정 클래스를 먼저 읽어 접속 주소가 정해지게 한다. */
    private static String mySqlUrl() {
        try {
            Class.forName("com.loopers.testcontainers.MySqlTestContainersConfig");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException(exception);
        }
        return System.getProperty("datasource.mysql-jpa.main.jdbc-url");
    }

    private static void pauseDb() {
        docker().pauseContainerCmd(mySqlContainerId()).exec();
    }

    private static void unpauseDb() {
        String id = mySqlContainerId();
        Boolean paused = docker().inspectContainerCmd(id).exec().getState().getPaused();
        if (Boolean.TRUE.equals(paused)) {
            docker().unpauseContainerCmd(id).exec();
        }
    }

    private static String mySqlContainerId() {
        return docker().listContainersCmd()
            .withLabelFilter(Map.of(DockerClientFactory.TESTCONTAINERS_SESSION_ID_LABEL, DockerClientFactory.SESSION_ID))
            .exec().stream()
            .filter(container -> container.getImage().startsWith("mysql"))
            .findFirst().orElseThrow().getId();
    }

    private static DockerClient docker() {
        return DockerClientFactory.instance().client();
    }

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private enum CreateFault { NONE, DROP_RESPONSE, SERVER_ERROR }

    private record Planned(Long orderId, int quantity, CreateFault fault, String transactionKey, String pgResult) {}

    private record Timed<T>(T value, Duration elapsed) {}
}
