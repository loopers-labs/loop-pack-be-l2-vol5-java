package com.loopers.application.payment;

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
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.testpg.TestPgControl;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

// 주기 조회를 실제 MySQL과 실제 adapter·로컬 테스트 PG로 확인한다. 스케줄러는 끄고 서비스를 직접 호출한다.
// 회전 순서를 보려고 한 번에 조회하는 건수를 2로 줄인다.
@SpringBootTest
@TestPropertySource(properties = "payment.periodic-check.batch-size=2")
class PaymentPeriodicCheckIntegrationTest {

    private static final long INITIAL_STOCK = 10L;
    private static final int QUANTITY = 2;

    @Autowired
    private PaymentPeriodicCheck paymentPeriodicCheck;

    @Autowired
    private PaymentRequestFacade paymentRequestFacade;

    @Autowired
    private PaymentRequestTransaction paymentRequestTransaction;

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean
    private ProviderOrderSuffixGenerator suffixGenerator;

    @MockitoSpyBean
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("생성 응답을 잃거나 웹훅이 누락된 시도를 reconcile 없이 확인해 성공·실패로 종결하고 생성은 다시 보내지 않는다.")
    @Test
    void terminatesAttemptsWithoutReconcile() {
        Fixture dropped = requested("DROP_RESPONSE");
        Fixture failed = requested("FAIL");
        TestPgControl.complete(dropped.providerOrderId());
        TestPgControl.complete(failed.providerOrderId());
        ageLastChange(dropped);
        ageLastChange(failed);

        int checked = paymentPeriodicCheck.checkDue();

        assertThat(checked).isEqualTo(2);
        assertThat(attempt(dropped).getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(attempt(dropped).getTransactionKey()).isNotBlank();
        assertThat(orderStatus(dropped)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orderStatus(failed)).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(stock(failed)).isEqualTo(INITIAL_STOCK);
        assertThat(TestPgControl.stats(dropped.providerOrderId()).createRequests()).isEqualTo(1);
    }

    @DisplayName("마지막 변경 뒤 기준 시간이 지나지 않은 시도와 조회 대상 기간이 지난 시도는 조회하지 않는다.")
    @Test
    void skipsRecentAndExpiredAttempts() {
        Fixture recent = requested(null);
        Fixture expired = requested(null);
        ageLastChange(expired);
        jdbcTemplate.update("UPDATE payment_attempts SET created_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 25 HOUR) "
            + "WHERE id = ?", expired.attemptId());

        int checked = paymentPeriodicCheck.checkDue();

        assertThat(checked).isZero();
        assertThat(TestPgControl.stats(recent.providerOrderId()).lookupRequests()).isZero();
        assertThat(TestPgControl.stats(expired.providerOrderId()).lookupRequests()).isZero();
        assertThat(lastCheckedAt(recent)).isNull();
        assertThat(lastCheckedAt(expired)).isNull();
    }

    @DisplayName("발송 전에 멈춰 PG 거래가 없는 시도는 조회해도 REQUESTED와 재고 차감을 유지하고 생성을 보내지 않는다.")
    @Test
    void keepsRequestedAttemptWithoutTransaction() {
        Fixture stopped = reservedOnly();
        ageLastChange(stopped);

        paymentPeriodicCheck.checkDue();

        assertThat(attempt(stopped).getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
        assertThat(orderStatus(stopped)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(stock(stopped)).isEqualTo(INITIAL_STOCK - QUANTITY);
        assertThat(lastCheckedAt(stopped)).isNotNull();
        assertThat(TestPgControl.stats(stopped.providerOrderId()).createRequests()).isZero();
    }

    @DisplayName("결과가 나오지 않는 시도가 건수 한도보다 많아도 확인 시각이 오래된 순서로 돌아가며 모든 시도를 조회한다.")
    @Test
    void rotatesByLastCheckedAt() {
        List<Fixture> stopped = List.of(reservedOnly(), reservedOnly(), reservedOnly());
        stopped.forEach(this::ageLastChange);

        paymentPeriodicCheck.checkDue();

        assertThat(stopped).filteredOn(fixture -> lastCheckedAt(fixture) != null).hasSize(2);
        Fixture notYetChecked = stopped.stream().filter(fixture -> lastCheckedAt(fixture) == null).findFirst().orElseThrow();

        paymentPeriodicCheck.checkDue();

        assertThat(lastCheckedAt(notYetChecked)).isNotNull();
    }

    @DisplayName("정합성 오류가 난 시도는 건너뛰고 상태를 바꾸지 않으며, 같은 실행의 다음 시도는 계속 처리한다.")
    @Test
    void continuesAfterIntegrityError() {
        Fixture broken = requested(null);
        Fixture healthy = requested(null);
        TestPgControl.complete(broken.providerOrderId());
        TestPgControl.complete(healthy.providerOrderId());
        jdbcTemplate.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", broken.orderId());
        ageLastChange(broken);
        ageLastChange(healthy);

        paymentPeriodicCheck.checkDue();

        assertThat(attempt(broken).getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(lastCheckedAt(broken)).isNotNull();
        assertThat(orderStatus(healthy)).isEqualTo(OrderStatus.CONFIRMED);
    }

    @DisplayName("결과 반영이 저장 오류로 rollback된 시도는 건너뛰고 다음 시도를 처리하며, 다음 실행에서 한 번 반영한다.")
    @Test
    void retriesRolledBackAttemptInNextRun() {
        Fixture first = requested("FAIL");
        Fixture second = requested(null);
        TestPgControl.complete(first.providerOrderId());
        TestPgControl.complete(second.providerOrderId());
        ageLastChange(first);
        ageLastChange(second);
        AtomicInteger terminations = new AtomicInteger();
        doAnswer(invocation -> {
            if (terminations.incrementAndGet() == 1) {
                throw new DataAccessResourceFailureException("결과 반영 저장 실패 주입");
            }
            return invocation.callRealMethod();
        }).when(paymentAttemptRepository).terminate(anyLong(), any(), anyString(), any());

        paymentPeriodicCheck.checkDue();

        assertThat(attempt(first).getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(stock(first)).isEqualTo(INITIAL_STOCK - QUANTITY);
        assertThat(orderStatus(second)).isEqualTo(OrderStatus.CONFIRMED);

        paymentPeriodicCheck.checkDue();

        assertThat(orderStatus(first)).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(stock(first)).isEqualTo(INITIAL_STOCK);
    }

    @DisplayName("test 프로필에서는 스케줄러를 등록하지 않는다.")
    @Test
    void doesNotRegisterSchedulerInTestProfile() {
        assertThat(applicationContext.getBeansOfType(PaymentCheckScheduler.class)).isEmpty();
    }

    private Fixture requested(String scenario) {
        Fixture fixture = draft();
        if (scenario != null) {
            TestPgControl.scenario(fixture.providerOrderId(), "{\"scenario\":\"" + scenario + "\"}");
        }
        paymentRequestFacade.requestPayment(fixture.userId(), fixture.orderId(), UUID.randomUUID().toString(), "TEST", "0000");
        return fixture.withAttempt(paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow().getId());
    }

    // TX A만 commit하고 PG 생성 전에 멈춘 시도를 만든다.
    private Fixture reservedOnly() {
        Fixture fixture = draft();
        paymentRequestTransaction.reserve(fixture.userId(), fixture.orderId(), UUID.randomUUID().toString());
        return fixture.withAttempt(paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow().getId());
    }

    private Fixture draft() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        when(suffixGenerator.next()).thenReturn(suffix);
        User user = userRepository.save(User.create());
        Brand brand = brandRepository.save(Brand.create("brand-" + UUID.randomUUID()));
        Product product = Product.create(brand.getId(), "Air Max", 3_500L);
        product.changeStockTo(INITIAL_STOCK);
        Product saved = productRepository.save(product);
        Order order = orderRepository.save(Order.create(user.getId(), List.of(
            OrderItem.create(saved.getId(), saved.getName(), saved.getPrice(), QUANTITY))));
        return new Fixture(user.getId(), order.getId(), saved.getId(), "LP-ORD-%010d-%s".formatted(order.getId(), suffix), null);
    }

    // 마지막 상태 변경이 기준 시간(1분)보다 오래된 것으로 만든다.
    private void ageLastChange(Fixture fixture) {
        jdbcTemplate.update("UPDATE payment_attempts SET updated_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 MINUTE) "
            + "WHERE id = ?", fixture.attemptId());
    }

    private Object lastCheckedAt(Fixture fixture) {
        return jdbcTemplate.queryForObject(
            "SELECT last_checked_at FROM payment_attempts WHERE id = ?", Object.class, fixture.attemptId());
    }

    private PaymentAttempt attempt(Fixture fixture) {
        return paymentAttemptRepository.findById(fixture.attemptId()).orElseThrow();
    }

    private OrderStatus orderStatus(Fixture fixture) {
        return orderRepository.findById(fixture.orderId()).orElseThrow().getStatus();
    }

    private long stock(Fixture fixture) {
        return productRepository.findById(fixture.productId()).orElseThrow().getStock().amount();
    }

    private record Fixture(Long userId, Long orderId, Long productId, String providerOrderId, Long attemptId) {
        Fixture withAttempt(Long id) {
            return new Fixture(userId, orderId, productId, providerOrderId, id);
        }
    }
}
