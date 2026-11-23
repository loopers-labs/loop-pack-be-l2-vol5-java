package com.loopers.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.application.payment.PaymentStatusQuery;
import com.loopers.config.PaymentMetricsConfig.PaymentUnknownGauge;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.fixture.SqlCapture;
import com.loopers.infrastructure.payment.PaymentJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * 실제 Testcontainers MySQL·Spring 프록시·애플리케이션 registry·loopback management 포트의 {@code /actuator/prometheus} 로
 * UNKNOWN gauge 를 검증한다(checkout-incident §3, §10 I2).
 * <p>
 * 대역 범위: {@code PaymentRepository} 는 실제 Bean 을 감싼 spy 다. 기본은 실제 메서드를 그대로 호출하고,
 * {@code boundaryFailure*} 사례만 저장소 경계에서 예외·음수를 주입한다(TX 프록시·registry·HTTP 는 실제).
 * 이 대역은 실제 DB 장애·쿼리 취소 검증이 아니다.
 */
@AutoConfigureObservability(tracing = false) // Boot 테스트는 기본으로 metrics export 를 끄므로 실제 Prometheus registry 를 켠다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    SqlCapture.PROPERTY,
    "server.address=127.0.0.1",
    "management.server.port=0",
    "management.server.address=127.0.0.1",
    "management.endpoint.health.show-details=never"
})
@DisplayName("UNKNOWN 결제 gauge 는 실제 DB 의 현재 UNKNOWN 수를 수집마다 읽어 Prometheus 로 노출한다.")
class PaymentMetricsIntegrationTest {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration RESULT_DEADLINE = Duration.ofSeconds(10);
    private static final Duration CLIENT_CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern SAMPLE = Pattern.compile("^commerce_payment_unknown\\{(.*)} (\\S+)$");
    private static final String SENSITIVE = "jdbc:mysql://secret-host:3306/loopers?password=hunter2 card=4111-1111";
    private static final String INSERT_PAYMENT = """
        INSERT INTO payment (order_id, attempt_key, provider, provider_account, provider_order_id, amount, status,
            dispatch_claimed_at, verification_status, created_at, updated_at)
        VALUES (?, ?, 'TEST_PG', '135135', ?, 4000, ?, NOW(6), 'NOT_CHECKED', NOW(6), NOW(6))
        """;

    @LocalManagementPort
    private int managementPort;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private PaymentStatusQuery paymentStatusQuery;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private ApplicationContext context;
    @MockitoSpyBean
    private PaymentRepository paymentRepository;

    private HttpClient client;
    private final List<CompletableFuture<?>> pending = new ArrayList<>();

