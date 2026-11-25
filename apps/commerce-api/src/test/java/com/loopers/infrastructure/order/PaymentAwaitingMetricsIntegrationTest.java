package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemCommand;
import com.loopers.application.order.PaymentGatewayResult;
import com.loopers.domain.order.AwaitingPaymentCounts;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.support.FakePaymentGateway;
import com.loopers.support.fixture.Fixtures;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static com.loopers.support.FakePaymentGateway.failed;
import static com.loopers.support.FakePaymentGateway.pending;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결제 대기 수 쿼리와 commerce.payment.awaiting gauge 를 실제 MySQL 로 본다. 주문 상태는 실제 주문·결제 경로로 만든다 (DB 에 상태를 직접 쓰지 않는다).
 * 실행: ./gradlew :apps:commerce-api:test --tests '*PaymentAwaitingMetricsIntegrationTest' (Docker 필요, Testcontainers).
 */
@SpringBootTest
class PaymentAwaitingMetricsIntegrationTest {
    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PaymentAwaitingMetrics metrics;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private FakePaymentGateway fakePg;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private ProductModel product;

    @BeforeEach
    void setUp() {
        fixtures.catalogRevision();
        user = fixtures.user();
        product = fixtures.product(fixtures.brand("브랜드").getId(), "상품", 1_000L, 100);
    }

    @AfterEach
    void tearDown() {
        fakePg.reset();
        databaseCleanUp.truncateAllTables();
    }

    private OrderInfo confirmWith(PaymentGatewayResult pgResponse) {
        OrderInfo order = orderFacade.createOrder(user.getId(), List.of(new OrderItemCommand(product.getId(), 1)));
        fakePg.willRespond(pgResponse);
        return orderFacade.confirmOrder(user.getId(), order.id(), "attempt-" + order.id(), "SAMSUNG", "1234-5678-9814-1451");
    }

    private double gauge(String tag) {
        return meterRegistry.get(PaymentAwaitingMetrics.METRIC).tag(PaymentAwaitingMetrics.TAG, tag).gauge().value();
    }

    @DisplayName("AWAITING_PAYMENT 만 거래 키 유무로 나눠 센다: PENDING 접수(키 있음) 1, 결과 미확인(키 없음) 2. DRAFT·CONFIRMED·FAILED 는 세지 않는다.")
    @Test
    void countsOnlyAwaitingPaymentByTransactionKey() {
        fixtures.draftOrder(user.getId(), product, 1);
        fixtures.confirmedOrder(user.getId(), product, 1);
        confirmWith(failed(null));
        OrderInfo pendingOrder = orderFacade.createOrder(user.getId(), List.of(new OrderItemCommand(product.getId(), 1)));
        fakePg.willRespond(pending(FakePaymentGateway.transactionKeyOf(pendingOrder.id())));
        orderFacade.confirmOrder(user.getId(), pendingOrder.id(), "attempt-" + pendingOrder.id(), "SAMSUNG", "1234-5678-9814-1451");
        confirmWith(PaymentGatewayResult.unknown());
        confirmWith(PaymentGatewayResult.unknown());

        assertThat(orderRepository.countAwaitingPayment()).isEqualTo(new AwaitingPaymentCounts(1, 2));
    }

    @DisplayName("gauge 는 갱신하면 같은 DB 값을 내보낸다: known 1, missing 2.")
    @Test
    void gaugeReflectsDatabaseAfterRefresh() {
        OrderInfo pendingOrder = orderFacade.createOrder(user.getId(), List.of(new OrderItemCommand(product.getId(), 1)));
        fakePg.willRespond(pending(FakePaymentGateway.transactionKeyOf(pendingOrder.id())));
        orderFacade.confirmOrder(user.getId(), pendingOrder.id(), "attempt-" + pendingOrder.id(), "SAMSUNG", "1234-5678-9814-1451");
        confirmWith(PaymentGatewayResult.unknown());
        confirmWith(PaymentGatewayResult.unknown());

        metrics.refresh();

        assertThat(gauge("known")).isEqualTo(1.0);
        assertThat(gauge("missing")).isEqualTo(2.0);
    }

    @DisplayName("결제 대기가 없으면 0 이다 (NaN 이 아니다).")
    @Test
    void zeroWhenNoAwaiting() {
        fixtures.confirmedOrder(user.getId(), product, 1);

        metrics.refresh();

        assertThat(gauge("known")).isZero();
        assertThat(gauge("missing")).isZero();
    }
}
