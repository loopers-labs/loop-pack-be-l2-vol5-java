package com.loopers.application.order;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

/**
 * 결제가 끝나지 않은 주문을 "결제 시도 상태"와 "고객 주문 상태" 두 기준으로 세면 보이는 주문이 달라지는지 본다.
 * 같은 속도로 주문을 넣는 두 실행을 비교한다. 정상 주문만 넣은 실행과, 정상 흐름에 멈춘 주문 세 건을 한 건씩 섞은 실행이다.
 * <ul>
 *   <li>① 생성 응답을 받은 뒤 저장(TX B) 실패 → 결제 시도 REQUESTED</li>
 *   <li>② PG가 결과를 끝내지 않음 → 결제 시도 PENDING</li>
 *   <li>③ PG 성공을 저장한 뒤 주문 반영(TX C) 실패 → 결제 시도 SUCCESS, 주문은 PAYMENT_PENDING</li>
 * </ul>
 * 두 신호 모두 나이를 결제 시도 생성 시각(결제를 시작한 때)부터 잰다. 차이는 어떤 상태를 집계에 넣었는지에서만 생긴다.
 * 1초마다 실제 /actuator/prometheus 응답에서 UNKNOWN gauge와 주문 기준 gauge(5초 주기 집계)를 함께 읽는다.
 * 가짜 PG의 응답 지연·결과 확정 지연·주문 도착 간격은 실험 조건이다. 정상 주문의 최대 나이는 이 조건에서의 관찰값이고 운영 기준이 아니다.
 */
@Tag("experiment")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@AutoConfigureObservability
class PaymentStuckSignalExperimentTest {

    private static final Duration ARRIVAL_INTERVAL = Duration.ofMillis(100);
    private static final Duration ARRIVAL_PERIOD = Duration.ofSeconds(30);
    private static final Duration INJECT_FROM = Duration.ofSeconds(5);
    private static final Duration DRAIN = Duration.ofSeconds(10);
    private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(1);
    private static final Duration PG_RESPONSE = Duration.ofMillis(100);
    private static final Duration PG_SETTLE = Duration.ofSeconds(2);
    private static final String CARD = "0000-0000-0000-0000";
    private static final List<String> STUCK_KINDS = List.of("REQUESTED", "PENDING", "TX_C");
    private static final Set<String> UNFINISHED_ATTEMPTS = Set.of("REQUESTED", "PENDING", "UNKNOWN");

    private static final FakePaymentServer PG = startPg();

    @DynamicPropertySource
    static void pgUrl(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
    }

    @MockitoSpyBean private OrderPaymentTransactions paymentTransactions;
    @Autowired private OrderFacade orderFacade;
    @Autowired private UserRegistrationService registration;
    @Autowired private BrandJpaRepository brandJpaRepository;
    @Autowired private ProductJpaRepository productJpaRepository;
    @Autowired private PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    @Autowired private OrderJpaRepository orderJpaRepository;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @Autowired private JdbcTemplate jdbc;
    @LocalManagementPort private int managementPort;

    private final Set<Long> createSaveFailures = ConcurrentHashMap.newKeySet();
    private final Set<Long> orderApplyFailures = ConcurrentHashMap.newKeySet();
    private Long userId;
    private Long productId;

    @BeforeEach
    void setUp() {
        PG.reset();
        PG.delayEveryResponse(PG_RESPONSE);
        userId = registration.register().getId();
        Long brandId = brandJpaRepository.save(new BrandModel("나이키", null)).getId();
        productId = productJpaRepository.save(new ProductModel(brandId, "양말", 1_000L, 10_000)).getId();
        // 동시에 호출되는 spy를 실행 중에 다시 stub하지 않도록, 실패시킬 주문 목록만 바꾼다.
        doThrow(new IllegalStateException("생성 응답 저장 실패 주입"))
            .when(paymentTransactions).recordCreateResponse(argThat(createSaveFailures::contains), any());
        doThrow(new IllegalStateException("주문 반영 실패 주입"))
            .when(paymentTransactions).applyRecordedResult(argThat(orderApplyFailures::contains));
    }

    @AfterEach
    void tearDown() {
        createSaveFailures.clear();
        orderApplyFailures.clear();
        databaseCleanUp.truncateAllTables();
    }

    @AfterAll
    static void stopPg() throws InterruptedException {
        PG.close();
    }

    @Test
    void normalOrdersOnly() throws Exception {
        Run run = run("normal", false);

        assertThat(run.finalStates().values()).allMatch(state -> state.equals("SUCCESS/CONFIRMED"));
    }

