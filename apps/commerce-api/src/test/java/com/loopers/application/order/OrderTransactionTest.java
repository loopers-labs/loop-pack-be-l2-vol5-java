package com.loopers.application.order;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.order.port.OrderRepository;
import com.loopers.application.payment.PaymentGatewayRegistry;
import com.loopers.application.payment.PaymentLookupPolicy;
import com.loopers.application.payment.port.PaymentAnomalyRepository;
import com.loopers.application.payment.port.PaymentLookupScheduleRepository;
import com.loopers.application.payment.port.PaymentRecordRepository;
import com.loopers.application.point.PointApplicationService;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.application.product.port.ProductRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.payment.PaymentRecord;
import com.loopers.infrastructure.order.OrderPersistenceAdapter;
import com.loopers.infrastructure.payment.PaymentLookupSchedulePersistenceAdapter;
import com.loopers.infrastructure.payment.PaymentRecordPersistenceAdapter;
import com.loopers.infrastructure.payment.TestPgServer;
import com.loopers.infrastructure.payment.TestPgServer.CreateBehavior;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 저장소 빈을 실패 주입용으로 바꿔 끼우는 테스트만 모은 클래스. 실패 직전 flush로 SQL이 나간 상태를 만들고,
// 서비스 트랜잭션이 끝난 뒤 새 트랜잭션에서 재조회한다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(OrderTransactionTest.FailureInjectionConfig.class)
class OrderTransactionTest {
    private static final long BALANCE = 10000;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PaymentAnomalyRepository paymentAnomalyRepository;

    @Autowired
    private MutablePaymentClock clock;

    @Autowired
    private PaymentLookupPolicy paymentLookupPolicy;

    @Autowired
    private PaymentLookupScheduleRepository paymentLookupScheduleRepository;

    @Autowired
    private BrandApplicationService brandApplicationService;

    @Autowired
    private ProductApplicationService productApplicationService;

    @Autowired
    private PointApplicationService pointApplicationService;

    @Autowired
    private OrderApplicationService orderApplicationService;

    @Autowired
    private FailingOrderRepository failingOrderRepository;

    @Autowired
    private FailingScheduleRepository failingScheduleRepository;

    @Autowired
    private FailingPaymentRecordRepository failingPaymentRecordRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private TestPgFixture testPgFixture;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private TestPgServer server;

    private long firstProductId;

    private long secondProductId;

    private long orderId;

    @BeforeEach
    void prepare() {
        clock.set(Instant.parse("2026-10-21T00:00:00Z"));
        server = testPgFixture.start();
        userJpaRepository.save(new UserJpaEntity(1));
        long brandId = brandApplicationService.create("브랜드").id().value();
        firstProductId = productApplicationService.create(brandId, "첫 상품", 2000, 5).id();
        secondProductId = productApplicationService.create(brandId, "둘째 상품", 3000, 5).id();
        pointApplicationService.charge(1, BALANCE);
        orderId = orderApplicationService.create(1, List.of(
            new OrderApplicationService.ItemRequest(firstProductId, 2),
            new OrderApplicationService.ItemRequest(secondProductId, 1))).id();
    }

