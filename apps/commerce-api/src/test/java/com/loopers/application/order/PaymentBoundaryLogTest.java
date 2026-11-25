package com.loopers.application.order;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.order.OrderConfirmedJsonSerializer;
import com.loopers.support.FakePaymentGateway;
import com.loopers.support.fixture.Fixtures;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.loopers.support.FakePaymentGateway.pending;
import static com.loopers.support.FakePaymentGateway.success;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/**
 * 결제 경계 구조화 로그 (OrderFacade): payment.request.result · payment.lookup.result · payment.apply.
 * 문자열이 아니라 로그 이벤트의 key-value 를 본다. rollback 은 OutboxAtomicityTest 와 같이 Outbox 직렬화 실패를 주입해 실제 트랜잭션을 되돌린다.
 * 실행: ./gradlew :apps:commerce-api:test --tests '*PaymentBoundaryLogTest' (Docker 필요, Testcontainers).
 */
@SpringBootTest
@Import(PaymentBoundaryLogTest.TestConfig.class)
class PaymentBoundaryLogTest {
    private static final String CARD_NO = "1234-5678-9814-1451";

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        FailingSerializer failingSerializer(OrderConfirmedJsonSerializer delegate) {
            return new FailingSerializer(delegate);
        }
    }

    /** 실제 직렬화 앞에서 실패를 주입한다 (확정 트랜잭션 안, Outbox insert 전). */
    static class FailingSerializer implements OrderConfirmedPayloadSerializer {
        private final OrderConfirmedPayloadSerializer delegate;
        private volatile boolean fail;

        FailingSerializer(OrderConfirmedPayloadSerializer delegate) {
            this.delegate = delegate;
        }

        @Override
        public String serialize(OrderConfirmedEvent event) {
            if (fail) {
                throw new IllegalStateException("주입된 실패: OrderConfirmed 직렬화");
            }
            return delegate.serialize(event);
        }
    }

    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private FailingSerializer serializer;
    @Autowired
    private FakePaymentGateway fakePg;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger facadeLogger;
    private UserModel user;
    private ProductModel product;

    @BeforeEach
    void setUp() {
        facadeLogger = (Logger) LoggerFactory.getLogger(OrderFacade.class);
        appender.start();
        facadeLogger.addAppender(appender);
        fixtures.catalogRevision();
        user = fixtures.user();
        product = fixtures.product(fixtures.brand("브랜드").getId(), "상품", 1_000L, 10);
    }

    @AfterEach
    void tearDown() {
        facadeLogger.detachAppender(appender);
        appender.stop();
        serializer.fail = false;
        fakePg.reset();
        databaseCleanUp.truncateAllTables();
    }

    private OrderInfo draftOrder() {
        return orderFacade.createOrder(user.getId(), List.of(new OrderItemCommand(product.getId(), 1)));
    }

    private OrderInfo confirm(Long orderId) {
        return orderFacade.confirmOrder(user.getId(), orderId, "attempt-" + orderId, "SAMSUNG", CARD_NO);
    }

    /** event 키가 같은 로그들의 key-value, 남긴 순서대로. */
    private List<Map<String, Object>> events(String event) {
        return appender.list.stream()
            .map(PaymentBoundaryLogTest::keyValues)
            .filter(keyValues -> event.equals(keyValues.get("event")))
            .toList();
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        Map<String, Object> keyValues = new LinkedHashMap<>();
        if (event.getKeyValuePairs() != null) {
            for (KeyValuePair pair : event.getKeyValuePairs()) {
                keyValues.put(pair.key, pair.value);
            }
        }
        return keyValues;
    }

    @DisplayName("결제 요청: PG 응답 결과(주문·시도 키·outcome·거래 키·소요 시간)와 반영 결말(REQUEST·COMMITTED·반영 후 상태)을 남긴다. 카드번호는 남기지 않는다.")
    @Test
    void logsRequestResultAndApply() {
        OrderInfo order = draftOrder();
        String transactionKey = FakePaymentGateway.transactionKeyOf(order.id());
        fakePg.willRespond(pending(transactionKey));

        confirm(order.id());

        assertThat(events("payment.request.result")).singleElement().satisfies(keyValues -> {
            assertThat(keyValues).contains(
                entry("orderId", order.id()),
                entry("attemptKey", "attempt-" + order.id()),
                entry("outcome", "PENDING"),
                entry("transactionKey", transactionKey));
            assertThat(keyValues.get("elapsedMs")).isInstanceOf(Long.class);
        });
        assertThat(events("payment.apply")).singleElement().satisfies(keyValues -> assertThat(keyValues).containsExactly(
            entry("event", "payment.apply"),
            entry("orderId", order.id()),
            entry("source", "REQUEST"),
            entry("outcome", "PENDING"),
            entry("tx", "COMMITTED"),
            entry("status", OrderStatus.AWAITING_PAYMENT.name())));
        assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains(CARD_NO));
    }

    @DisplayName("결제 대기 해소: 응답을 잃은(UNKNOWN) 주문의 거래 조회 결과(RESOLVE·SUCCESS·거래 키·금액)와 반영 결말(COMMITTED·CONFIRMED)을 남긴다.")
    @Test
    void logsResolveLookupAndApply() {
        OrderInfo order = draftOrder();
        fakePg.willRespond(PaymentGatewayResult.unknown());
        confirm(order.id());
        appender.list.clear();
        fakePg.willFind(success("tx-found", order.totalAmount()));

        orderFacade.resolveAwaitingPayment(user.getId(), order.id());

        assertThat(events("payment.lookup.result")).singleElement().satisfies(keyValues -> assertThat(keyValues).contains(
            entry("orderId", order.id()),
            entry("source", "RESOLVE"),
            entry("outcome", "SUCCESS"),
            entry("transactionKey", "tx-found"),
            entry("amount", order.totalAmount())));
        assertThat(events("payment.apply")).singleElement().satisfies(keyValues -> assertThat(keyValues).contains(
            entry("source", "RESOLVE"),
            entry("tx", "COMMITTED"),
            entry("status", OrderStatus.CONFIRMED.name())));
    }

    @DisplayName("callback: 권위 조회 결과(CALLBACK)와 반영 결말을 남긴다.")
    @Test
    void logsCallbackLookupAndApply() {
        OrderInfo order = draftOrder();
        String transactionKey = FakePaymentGateway.transactionKeyOf(order.id());
        fakePg.willRespond(pending(transactionKey));
        confirm(order.id());
        appender.list.clear();
        fakePg.willFind(success(transactionKey, order.totalAmount()));

        orderFacade.applyPaymentCallback(PgOrderId.of(order.id()), success(transactionKey, order.totalAmount()));

        assertThat(events("payment.lookup.result")).singleElement().satisfies(keyValues -> assertThat(keyValues).contains(
            entry("source", "CALLBACK"),
            entry("outcome", "SUCCESS"),
            entry("transactionKey", transactionKey)));
        assertThat(events("payment.apply")).singleElement().satisfies(keyValues -> assertThat(keyValues).contains(
            entry("source", "CALLBACK"),
            entry("tx", "COMMITTED"),
            entry("status", OrderStatus.CONFIRMED.name())));
    }

    @DisplayName("반영 트랜잭션이 실패하면 ROLLED_BACK 과 예외 종류를 남기고 예외를 그대로 던진다. 주문은 결제 대기 그대로, Outbox 없음.")
    @Test
    void logsRollback() {
        OrderInfo order = draftOrder();
        fakePg.willRespond(PaymentGatewayResult.unknown());
        confirm(order.id());
        appender.list.clear();
        fakePg.willFind(success("tx-found", order.totalAmount()));
        serializer.fail = true;

        assertThatThrownBy(() -> orderFacade.resolveAwaitingPayment(user.getId(), order.id()))
            .isInstanceOf(IllegalStateException.class);

        assertThat(events("payment.apply")).singleElement().satisfies(keyValues -> assertThat(keyValues).containsExactly(
            entry("event", "payment.apply"),
            entry("orderId", order.id()),
            entry("source", "RESOLVE"),
            entry("outcome", "SUCCESS"),
            entry("tx", "ROLLED_BACK"),
            entry("error", "IllegalStateException")));
        assertThat(fixtures.reloadOrder(order.id()).getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(fixtures.reloadOrder(order.id()).getTransactionKey()).isNull();
        assertThat(fixtures.outboxOf(order.id())).isEmpty();
    }
}
