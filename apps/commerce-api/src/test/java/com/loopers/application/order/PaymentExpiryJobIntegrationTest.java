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
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결제 대기 정리 job 이 스케줄대로 도는지. 만료 300ms·주기 100ms 로 줄인 별도 컨텍스트에서 본다 (test 프로필 기본은 주기 1h 라 다른 테스트에선 돌지 않는다).
 * job 은 테이블 전체를 보므로, 이 컨텍스트가 캐시에 남아 다른 테스트의 결제 대기 주문을 거두지 않게 끝나면 닫는다.
 */
@SpringBootTest(properties = {"pg.payment-expiry=300ms", "pg.payment-expiry-interval=100ms"})
@DirtiesContext
class PaymentExpiryJobIntegrationTest {
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private FakePaymentGateway fakePg;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private ApplicationContext applicationContext;

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

    @DisplayName("결제 요청이 결론내지 못한(UNKNOWN) 주문은 만료 시간이 지나면 job 이 FAILED 로 바꾸고 재고를 반환한다.")
    @Test
    void expiresAwaitingOrder() {
        OrderModel order = fixtures.draftOrder(user.getId(), product, 2);
        fakePg.willRespond(PaymentGatewayResult.unknown());

        OrderInfo info = orderFacade.confirmOrder(user.getId(), order.getId(), "attempt-1", "SAMSUNG", "1234-5678-9814-1451");

        assertThat(info.status()).isEqualTo("AWAITING_PAYMENT");
        awaitUntil(() -> fixtures.reloadOrder(order.getId()).getStatus() == OrderStatus.FAILED);
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(5);
    }

    @DisplayName("스케줄링을 켜도 Boot 의 applicationTaskExecutor 자동 구성은 그대로다.")
    @Test
    void keepsApplicationTaskExecutor() {
        assertThat(applicationContext.containsBean("applicationTaskExecutor")).isTrue();
    }
}