    @AfterEach
    void clean() throws InterruptedException {
        testPgFixture.close();
        failingOrderRepository.disarm();
        failingScheduleRepository.armed = false;
        failingPaymentRecordRepository.disarm();
        jdbcTemplate.update("delete from order_items");
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("트랜잭션 A에서 재고 확보가 DB에 반영된 뒤 주문 저장이 실패하면 DRAFT·재고 원래 값·결제 기록 0건·create 0회이고 다시 확정할 수 있다")
    void rollsBackReservationWhenOrderSaveFails() {
        failingOrderRepository.failOnNextSave();

        assertThatThrownBy(() -> orderApplicationService.confirm(1, orderId, TestPgFixture.command()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(FailingOrderRepository.INJECTED_FAILURE);

        assertThat(failingOrderRepository.stateBeforeFailure()).isEqualTo("stock=3/4");
        assertState("DRAFT", 5, 5, 0);
        assertThat(paymentRecords()).isZero();

        OrderResult retried = orderApplicationService.confirm(1, orderId, TestPgFixture.command());
        assertThat(retried.status()).isEqualTo("PAYMENT_PENDING");
        assertState("PAYMENT_PENDING", 3, 4, 1);
    }

    @Test
    @DisplayName("트랜잭션 A에서 재고 확보와 결제 대기가 DB에 반영된 뒤 결제 기록 저장이 실패하면 DRAFT·재고 원래 값·결제 기록 0건·create 0회다")
    void rollsBackReservationWhenPaymentRecordSaveFails() {
        failingPaymentRecordRepository.failOnSave(1);

        assertThatThrownBy(() -> orderApplicationService.confirm(1, orderId, TestPgFixture.command()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(FailingPaymentRecordRepository.INJECTED_FAILURE);

        assertThat(failingPaymentRecordRepository.stateBeforeFailure()).isEqualTo("stock=3/4, order=PAYMENT_PENDING");
        assertState("DRAFT", 5, 5, 0);
        assertThat(paymentRecords()).isZero();
    }

    @Test
    @DisplayName("스케줄 INSERT까지 flush한 뒤 A가 실패하면 주문·재고·결제 기록·스케줄이 모두 롤백되고 create는 나가지 않는다")
    void rollsBackScheduleWithTransactionA() {
        failingScheduleRepository.armed = true;

        assertThatThrownBy(() -> orderApplicationService.confirm(1, orderId, TestPgFixture.command()))
            .isInstanceOf(IllegalStateException.class).hasMessage("주입된 스케줄 저장 실패");

        assertThat(failingScheduleRepository.beforeFailure).isEqualTo("stock=3/4, schedule=1");
        assertState("DRAFT", 5, 5, 0);
        assertThat(paymentRecords()).isZero();
        assertThat(paymentLookupScheduleRepository.findByOrderId(orderId)).isEmpty();
    }

    @Test
    @DisplayName("PG가 SUCCESS로 응답한 뒤 결과 반영(트랜잭션 B)의 결제 기록 저장이 실패하면 주문은 결제 대기·재고 확보 유지·create 1회로 남는다")
    void keepsPendingWhenResultSaveFailsAfterPgSuccess() throws Exception {
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        failingPaymentRecordRepository.failOnSave(2);

        mvc.perform(post("/api/v1/orders/{id}/confirm", orderId).header("X-USER-ID", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentAttemptKey\":\"" + UUID.randomUUID()
                    + "\",\"cardType\":\"" + TestPgFixture.CARD_TYPE + "\",\"cardNo\":\"" + TestPgFixture.CARD_NO + "\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
            .andExpect(jsonPath("$.data.statusUrl").value("/api/v1/orders/" + orderId));

        assertThat(failingPaymentRecordRepository.stateBeforeFailure()).isEqualTo("stock=3/4, order=CONFIRMED");
        assertState("PAYMENT_PENDING", 3, 4, 1);
        assertThat(paymentRecord()).containsEntry("result", "PENDING").containsEntry("provider_status", "SEND_STARTED");
        assertThat(server.transactions()).extracting(TestPgServer.StoredTransaction::status).containsExactly("SUCCESS");

        for (int index = 0; index < 2; index++) {
            mvc.perform(post("/api-admin/v1/orders/{id}/payment/reconcile", orderId)
                    .with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
            assertState("CONFIRMED", 3, 4, 1);
            assertThat(paymentRecord()).containsEntry("result", "SUCCESS").containsEntry("provider_status", "SUCCESS");
            assertThat(server.transactions()).hasSize(1);
            assertThat(server.lookupCalls()).isEqualTo(index + 1);
            assertThat(jdbcTemplate.queryForObject("select count(*) from payment_anomalies", Long.class)).isZero();
        }
    }

    @Test
    @DisplayName("PG 성공 뒤 B 저장 실패로 202를 반환해도 시각이 되면 자동 재조회가 한 번 확정하고 스케줄을 지운다")
    void automaticallyRecoversAfterResultSaveFailure() throws Exception {
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        failingPaymentRecordRepository.failOnSave(2);
        mvc.perform(post("/api/v1/orders/{id}/confirm", orderId).header("X-USER-ID", "1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"paymentAttemptKey\":\"" + UUID.randomUUID()
                    + "\",\"cardType\":\"" + TestPgFixture.CARD_TYPE + "\",\"cardNo\":\"" + TestPgFixture.CARD_NO + "\"}"))
            .andExpect(status().isAccepted());
        assertState("PAYMENT_PENDING", 3, 4, 1);
        clock.set(paymentLookupScheduleRepository.findByOrderId(orderId).orElseThrow().nextLookupAt());

        orderApplicationService.reconcileDuePayments();
        orderApplicationService.reconcileDuePayments();

        assertState("CONFIRMED", 3, 4, 1);
        assertThat(paymentRecord()).containsEntry("provider_status", "SUCCESS");
        assertThat(paymentLookupScheduleRepository.findByOrderId(orderId)).isEmpty();
        assertThat(server.lookupCalls()).isEqualTo(1);
        assertThat(server.transactions()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from payment_anomalies", Long.class)).isZero();
    }

    @Test
    @DisplayName("트랜잭션 A commit 뒤 create 전에 멈췄다가 서비스·클라이언트를 다시 만들어 같은 키로 확정하면 결제 대기를 돌려주고 create를 보내지 않는다")
    void doesNotResendAfterRestart() {
        String attemptKey = UUID.randomUUID().toString();
        testPgFixture.crashBeforeSend();

        assertThatThrownBy(() -> orderApplicationService.confirm(1, orderId, TestPgFixture.command(attemptKey)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("주입된 발송 전 중단");
        assertState("PAYMENT_PENDING", 3, 4, 0);
        assertThat(paymentRecord()).containsEntry("provider_status", "SEND_STARTED");

        OrderApplicationService restarted = new OrderApplicationService(failingOrderRepository, productRepository,
            failingPaymentRecordRepository,
            new PaymentGatewayRegistry(List.of(testPgFixture.newClient()), List.of(TestPgFixture.PROVIDER_ID)),
            transactionTemplate, paymentAnomalyRepository, clock, paymentLookupScheduleRepository, paymentLookupPolicy);
        OrderResult afterRestart = restarted.confirm(1, orderId, TestPgFixture.command(attemptKey));

        assertThat(afterRestart.status()).isEqualTo("PAYMENT_PENDING");
        assertThat(afterRestart.paymentResult()).isEqualTo("PENDING");
        assertState("PAYMENT_PENDING", 3, 4, 0);
        assertThat(paymentRecord()).containsEntry("provider_status", "SEND_STARTED");
        assertThat(server.transactions()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select count(*) from payment_lookup_schedules where order_id = ?",
            Long.class, orderId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select actual_attempts from payment_lookup_schedules where order_id = ?",
            Integer.class, orderId)).isZero();
    }

    private void assertState(String orderStatus, int firstStock, int secondStock, long createCalls) {
        assertThat(jdbcTemplate.queryForObject("select status from orders where id = ?", String.class, orderId))
            .isEqualTo(orderStatus);
        assertThat(productApplicationService.getAdminProduct(firstProductId).stock()).isEqualTo(firstStock);
        assertThat(productApplicationService.getAdminProduct(secondProductId).stock()).isEqualTo(secondStock);
        assertThat(server.createCalls()).isEqualTo(createCalls);
        assertThat(pointApplicationService.balance(1)).isEqualTo(BALANCE);
    }

    private long paymentRecords() {
        return jdbcTemplate.queryForObject("select count(*) from payment_records", Long.class);
    }

    private Map<String, Object> paymentRecord() {
        return jdbcTemplate.queryForMap("select result, provider_status from payment_records where order_id = ?", orderId);
    }

    @TestConfiguration
    static class FailureInjectionConfig {
        @Bean
        @Primary
        FailingScheduleRepository failingScheduleRepository(PaymentLookupSchedulePersistenceAdapter delegate,
            EntityManager entityManager, JdbcTemplate jdbcTemplate) {
            return new FailingScheduleRepository(delegate, entityManager, jdbcTemplate);
        }

        @Bean
        @Primary
        MutablePaymentClock transactionClock() {
            return new MutablePaymentClock();
        }

        @Bean
        @Primary
        FailingOrderRepository failingOrderRepository(OrderPersistenceAdapter orderPersistenceAdapter,
            EntityManager entityManager, JdbcTemplate jdbcTemplate) {
            return new FailingOrderRepository(orderPersistenceAdapter, entityManager, jdbcTemplate);
        }

        @Bean
        @Primary
        FailingPaymentRecordRepository failingPaymentRecordRepository(
            PaymentRecordPersistenceAdapter paymentRecordPersistenceAdapter, EntityManager entityManager,
            JdbcTemplate jdbcTemplate) {
            return new FailingPaymentRecordRepository(paymentRecordPersistenceAdapter, entityManager, jdbcTemplate);
        }
    }

    static class FailingScheduleRepository implements PaymentLookupScheduleRepository {
        private final PaymentLookupScheduleRepository delegate;
        private final EntityManager entityManager;
        private final JdbcTemplate jdbcTemplate;
        private boolean armed;
        private String beforeFailure;

        FailingScheduleRepository(PaymentLookupScheduleRepository delegate, EntityManager entityManager, JdbcTemplate jdbcTemplate) {
            this.delegate = delegate;
            this.entityManager = entityManager;
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public void save(Schedule schedule) {
            delegate.save(schedule);
            if (armed) {
                entityManager.flush();
                beforeFailure = stockState(jdbcTemplate) + ", schedule="
                    + jdbcTemplate.queryForObject("select count(*) from payment_lookup_schedules", Long.class);
                throw new IllegalStateException("주입된 스케줄 저장 실패");
            }
        }

        @Override
        public Optional<Schedule> findByOrderId(long orderId) {
            return delegate.findByOrderId(orderId);
        }

        @Override
        public List<Long> findDue(Instant now, int limit) {
            return delegate.findDue(now, limit);
        }

        @Override
        public void delete(long orderId) {
            delegate.delete(orderId);
        }
    }

    private static String stockState(JdbcTemplate jdbcTemplate) {
        List<Integer> stocks = jdbcTemplate.queryForList("select stock from products order by id", Integer.class);
        return "stock=" + stocks.get(0) + "/" + stocks.get(1);
    }

    // 실제 어댑터에 위임하다가 무장된 다음 주문 저장에서 앞선 재고 변경을 DB로 보낸 뒤 실패를 일으키는 테스트 전용 저장소.
    static class FailingOrderRepository implements OrderRepository {
        static final String INJECTED_FAILURE = "주입된 주문 저장 실패";

        private final OrderRepository delegate;
        private final EntityManager entityManager;
        private final JdbcTemplate jdbcTemplate;
        private final AtomicInteger remainingFailures = new AtomicInteger();
        private final AtomicReference<String> stateBeforeFailure = new AtomicReference<>();

        FailingOrderRepository(OrderRepository delegate, EntityManager entityManager, JdbcTemplate jdbcTemplate) {
            this.delegate = delegate;
            this.entityManager = entityManager;
            this.jdbcTemplate = jdbcTemplate;
        }

        void failOnNextSave() {
            stateBeforeFailure.set(null);
            remainingFailures.set(1);
        }

        void disarm() {
            remainingFailures.set(0);
        }

        String stateBeforeFailure() {
            return stateBeforeFailure.get();
        }

        @Override
        public Order save(Order order) {
            if (remainingFailures.getAndUpdate(count -> Math.max(0, count - 1)) > 0) {
                entityManager.flush();
                stateBeforeFailure.set(stockState(jdbcTemplate));
                throw new IllegalStateException(INJECTED_FAILURE);
            }
            return delegate.save(order);
        }

        @Override
        public Optional<Order> findById(long id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<Order> findByIdForUpdate(long id) {
            return delegate.findByIdForUpdate(id);
        }

        @Override
        public List<Order> findPage(Long userId, int page, int size) {
            return delegate.findPage(userId, page, size);
        }
    }

    // 무장 후 n번째 결제 기록 저장에서 앞선 재고·주문 변경을 DB로 보낸 뒤 실패를 일으키는 테스트 전용 저장소.
    // confirm에서 1번째 저장은 트랜잭션 A의 발송 시작 기록, 2번째는 결과 반영(트랜잭션 B)이다.
    static class FailingPaymentRecordRepository implements PaymentRecordRepository {
        static final String INJECTED_FAILURE = "주입된 결제 기록 저장 실패";

        private final PaymentRecordRepository delegate;
        private final EntityManager entityManager;
        private final JdbcTemplate jdbcTemplate;
        private final AtomicInteger savesUntilFailure = new AtomicInteger();
        private final AtomicReference<String> stateBeforeFailure = new AtomicReference<>();

        FailingPaymentRecordRepository(PaymentRecordRepository delegate, EntityManager entityManager,
            JdbcTemplate jdbcTemplate) {
            this.delegate = delegate;
            this.entityManager = entityManager;
            this.jdbcTemplate = jdbcTemplate;
        }

        void failOnSave(int nth) {
            stateBeforeFailure.set(null);
            savesUntilFailure.set(nth);
        }

        void disarm() {
            savesUntilFailure.set(0);
        }

        String stateBeforeFailure() {
            return stateBeforeFailure.get();
        }

        @Override
        public PaymentRecord save(PaymentRecord payment) {
            if (savesUntilFailure.get() > 0 && savesUntilFailure.decrementAndGet() == 0) {
                entityManager.flush();
                String status = jdbcTemplate.queryForObject("select status from orders where id = ?", String.class,
                    payment.getOrderId());
                stateBeforeFailure.set(stockState(jdbcTemplate) + ", order=" + status);
                throw new IllegalStateException(INJECTED_FAILURE);
            }
            return delegate.save(payment);
        }

        @Override
        public Optional<PaymentRecord> findByOrderId(long orderId) {
            return delegate.findByOrderId(orderId);
        }

        @Override
        public List<PaymentRecord> findByOrderIds(Collection<Long> orderIds) {
            return delegate.findByOrderIds(orderIds);
        }

        @Override
        public Optional<PaymentRecord> findByProviderOrderId(String providerOrderId) {
            return delegate.findByProviderOrderId(providerOrderId);
        }

        @Override
        public Optional<PaymentRecord> findByAttemptKey(String attemptKey) {
            return delegate.findByAttemptKey(attemptKey);
        }
    }
}
