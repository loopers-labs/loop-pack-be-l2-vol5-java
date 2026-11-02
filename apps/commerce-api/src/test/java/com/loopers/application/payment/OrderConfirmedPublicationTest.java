package com.loopers.application.payment;

import com.loopers.application.order.event.OrderConfirmedEvent;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.time.MutableClock;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderConfirmedPublicationTest {

    private static final Instant FIRST = Instant.parse("2026-09-07T01:00:00.123456789Z");
    private static final Instant FIRST_IN_MICROS = Instant.parse("2026-09-07T01:00:00.123456Z");
    private static final long PRICE = 3_500L;
    private static final int QUANTITY = 2;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        MutableClock publicationClock() {
            return new MutableClock(FIRST, ZoneOffset.UTC);
        }

        @Bean
        PublicationObserver publicationObserver() {
            return new PublicationObserver();
        }
    }

    static class PublicationObserver {
        private final List<OrderConfirmedEvent> events = new CopyOnWriteArrayList<>();
        private boolean failBeforeCommit;

        @EventListener
        public void failBeforeCommitWhenRequested(OrderConfirmedEvent event) {
            if (failBeforeCommit) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new DataAccessResourceFailureException("확정 이벤트 등록 후 commit 전 테스트 오류");
                    }
                });
            }
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void afterCommit(OrderConfirmedEvent event) {
            events.add(event);
        }
    }

    @Autowired
    private PaymentResultService paymentResultService;
    @Autowired
    private MutableClock clock;
    @Autowired
    private PublicationObserver observer;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeEach
    void setUp() {
        clock.set(FIRST);
        observer.events.clear();
        observer.failBeforeCommit = false;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("최초 PG 성공을 commit하면 저장된 확정 시각과 품목의 이벤트를 after-commit에 한 번 통지한다.")
    @Test
    void publishesFirstConfirmationAfterCommit() {
        Fixture fixture = fixture();

        PaymentReflection result = paymentResultService.applyLookup(
            fixture.attemptId(), found(fixture, PgTransactionStatus.SUCCESS));

        assertThat(result.outcome()).isEqualTo(PaymentReflectionOutcome.APPLIED);
        Order saved = orderRepository.findById(fixture.orderId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(saved.getConfirmedAt().toInstant()).isEqualTo(FIRST_IN_MICROS);
        assertThat(observer.events).singleElement().satisfies(event -> {
            assertThat(event.getEventId()).isEqualTo("order-confirmed:v1:" + saved.getId());
            assertThat(event.getOrderId()).isEqualTo(saved.getId());
            assertThat(event.getConfirmedAt().toInstant()).isEqualTo(saved.getConfirmedAt().toInstant());
            assertThat(event.getItems()).containsExactly(new OrderConfirmedEvent.Item(fixture.productId(), QUANTITY));
        });
    }

    @DisplayName("같은 PG 성공을 다시 반영해도 최초 이벤트만 남고 확정 시각은 바뀌지 않는다.")
    @Test
    void doesNotPublishDuplicateSuccess() {
        Fixture fixture = fixture();
        paymentResultService.applyLookup(fixture.attemptId(), found(fixture, PgTransactionStatus.SUCCESS));
        clock.set(FIRST.plusSeconds(3_600));

        PaymentReflection duplicate = paymentResultService.applyLookup(
            fixture.attemptId(), found(fixture, PgTransactionStatus.SUCCESS));

        assertThat(duplicate.outcome()).isEqualTo(PaymentReflectionOutcome.UNCHANGED);
        assertThat(observer.events).hasSize(1);
        assertThat(orderRepository.findById(fixture.orderId()).orElseThrow().getConfirmedAt().toInstant())
            .isEqualTo(FIRST_IN_MICROS);
    }

    @DisplayName("처리 중이거나 실패한 PG 관찰은 주문 확정 이벤트를 통지하지 않는다.")
    @ParameterizedTest
    @EnumSource(value = PgTransactionStatus.class, names = {"PENDING", "FAILED"})
    void doesNotPublishUnconfirmedResult(PgTransactionStatus status) {
        Fixture fixture = fixture();

        paymentResultService.applyLookup(fixture.attemptId(), found(fixture, status));

        assertThat(observer.events).isEmpty();
        assertThat(orderRepository.findById(fixture.orderId()).orElseThrow().getConfirmedAt()).isNull();
    }

    @DisplayName("생성 응답을 확인하지 못한 UNKNOWN 상태에서는 주문 확정 이벤트를 통지하지 않는다.")
    @Test
    void doesNotPublishUnknownResult() {
        Fixture fixture = fixture();

        paymentResultService.applyCreateResponse(fixture.attemptId(), new PaymentCreateObservation.Unknown("timeout"));

        assertThat(observer.events).isEmpty();
        assertThat(orderRepository.findById(fixture.orderId()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @DisplayName("이벤트 등록 뒤 실제 DB commit이 실패하면 주문·시도가 rollback되고 after-commit 통지도 없다.")
    @Test
    void doesNotPublishWhenConfirmationRollsBack() {
        Fixture fixture = fixture();
        observer.failBeforeCommit = true;

        assertThatThrownBy(() -> paymentResultService.applyLookup(
            fixture.attemptId(), found(fixture, PgTransactionStatus.SUCCESS)))
            .isInstanceOf(PaymentReflectionRolledBackException.class)
            .hasCauseInstanceOf(DataAccessResourceFailureException.class);

        assertThat(observer.events).isEmpty();
        Order saved = orderRepository.findById(fixture.orderId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(saved.getConfirmedAt()).isNull();
        assertThat(paymentAttemptRepository.findById(fixture.attemptId()).orElseThrow().getStatus())
            .isEqualTo(PaymentAttemptStatus.REQUESTED);
    }

    @DisplayName("같은 성공을 동시에 반영해도 조건부 갱신을 성공시킨 한 요청만 이벤트를 통지한다.")
    @Test
    void publishesOnceForConcurrentSuccess() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PaymentReflection> first = executor.submit(() -> {
                start.await();
                return paymentResultService.applyLookup(fixture.attemptId(), found(fixture, PgTransactionStatus.SUCCESS));
            });
            Future<PaymentReflection> second = executor.submit(() -> {
                start.await();
                return paymentResultService.applyLookup(fixture.attemptId(), found(fixture, PgTransactionStatus.SUCCESS));
            });
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS).outcome(), second.get(10, TimeUnit.SECONDS).outcome()))
                .containsExactlyInAnyOrder(PaymentReflectionOutcome.APPLIED, PaymentReflectionOutcome.UNCHANGED);
            assertThat(observer.events).hasSize(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Fixture fixture() {
        Brand brand = brandRepository.save(Brand.create("publication-" + UUID.randomUUID()));
        Product product = Product.create(brand.getId(), "상품", PRICE);
        product.changeStockTo(8L);
        Product saved = productRepository.save(product);
        Order order = orderRepository.save(Order.create(1L, List.of(
            OrderItem.create(saved.getId(), saved.getName(), PRICE, QUANTITY))));
        order.requestPgPayment();
        orderRepository.save(order);
        PaymentAttempt attempt = paymentAttemptRepository.save(PaymentAttempt.create(
            UUID.randomUUID().toString(), order.getId(), order.getTotalAmount(), UUID.randomUUID().toString().substring(0, 8)));
        return new Fixture(attempt.getId(), order.getId(), saved.getId(), attempt.getProviderOrderId(), order.getTotalAmount());
    }

    private PaymentLookupObservation found(Fixture fixture, PgTransactionStatus status) {
        return new PaymentLookupObservation.Found(List.of(
            new PgTransaction("TX-1", fixture.providerOrderId(), fixture.amount(), status)));
    }

    private record Fixture(Long attemptId, Long orderId, Long productId, String providerOrderId, long amount) {}
}
