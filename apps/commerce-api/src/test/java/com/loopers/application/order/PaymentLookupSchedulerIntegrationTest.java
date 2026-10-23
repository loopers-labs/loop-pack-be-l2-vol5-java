package com.loopers.application.order;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.support.FakePaymentGateway;
import com.loopers.support.fixture.Fixtures;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.loopers.support.FakePaymentGateway.failed;
import static com.loopers.support.FakePaymentGateway.pending;
import static com.loopers.support.FakePaymentGateway.success;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * confirmOrder 의 백업 거래 조회 예약. 지연을 짧게(300ms) 줄인 별도 컨텍스트에서 본다 (test 프로필 기본은 1h 라 다른 테스트에선 조회가 나가지 않는다).
 * 결제 요청과 거래 조회는 서로 기다리지 않고, 둘의 결과는 applyResult 로만 만난다.
 * 결제 요청 permit 은 1개로 줄여 PaymentRequestGuard 에 거절된 주문은 거래 조회도 예약되지 않는지 본다.
 */
@SpringBootTest(properties = {"pg.lookup-delay=300ms", "pg.bulkhead.max-concurrent-calls=1"})
class PaymentLookupSchedulerIntegrationTest {
    private static final String KEY = "attempt-1";
    private static final String CARD_TYPE = "SAMSUNG";
    private static final String CARD_NO = "1234-5678-9814-1451";
    private static final Duration LOOKUP_DELAY = Duration.ofMillis(300);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private OrderFacade orderFacade;
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
        BrandModel brand = fixtures.brand("브랜드");
        product = fixtures.product(brand.getId(), "상품", 1_000L, 5);
    }

    @AfterEach
    void tearDown() {
        fakePg.reset();
        databaseCleanUp.truncateAllTables();
    }

    private OrderInfo confirm(OrderModel order) {
        return orderFacade.confirmOrder(user.getId(), order.getId(), KEY, CARD_TYPE, CARD_NO);
    }

    private OrderStatus statusOf(OrderModel order) {
        return fixtures.reloadOrder(order.getId()).getStatus();
    }

    private static void awaitUntil(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(AWAIT_TIMEOUT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("조건이 " + AWAIT_TIMEOUT + " 안에 맞지 않았다");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @DisplayName("결제 요청이 PENDING 이면 거래 조회를 기다리지 않고 결제 대기로 응답하고, 지연 뒤 거래 조회 결과가 반영된다.")
    @Test
    void lookupConcludes_afterPendingResponse() {
        OrderModel order = fixtures.draftOrder(user.getId(), product, 2);
        String tx = FakePaymentGateway.transactionKeyOf(order.getId());
        fakePg.willRespond(pending(tx));
        fakePg.willFind(success(tx, 2_000L));

        OrderInfo info = confirm(order);

        assertThat(info.status()).isEqualTo("AWAITING_PAYMENT");
        awaitUntil(() -> statusOf(order) == OrderStatus.CONFIRMED);
        assertThat(fakePg.finds()).containsExactly(order.getId());
    }

    @DisplayName("결제 요청이 응답 없이 걸린 동안 거래 조회가 결론내면, 결제 요청이 read timeout(ERROR)으로 끝나도 응답은 그 결론이다. 재고 반환 없음.")
    @Test
    void lookupConcludes_whilePaymentRequestHangs() {
        OrderModel order = fixtures.draftOrder(user.getId(), product, 2);
        String tx = FakePaymentGateway.transactionKeyOf(order.getId());
        fakePg.willRespond(request -> {
            awaitUntil(() -> statusOf(order) != OrderStatus.AWAITING_PAYMENT);
            return PaymentGatewayResult.error();
        });
        fakePg.willFind(success(tx, 2_000L));

        OrderInfo info = confirm(order);

        assertThat(info.status()).isEqualTo("CONFIRMED");
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(3);
        assertThat(fakePg.requests()).hasSize(1);
        assertThat(fakePg.finds()).containsExactly(order.getId());
    }

    @DisplayName("거래 조회가 PENDING 이면 주문은 결제 대기 그대로고, 뒤이어 결제 요청이 read timeout(ERROR)으로 끝나면 FAILED, 재고 반환.")
    @Test
    void paymentRequestErrorFails_afterLookupPending() {
        OrderModel order = fixtures.draftOrder(user.getId(), product, 2);
        String tx = FakePaymentGateway.transactionKeyOf(order.getId());
        fakePg.willRespond(request -> {
            awaitUntil(() -> fakePg.finds().contains(order.getId()) && tx.equals(fixtures.reloadOrder(order.getId()).getTransactionKey()));
            assertThat(statusOf(order)).isEqualTo(OrderStatus.AWAITING_PAYMENT);
            return PaymentGatewayResult.error();
        });
        fakePg.willFind(pending(tx));

        OrderInfo info = confirm(order);

        assertThat(info.status()).isEqualTo("FAILED");
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(5);
    }

    @DisplayName("결제 요청이 먼저 결론내도 거래 조회는 나가고, 이미 결론난 주문이라 조회 결과는 반영되지 않는다.")
    @Test
    void lookupStillRuns_afterPaymentConcludes() {
        OrderModel order = fixtures.draftOrder(user.getId(), product, 2);
        fakePg.willFind(failed(FakePaymentGateway.transactionKeyOf(order.getId())));

        OrderInfo info = confirm(order);

        assertThat(info.status()).isEqualTo("CONFIRMED");
        awaitUntil(() -> fakePg.finds().contains(order.getId()));
        assertThat(statusOf(order)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(3);
    }

    @DisplayName("결제 요청 permit 이 모두 잡혀 있으면 PG 를 부르지 않고 FAILED, 재고 반환. 거래 조회도 예약되지 않는다.")
    @Test
    void neitherRequestsNorLooksUp_whenBulkheadFull() throws Exception {
        OrderModel holding = fixtures.draftOrder(user.getId(), product, 1);
        OrderModel rejected = fixtures.draftOrder(user.getId(), product, 2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fakePg.willRespond(request -> {
            entered.countDown();
            awaitUntil(() -> release.getCount() == 0);
            return success(FakePaymentGateway.transactionKeyOf(request.orderId()), request.amount());
        });
        CompletableFuture<OrderInfo> first = CompletableFuture.supplyAsync(() -> confirm(holding));
        assertThat(entered.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        OrderInfo info = confirm(rejected);
        release.countDown();

        assertThat(info.status()).isEqualTo("FAILED");
        assertThat(first.get(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).status()).isEqualTo("CONFIRMED");
        assertThat(fakePg.requests()).extracting(FakePaymentGateway.Request::orderId).containsExactly(holding.getId());
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(4);
        awaitUntil(() -> fakePg.finds().contains(holding.getId()));
        Thread.sleep(LOOKUP_DELAY.multipliedBy(2).toMillis());
        assertThat(fakePg.finds()).containsExactly(holding.getId());
    }
}
