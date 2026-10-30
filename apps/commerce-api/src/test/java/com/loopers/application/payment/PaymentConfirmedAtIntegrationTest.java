package com.loopers.application.payment;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.time.MutableClock;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 결과 반영(TX B)이 주입한 Clock으로 최초 확정 시각을 정하고, 이후 반영이 그 값을 바꾸지 못하는지 실제 MySQL에서 확인한다.
@SpringBootTest
class PaymentConfirmedAtIntegrationTest {

    private static final Instant FIRST = Instant.parse("2026-09-07T01:00:00.123456789Z");
    private static final Instant FIRST_IN_MICROS = Instant.parse("2026-09-07T01:00:00.123456Z");
    private static final Instant LATER = Instant.parse("2026-09-07T02:00:00Z");
    private static final long PRICE = 3_500L;
    private static final int QUANTITY = 2;

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(FIRST, ZoneOffset.UTC);
        }
    }

    @Autowired
    private PaymentResultService paymentResultService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeEach
    void setUp() {
        clock.set(FIRST);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("PG 성공을 처음 반영하면 주입한 Clock의 시각을 마이크로초로 잘라 확정 시각으로 저장한다.")
    @Test
    void storesClockTimeInMicrosAsConfirmedAt() {
        Fixture fixture = fixture();

        PaymentReflection reflection = paymentResultService.applyLookup(
            fixture.attemptId(), found(fixture, "TX-1", PgTransactionStatus.SUCCESS));

        assertThat(reflection.outcome()).isEqualTo(PaymentReflectionOutcome.APPLIED);
        Order order = order(fixture);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt().toInstant()).isEqualTo(FIRST_IN_MICROS);
    }

    @DisplayName("확정 뒤 시각이 지나 같은 성공을 다시 반영해도 처음 확정 시각을 유지한다.")
    @Test
    void keepsFirstConfirmedAtForDuplicateSuccess() {
        Fixture fixture = fixture();
        paymentResultService.applyLookup(fixture.attemptId(), found(fixture, "TX-1", PgTransactionStatus.SUCCESS));
        clock.set(LATER);

        PaymentReflection duplicate = paymentResultService.applyLookup(
            fixture.attemptId(), found(fixture, "TX-1", PgTransactionStatus.SUCCESS));

        assertThat(duplicate.outcome()).isEqualTo(PaymentReflectionOutcome.UNCHANGED);
        assertThat(order(fixture).getConfirmedAt().toInstant()).isEqualTo(FIRST_IN_MICROS);
    }

    @DisplayName("PG 실패로 종결한 주문은 확정 시각을 남기지 않는다.")
    @Test
    void leavesConfirmedAtEmptyForFailure() {
        Fixture fixture = fixture();

        paymentResultService.applyLookup(fixture.attemptId(), found(fixture, "TX-1", PgTransactionStatus.FAILED));

        Order order = order(fixture);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(order.getConfirmedAt()).isNull();
    }

    private Fixture fixture() {
        Brand brand = brandRepository.save(Brand.create("brand-" + UUID.randomUUID()));
        Product product = Product.create(brand.getId(), "상품", PRICE);
        product.changeStockTo(8L);
        Product saved = productRepository.save(product);
        Order order = orderRepository.save(Order.create(1L, List.of(
            OrderItem.create(saved.getId(), saved.getName(), PRICE, QUANTITY))));
        order.requestPgPayment();
        orderRepository.save(order);
        PaymentAttempt attempt = paymentAttemptRepository.save(PaymentAttempt.create(
            UUID.randomUUID().toString(), order.getId(), order.getTotalAmount(), UUID.randomUUID().toString().substring(0, 8)
        ));
        return new Fixture(attempt.getId(), order.getId(), attempt.getProviderOrderId(), order.getTotalAmount());
    }

    private PaymentLookupObservation found(Fixture fixture, String transactionKey, PgTransactionStatus status) {
        return new PaymentLookupObservation.Found(List.of(
            new PgTransaction(transactionKey, fixture.providerOrderId(), fixture.amount(), status)
        ));
    }

    private Order order(Fixture fixture) {
        return orderRepository.findById(fixture.orderId()).orElseThrow();
    }

    private record Fixture(Long attemptId, Long orderId, String providerOrderId, long amount) {}
}
