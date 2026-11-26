package com.loopers.application.payment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.application.order.event.OrderConfirmedOutboxWriter;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.testpg.TestPgControl;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

// 결제 생성과 PG 조회의 경계 로그가 주문 단위로 이어지는 key-value를 남기는지 실제 경로로 확인한다.
@SpringBootTest
class PaymentBoundaryLogIntegrationTest {
    private static final long PRICE = 3_500L;
    private static final int QUANTITY = 2;
    private static final String CARD_NO = "0000-0000-0000-0000";

    @Autowired private PaymentRequestFacade paymentRequestFacade;
    @Autowired private PaymentReconcileFacade paymentReconcileFacade;
    @Autowired private PaymentCallbackFacade paymentCallbackFacade;
    @Autowired private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private BrandRepository brandRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @MockitoBean private ProviderOrderSuffixGenerator suffixGenerator;
    @MockitoSpyBean private OrderConfirmedOutboxWriter outboxWriter;
    @Value("${payment.pg.read-timeout}") private Duration responseTimeout;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger paymentLogger;
    private String suffix;

    @BeforeEach
    void captureLogs() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        when(suffixGenerator.next()).thenAnswer(invocation -> suffix);
        paymentLogger = (Logger) LoggerFactory.getLogger("com.loopers.application.payment");
        appender.start();
        paymentLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        paymentLogger.detachAppender(appender);
        appender.stop();
        try {
            TestPgControl.lookupFailures(0);
        } finally {
            databaseCleanUp.truncateAllTables();
        }
    }

    @DisplayName("생성 응답을 잃으면 생성 관찰을 WARN으로 남기고, 응답 timeout 이상 걸린 시간과 adapter가 남긴 이유를 담는다.")
    @Test
    void logsLostCreateResponse() {
        Fixture fixture = fixture();
        TestPgControl.scenario(providerOrderId(fixture), "{\"scenario\":\"DROP_RESPONSE\"}");

        PaymentStatusInfo status = request(fixture);

        assertThat(status.attemptStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        ILoggingEvent created = single("payment.create.observed");
        assertThat(created.getLevel()).isEqualTo(Level.WARN);
        assertThat(value(created, "attemptId")).isEqualTo(String.valueOf(attempt(fixture).getId()));
        assertThat(value(created, "orderId")).isEqualTo(String.valueOf(fixture.orderId()));
        assertThat(value(created, "providerOrderId")).isEqualTo(providerOrderId(fixture));
        assertThat(value(created, "outcome")).isEqualTo("UNKNOWN");
        assertThat(value(created, "detail")).isNotBlank();
        assertThat(Long.parseLong(value(created, "elapsedMs"))).isGreaterThanOrEqualTo(responseTimeout.toMillis());
        assertThat(created.getFormattedMessage()).contains("attemptId=" + attempt(fixture).getId())
            .contains("orderId=" + fixture.orderId());
        assertThat(events("payment.lookup.observed")).isEmpty();
    }

    @DisplayName("본인 reconcile이 PG 성공을 확인하면 조회 관찰을 한 줄 남긴다.")
    @Test
    void logsReconcileLookup() {
        Fixture fixture = acceptedAndCompleted();
        appender.list.clear();

        paymentReconcileFacade.reconcile(fixture.userId(), fixture.orderId());

        ILoggingEvent lookup = single("payment.lookup.observed");
        assertThat(lookup.getLevel()).isEqualTo(Level.INFO);
        assertThat(value(lookup, "attemptId")).isEqualTo(String.valueOf(attempt(fixture).getId()));
        assertThat(value(lookup, "orderId")).isEqualTo(String.valueOf(fixture.orderId()));
        assertThat(value(lookup, "outcome")).isEqualTo("FOUND");
        assertThat(value(lookup, "pgStatus")).isEqualTo("SUCCESS");
        assertThat(value(lookup, "transactionCount")).isEqualTo("1");
        assertThat(lookup.getFormattedMessage()).contains("attemptId=" + attempt(fixture).getId());
    }

    @DisplayName("PG를 조회할 수 없으면 조회 관찰에 조회 실패 분류를 담는다.")
    @Test
    void logsUnavailableLookup() {
        Fixture fixture = acceptedAndCompleted();
        appender.list.clear();
        TestPgControl.lookupFailures(1);

        assertThat(paymentCallbackFacade.handle(providerOrderId(fixture))).isEqualTo(PaymentCallbackResult.RETRY_LATER);

        ILoggingEvent lookup = single("payment.lookup.observed");
        assertThat(value(lookup, "outcome")).isEqualTo("UNAVAILABLE");
        assertThat(value(lookup, "reason")).isNotBlank();
    }

    @DisplayName("반영이 rollback되면 기존 rollback error가 시도와 주문 ID를 담는다. 가설 판정은 이 로그로 rollback을 찾는다.")
    @Test
    void keepsAttemptIdInExistingRollbackLog() {
        Fixture fixture = acceptedAndCompleted();
        appender.list.clear();
        doThrow(new DataAccessResourceFailureException("Outbox insert 실패 주입")).when(outboxWriter).record(any());

        PaymentReflection reflection = paymentReconcileFacade.reconcile(fixture.userId(), fixture.orderId());

        assertThat(reflection.outcome()).isEqualTo(PaymentReflectionOutcome.UNCHANGED);
        assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.ERROR)
            .extracting(ILoggingEvent::getFormattedMessage)
            .anySatisfy(message -> assertThat(message).contains("결제 결과 반영을 되돌립니다.")
                .contains("attemptId=" + attempt(fixture).getId() + ",")
                .contains("orderId=" + fixture.orderId()));
    }

    @DisplayName("경계 로그에는 사용자 ID, 결제 시도 키, 카드 정보를 남기지 않는다.")
    @Test
    void keepsPersonalAndCardValuesOutOfLogs() {
        Fixture fixture = acceptedAndCompleted();
        paymentReconcileFacade.reconcile(fixture.userId(), fixture.orderId());
        String attemptKey = attempt(fixture).getPaymentAttemptKey();

        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list).allSatisfy(event -> {
            List<KeyValuePair> pairs = Objects.requireNonNullElse(event.getKeyValuePairs(), List.of());
            assertThat(pairs).extracting(pair -> pair.key)
                .doesNotContain("userId", "paymentAttemptKey", "cardNo", "cardType");
            assertThat(pairs).extracting(pair -> String.valueOf(pair.value)).doesNotContain(attemptKey, CARD_NO);
            assertThat(event.getFormattedMessage()).doesNotContain(attemptKey).doesNotContain(CARD_NO);
        });
    }

    private Fixture acceptedAndCompleted() {
        Fixture fixture = fixture();
        TestPgControl.scenario(providerOrderId(fixture), "{\"scenario\":\"SKIP_CALLBACK\"}");
        assertThat(request(fixture).attemptStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(TestPgControl.complete(providerOrderId(fixture))).isEqualTo(1);
        return fixture;
    }

    private Fixture fixture() {
        User user = userRepository.save(User.create());
        Brand brand = brandRepository.save(Brand.create("brand-" + UUID.randomUUID()));
        Product product = Product.create(brand.getId(), "상품", PRICE);
        product.changeStockTo(10L);
        Product saved = productRepository.save(product);
        Order order = orderRepository.save(Order.create(user.getId(),
            List.of(OrderItem.create(saved.getId(), saved.getName(), PRICE, QUANTITY))));
        return new Fixture(user.getId(), order.getId());
    }

    private PaymentStatusInfo request(Fixture fixture) {
        return paymentRequestFacade.requestPayment(fixture.userId(), fixture.orderId(), UUID.randomUUID().toString(),
            "TEST", CARD_NO);
    }

    private String providerOrderId(Fixture fixture) {
        return "LP-ORD-%010d-%s".formatted(fixture.orderId(), suffix);
    }

    private PaymentAttempt attempt(Fixture fixture) {
        return paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow();
    }

    private ILoggingEvent single(String event) {
        List<ILoggingEvent> found = events(event);
        assertThat(found).as(event).hasSize(1);
        return found.getFirst();
    }

    private List<ILoggingEvent> events(String event) {
        return appender.list.stream().filter(logged -> event.equals(value(logged, "event"))).toList();
    }

    private static String value(ILoggingEvent event, String key) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs == null) {
            return null;
        }
        return pairs.stream().filter(pair -> pair.key.equals(key)).map(pair -> String.valueOf(pair.value))
            .findFirst().orElse(null);
    }

    private record Fixture(Long userId, Long orderId) {}
}