    @BeforeEach
    void setUp() {
        client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(HTTP_TIMEOUT)
            .build();
        String url = jdbcTemplate.execute((java.sql.Connection connection) -> connection.getMetaData().getURL());
        assertThat(url).as("Testcontainers 전용 DB").isEqualTo(System.getProperty("datasource.mysql-jpa.main.jdbc-url"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment", Long.class))
            .as("배경 결제 행 없음").isZero();
    }

    /** 미완료 요청 취소 → client 유한 종료 → 소유 테스트 DB 정리. 앞 단계가 실패해도 뒤 단계를 수행한다. */
    @AfterEach
    void tearDown() throws Throwable {
        List<Throwable> failures = new ArrayList<>();
        pending.forEach(future -> future.cancel(true));
        try {
            client.shutdownNow();
            if (!client.awaitTermination(CLIENT_CLOSE_TIMEOUT)) {
                failures.add(new IllegalStateException("HttpClient 종료 미확인"));
            }
        } catch (Throwable t) {
            failures.add(t);
        }
        try {
            databaseCleanUp.truncateAllTables();
        } catch (Throwable t) {
            failures.add(t);
        }
        if (!failures.isEmpty()) {
            Throwable first = failures.get(0);
            failures.subList(1, failures.size()).forEach(first::addSuppressed);
            throw first;
        }
    }

    @DisplayName("실제 DB 의 UNKNOWN 만 세고, 정상 0·commit 뒤 변화를 새 수집마다 DB 조회로 반영한다.")
    @Test
    void countsUnknownRowsOnEveryScrape() {
        Sample empty = scrapeSample();
        insertPayment(1L, "UNKNOWN");
        insertPayment(2L, "UNKNOWN");
        insertPayment(3L, "UNKNOWN");
        insertPayment(4L, "PENDING");
        insertPayment(5L, "SUCCESS");
        insertPayment(6L, "FAILED");

        long direct = paymentStatusQuery.countUnknown();
        Sample three;
        Sample two;
        List<String> statements;
        SqlCapture.start();
        try {
            three = scrapeSample();
            jdbcTemplate.update("UPDATE payment SET status = 'PENDING' WHERE order_id = 1");
            two = scrapeSample();
        } finally {
            statements = SqlCapture.stop();
        }

        System.out.println("[UNKNOWN GAUGE] samples: " + empty.line() + " | " + three.line() + " | " + two.line());
        System.out.println("[UNKNOWN GAUGE] SQL per scrape: " + statements);
        assertAll(
            () -> assertThat(empty.value()).as("배경 없는 정상 0").isEqualTo(0.0),
            () -> assertThat(direct).isEqualTo(3),
            () -> assertThat(three.value()).as("UNKNOWN3 · PENDING/SUCCESS/FAILED 제외").isEqualTo(3.0),
            () -> assertThat(two.value()).as("commit 된 변경을 새 수집에서 반영").isEqualTo(2.0),
            () -> assertThat(statements).as("수집마다 COUNT 1회, 캐시 없음").hasSize(2)
                .allSatisfy(sql -> assertThat(sql.toLowerCase()).contains("count(", "from payment", "status")),
            () -> assertThat(three.labels()).isEqualTo("application=\"commerce-api\"")
        );
    }

    @DisplayName("Prometheus 원문에 이름·HELP·TYPE gauge 와 기존 application 태그의 표본 하나만 있다.")
    @Test
    void exposesGaugeInPrometheusFormat() {
        insertPayment(10L, "UNKNOWN");

        HttpResponse<String> response = scrape();
        List<String> lines = response.body().lines()
            .filter(line -> line.contains("commerce_payment_unknown"))
            .toList();
        System.out.println("[UNKNOWN GAUGE] exposition: " + lines);

        assertAll(
            () -> assertThat(response.statusCode()).isEqualTo(200),
            () -> assertThat(lines).containsExactly(
                "# HELP commerce_payment_unknown Current payment rows in UNKNOWN state; excludes PENDING",
                "# TYPE commerce_payment_unknown gauge",
                "commerce_payment_unknown{application=\"commerce-api\"} 1.0")
        );
    }

    @DisplayName("gauge 는 애플리케이션 registry 에 하나이고, 대상은 singleton bean 이며 조회는 Spring 프록시를 거친다.")
    @Test
    void registersOnceWithSingletonTargetAndProxy() throws NoSuchMethodException {
        PaymentUnknownGauge binder = context.getBean(PaymentUnknownGauge.class);
        binder.bindTo(meterRegistry); // 같은 registry 재등록 시도

        QueryHints hints = PaymentJpaRepository.class.getMethod("countByStatus", PaymentStatus.class)
            .getAnnotation(QueryHints.class);
        assertAll(
            () -> assertThat(meterRegistry.find(PaymentMetricsConfig.UNKNOWN_GAUGE).gauges()).hasSize(1),
            () -> assertThat(meterRegistry.get(PaymentMetricsConfig.UNKNOWN_GAUGE).gauge().getId().getTags())
                .containsExactly(Tag.of("application", "commerce-api")),
            () -> assertThat(context.getBean(PaymentUnknownGauge.class)).isSameAs(binder),
            () -> assertThat(AopUtils.isAopProxy(paymentStatusQuery)).isTrue(),
            () -> assertThat(ReflectionTestUtils.getField(binder, "query")).as("gauge 는 프록시 bean 을 참조")
                .isSameAs(paymentStatusQuery),
            () -> assertThat(hints.value()).extracting(hint -> hint.name() + "=" + hint.value())
                .containsExactly("jakarta.persistence.query.timeout=1000")
        );
    }

    @DisplayName("HTTP 수집 스레드의 조회는 읽기 전용·1초 timeout TX 안에서 실제 저장소를 호출한다.")
    @Test
    void scrapeRunsInReadOnlyTimedTransaction() {
        List<String> observed = new ArrayList<>();
        doAnswer(invocation -> {
            EntityManagerHolder holder = TransactionSynchronizationManager.getResourceMap().values().stream()
                .filter(EntityManagerHolder.class::isInstance).map(EntityManagerHolder.class::cast)
                .findFirst().orElse(null);
            observed.add("active=" + TransactionSynchronizationManager.isActualTransactionActive()
                + " readOnly=" + TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                + " timeout=" + (holder != null && holder.hasTimeout())
                + " ttlWithin1s=" + (holder != null && holder.hasTimeout() && holder.getTimeToLiveInMillis() <= 1000)
                + " status=" + invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(paymentRepository).countByStatus(any());
        insertPayment(20L, "UNKNOWN");

        Sample sample = scrapeSample();

        assertAll(
            () -> assertThat(sample.value()).isEqualTo(1.0),
            () -> assertThat(observed).containsExactly(
                "active=true readOnly=true timeout=true ttlWithin1s=true status=UNKNOWN")
        );
    }

    @DisplayName("[대역] 저장소 경계 RuntimeException 이면 HTTP 200 에 NaN 표본, 정적 코드·예외 클래스만 기록하고 민감 표식은 새 로그에 없다.")
    @Test
    void boundaryFailureExposesNaNWithoutSensitiveLog() {
        insertPayment(30L, "UNKNOWN");
        doThrow(new QueryTimeoutException(SENSITIVE)).when(paymentRepository).countByStatus(any());

        CapturedScrape failed = scrapeCapturingLogs();
        org.mockito.Mockito.reset(paymentRepository);
        Sample recovered = scrapeSample();

        List<ILoggingEvent> gaugeEvents = failed.events().stream()
            .filter(event -> event.getLoggerName().equals(PaymentUnknownGauge.class.getName()))
            .toList();
        assertAll(
            () -> assertThat(failed.sample().value()).as("실패는 0 이 아닌 NaN").isNaN(),
            () -> assertThat(gaugeEvents).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("PAYMENT_UNKNOWN_QUERY_FAILED exception=org.springframework.dao.QueryTimeoutException"),
            () -> assertThat(gaugeEvents).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull()),
            () -> assertThat(failed.events()).allSatisfy(event -> assertThat(render(event))
                .doesNotContain("secret-host", "hunter2", "4111-1111")),
            () -> assertThat(recovered.value()).as("직전 NaN 을 보관하지 않고 다시 조회").isEqualTo(1.0)
        );
    }

    @DisplayName("[대역] 저장소 경계가 음수를 주면 정상 값으로 내보내지 않고 NaN 과 내부 오류 클래스를 기록한다.")
    @Test
    void boundaryFailureNegativeCountExposesNaN() {
        doReturn(-1L).when(paymentRepository).countByStatus(any());

        CapturedScrape failed = scrapeCapturingLogs();

        assertAll(
            () -> assertThat(failed.sample().value()).isNaN(),
            () -> assertThat(failed.events()).filteredOn(event -> event.getLoggerName().equals(PaymentUnknownGauge.class.getName()))
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("PAYMENT_UNKNOWN_QUERY_FAILED exception=java.lang.IllegalStateException")
        );
    }

    private void insertPayment(long orderId, String status) {
        jdbcTemplate.update(INSERT_PAYMENT, orderId, UUID.randomUUID().toString(), PaymentModel.providerOrderIdOf(orderId),
            status);
    }

    /** 이 테스트가 root logger 에 붙인 appender 만 떼어 낸다. 수집 구간의 모든 새 로그를 본다. */
    private CapturedScrape scrapeCapturingLogs() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        Sample sample;
        try {
            sample = scrapeSample();
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
        return new CapturedScrape(sample, List.copyOf(appender.list));
    }

    private static String render(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
            text.append(' ').append(proxy.getClassName()).append(": ").append(proxy.getMessage());
        }
        return text.toString();
    }

    /** 표본이 없거나 여러 개면 0 으로 채우지 않고 실패한다. */
    private Sample scrapeSample() {
        HttpResponse<String> response = scrape();
        assertThat(response.statusCode()).isEqualTo(200);
        List<String> samples = response.body().lines().filter(line -> line.startsWith("commerce_payment_unknown")).toList();
        assertThat(samples).as("commerce_payment_unknown 표본 하나").hasSize(1);
        Matcher matcher = SAMPLE.matcher(samples.get(0));
        assertThat(matcher.matches()).as("표본 형식: " + samples.get(0)).isTrue();
        return new Sample(samples.get(0), matcher.group(1), Double.parseDouble(matcher.group(2)));
    }

    private HttpResponse<String> scrape() {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
            .timeout(HTTP_TIMEOUT)
            .GET()
            .build();
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        pending.add(future);
        try {
            return future.get(RESULT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("수집 응답 대기 중단", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("수집 요청 실패", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("수집 응답 기한 초과", e);
        } finally {
            future.cancel(true);
        }
    }

    private record Sample(String line, String labels, double value) {
    }

    private record CapturedScrape(Sample sample, List<ILoggingEvent> events) {
    }
}