    @Test
    void normalOrdersWithThreeStuckOrders() throws Exception {
        Run run = run("mixed", true);

        assertThat(run.injected()).containsOnlyKeys(STUCK_KINDS);
        assertThat(run.finalStates().get(run.injected().get("REQUESTED"))).isEqualTo("REQUESTED/PAYMENT_PENDING");
        assertThat(run.finalStates().get(run.injected().get("PENDING"))).isEqualTo("PENDING/PAYMENT_PENDING");
        assertThat(run.finalStates().get(run.injected().get("TX_C"))).isEqualTo("SUCCESS/PAYMENT_PENDING");
        run.finalStates().forEach((orderId, state) -> {
            if (!run.injected().containsValue(orderId)) {
                assertThat(state).as("정상 주문 %d", orderId).isEqualTo("SUCCESS/CONFIRMED");
            }
        });
    }

    private Run run(String name, boolean inject) throws Exception {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        Map<String, Long> injected = new ConcurrentHashMap<>();
        AtomicInteger unexpectedErrors = new AtomicInteger();
        AtomicLong normalAttemptMaxMillis = new AtomicLong();
        AtomicLong normalOrderMaxMillis = new AtomicLong();
        Instant start = Instant.now();
        lines.add("[condition] run=%s arrivalIntervalMs=%d arrivalSeconds=%d injectFromSeconds=%d pgResponseMs=%d pgSettleMs=%d"
            .formatted(name, ARRIVAL_INTERVAL.toMillis(), ARRIVAL_PERIOD.toSeconds(), INJECT_FROM.toSeconds(),
                PG_RESPONSE.toMillis(), PG_SETTLE.toMillis()));

        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledExecutorService timer = Executors.newScheduledThreadPool(2);
        try {
            var arrivals = timer.scheduleAtFixedRate(() -> {
                String kind = inject ? nextStuckKind(start, injected) : null;
                workers.submit(() -> pay(kind, injected, workers, timer, lines, unexpectedErrors));
            }, 0, ARRIVAL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
            var sampling = timer.scheduleAtFixedRate(
                () -> sample(start, injected, lines, normalAttemptMaxMillis, normalOrderMaxMillis),
                SAMPLE_INTERVAL.toMillis(), SAMPLE_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);

            Thread.sleep(ARRIVAL_PERIOD.toMillis());
            arrivals.cancel(false);
            Thread.sleep(DRAIN.toMillis());
            sampling.cancel(false);
        } finally {
            timer.shutdown();
            timer.awaitTermination(10, TimeUnit.SECONDS);
            workers.shutdown();
            workers.awaitTermination(10, TimeUnit.SECONDS);
        }

        Map<Long, String> finalStates = finalStates();
        lines.add("[summary] run=%s orders=%d injected=%s unexpectedErrors=%d normalMaxAttemptAgeMs=%d normalMaxOrderAgeMs=%d"
            .formatted(name, finalStates.size(), injected, unexpectedErrors.get(), normalAttemptMaxMillis.get(),
                normalOrderMaxMillis.get()));
        injected.forEach((kind, orderId) ->
            lines.add("[final] kind=%s orderId=%d attempt/order=%s".formatted(kind, orderId, finalStates.get(orderId))));
        write(name, lines);
        assertThat(unexpectedErrors).as("주입하지 않은 예외").hasValue(0);
        return new Run(injected, finalStates);
    }

    /** 주입 시작 뒤 도착하는 주문 세 건에 ①·②·③을 차례로 붙인다. 도착 타이머 한 스레드에서만 부른다. */
    private String nextStuckKind(Instant start, Map<String, Long> injected) {
        if (Duration.between(start, Instant.now()).compareTo(INJECT_FROM) < 0) {
            return null;
        }
        for (String kind : STUCK_KINDS) {
            if (!injected.containsKey(kind)) {
                injected.put(kind, -1L);
                return kind;
            }
        }
        return null;
    }

    private void pay(String kind, Map<String, Long> injected, ExecutorService workers, ScheduledExecutorService timer,
                     List<String> lines, AtomicInteger unexpectedErrors) {
        Long orderId = orderFacade.create(userId, List.of(new OrderFacade.OrderLine(productId, 1))).id();
        if (kind != null) {
            injected.put(kind, orderId);
            lines.add("[inject] at=%s kind=%s orderId=%d".formatted(Instant.now(), kind, orderId));
        }
        if ("REQUESTED".equals(kind)) {
            createSaveFailures.add(orderId);
        }
        if ("TX_C".equals(kind)) {
            orderApplyFailures.add(orderId);
        }
        OrderPaymentInfo payment;
        try {
            payment = orderFacade.confirm(userId, orderId, new OrderFacade.PaymentCommand("attempt-" + orderId, "TEST", CARD));
        } catch (RuntimeException exception) {
            if (!"REQUESTED".equals(kind)) {
                unexpectedErrors.incrementAndGet();
                lines.add("[error] orderId=%d step=confirm error=%s".formatted(orderId, exception));
            }
            return;
        }
        if ("PENDING".equals(kind)) {
            return;
        }
        timer.schedule(() -> workers.submit(() -> settle(orderId, kind, payment, lines, unexpectedErrors)),
            PG_SETTLE.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** PG가 결과를 확정하고 callback을 보낸 것처럼, 거래를 SUCCESS로 바꾸고 callback 처리를 부른다. */
    private void settle(Long orderId, String kind, OrderPaymentInfo payment, List<String> lines, AtomicInteger unexpectedErrors) {
        PG.changeStatus(payment.transactionKey(), "SUCCESS");
        try {
            orderFacade.handleCallback(payment.providerOrderId());
        } catch (RuntimeException exception) {
            if (!"TX_C".equals(kind)) {
                unexpectedErrors.incrementAndGet();
                lines.add("[error] orderId=%d step=callback error=%s".formatted(orderId, exception));
            }
        }
    }

    /** 같은 순간의 세 신호를 한 줄로 남긴다. 나이는 모두 결제 시도 생성 시각부터 잰다. */
    private void sample(Instant start, Map<String, Long> injected, List<String> lines,
                        AtomicLong normalAttemptMax, AtomicLong normalOrderMax) {
        Instant now = Instant.now();
        // 저장 시각은 엔티티로 읽는다. JDBC Timestamp로 직접 읽으면 저장할 때와 시간대 해석이 달라 9시간 어긋났다.
        Map<Long, String> orderStatuses = orderJpaRepository.findAll().stream()
            .collect(Collectors.toMap(OrderModel::getId, order -> order.getStatus().name()));
        List<Row> rows = paymentAttemptJpaRepository.findAll().stream()
            .map(attempt -> new Row(attempt.getOrderId(), attempt.getStatus().name(), attempt.getCreatedAt().toInstant(),
                orderStatuses.get(attempt.getOrderId())))
            .toList();
        List<Row> attemptSignal = rows.stream().filter(row -> UNFINISHED_ATTEMPTS.contains(row.attemptStatus())).toList();
        List<Row> orderSignal = rows.stream().filter(row -> row.orderStatus().equals("PAYMENT_PENDING")).toList();
        normalAttemptMax.accumulateAndGet(normalMaxAge(attemptSignal, injected, now), Math::max);
        normalOrderMax.accumulateAndGet(normalMaxAge(orderSignal, injected, now), Math::max);
        String metrics = prometheus();
        lines.add(("[signal] t=%ds unknownGauge=%s unfinishedOrderGaugeSeconds=%s attempt={count=%d oldest=%s}"
            + " order={count=%d oldest=%s}").formatted(Duration.between(start, now).toSeconds(),
            metric(metrics, "commerce_payment_attempts_unknown{"),
            metric(metrics, "commerce_payment_orders_unfinished_oldest_seconds{"),
            attemptSignal.size(), oldest(attemptSignal, now), orderSignal.size(), oldest(orderSignal, now)));
    }

    private static long normalMaxAge(List<Row> rows, Map<String, Long> injected, Instant now) {
        return rows.stream().filter(row -> !injected.containsValue(row.orderId()))
            .mapToLong(row -> Duration.between(row.paymentStartedAt(), now).toMillis()).max().orElse(0);
    }

    private static List<String> oldest(List<Row> rows, Instant now) {
        return rows.stream().sorted(Comparator.comparing(Row::paymentStartedAt)).limit(3)
            .map(row -> "%d:%s:%.1fs".formatted(row.orderId(), row.attemptStatus(),
                Duration.between(row.paymentStartedAt(), now).toMillis() / 1000.0))
            .toList();
    }

    private Map<Long, String> finalStates() {
        Map<Long, String> states = new ConcurrentHashMap<>();
        jdbc.query("SELECT a.order_id, a.status, o.status FROM payment_attempt a JOIN orders o ON o.id = a.order_id",
            rs -> {
                states.put(rs.getLong(1), rs.getString(2) + "/" + rs.getString(3));
            });
        return states;
    }

    private String prometheus() {
        try {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus")).build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException exception) {
            return "error:" + exception.getClass().getSimpleName();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    private static String metric(String prometheus, String linePrefix) {
        return prometheus.lines().filter(line -> line.startsWith(linePrefix))
            .map(line -> line.substring(line.lastIndexOf(' ') + 1)).findFirst().orElse("absent");
    }

    private static void write(String name, List<String> lines) throws IOException {
        Path file = Path.of("build", "week10-stuck-signal", name + ".txt");
        Files.createDirectories(file.getParent());
        Files.write(file, lines);
        lines.forEach(System.out::println);
    }

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record Row(Long orderId, String attemptStatus, Instant paymentStartedAt, String orderStatus) {}

    private record Run(Map<String, Long> injected, Map<Long, String> finalStates) {}
}
