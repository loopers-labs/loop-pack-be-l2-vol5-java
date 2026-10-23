package com.loopers.application.payment;

import com.loopers.application.order.OrderAdminInfo;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentAttemptModel;
import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.order.StockReservationJpaRepository;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.pg.PaymentTestSupport;
import com.loopers.support.pg.TestPgContract;
import com.loopers.support.pg.TestPgResetExtension;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.pg.TestPgStatus;
import com.loopers.support.pg.TestPgTransaction;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 결제 시나리오 통합 테스트의 공통 기반 (design.md 9-9절). 실제 MySQL, 실제 controller, 실제 adapter → 테스트 PG fixture를 모두 거친다.
 *
 * <p>모든 장면은 같은 수치에서 시작한다. 재고 5, 주문 수량 2(합계 14,000원), 포인트 잔액 100,000원.
 * 그래서 가용 재고는 결제 시작 뒤 3, 성공 뒤 3, 확정 실패 뒤 5이고, 잔액은 어느 장면에서도 그대로여야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(TestPgResetExtension.class)
abstract class PaymentScenarioTestBase {

    protected static final long USER_ID = 1L;
    protected static final long OTHER_USER_ID = 2L;
    protected static final long INITIAL_BALANCE = 100_000L;
    protected static final int INITIAL_STOCK = 5;
    protected static final int ORDER_QUANTITY = 2;
    protected static final long UNIT_PRICE = 7_000L;
    protected static final long ORDER_AMOUNT = UNIT_PRICE * ORDER_QUANTITY;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected OrderFacade orderFacade;

    @Autowired
    protected PaymentFacade paymentFacade;

    @Autowired
    protected PaymentTransactions paymentTransactions;

    @Autowired
    protected ProductFacade productFacade;

    @Autowired
    protected BrandJpaRepository brandJpaRepository;

    @Autowired
    protected ProductJpaRepository productJpaRepository;

    @Autowired
    protected PointJpaRepository pointJpaRepository;

    @Autowired
    protected OrderJpaRepository orderJpaRepository;

    @Autowired
    protected PaymentAttemptJpaRepository paymentAttemptJpaRepository;

    @Autowired
    protected StockReservationJpaRepository stockReservationJpaRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    protected final TestPgServer pg = TestPgServer.shared();

    protected ProductModel product;
    protected Long orderId;

    @BeforeEach
    void setUpScenario() {
        BrandModel brand = brandJpaRepository.save(new BrandModel("나이키"));
        product = productJpaRepository.save(new ProductModel(brand.getId(), "runner", UNIT_PRICE, INITIAL_STOCK));
        PointModel point = new PointModel(USER_ID);
        point.charge(INITIAL_BALANCE);
        pointJpaRepository.save(point);
        orderId = saveDraft(USER_ID);
    }

    @AfterEach
    void tearDownScenario() {
        databaseCleanUp.truncateAllTables();
    }

    // ---- 준비 ----

    protected Long saveDraft(long userId) {
        return orderJpaRepository.save(new OrderModel(userId, List.of(new OrderItem(product.getId(), ORDER_QUANTITY, UNIT_PRICE)))).getId();
    }

    // ---- HTTP (실제 controller) ----

    protected ResultActions confirm() throws Exception {
        return confirm(orderId, USER_ID, PaymentTestSupport.attemptKeyOf(orderId), PaymentTestSupport.CARD_TYPE);
    }

    protected ResultActions confirm(Long targetOrderId, long userId, String attemptKey, String cardType) throws Exception {
        String body = "{\"paymentAttemptKey\":\"" + attemptKey + "\",\"cardType\":\"" + cardType
            + "\",\"cardNo\":\"" + PaymentTestSupport.CARD_NO + "\"}";
        return mvc.perform(post("/api/v1/orders/" + targetOrderId + "/confirm")
            .header("X-USER-ID", userId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    protected ResultActions reconcile() throws Exception {
        return mvc.perform(post("/api/v1/orders/" + orderId + "/payment/reconcile").header("X-USER-ID", USER_ID));
    }

    /** PG가 보내는 결과 알림. 고객 식별 헤더 없이 보낸다. */
    protected ResultActions callback(String transactionKey, String providerOrderId, String status) throws Exception {
        String body = "{\"transactionKey\":\"" + transactionKey + "\",\"orderId\":\"" + providerOrderId + "\",\"status\":\"" + status + "\"}";
        return mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 과제 문장이 알림 본문을 설명하는 이름(providerOrderId)으로 보낸 알림. 같은 값으로 받아야 한다. */
    protected ResultActions callbackWithProviderOrderIdName(String transactionKey, String providerOrderId, String status) throws Exception {
        String body = "{\"transactionKey\":\"" + transactionKey + "\",\"providerOrderId\":\"" + providerOrderId + "\",\"status\":\"" + status + "\"}";
        return mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** PG가 이 거래의 현재 상태로 보내는 알림 본문 그대로 보낸다. */
    protected ResultActions callbackOf(TestPgTransaction transaction) throws Exception {
        return callback(transaction.transactionKey(), transaction.orderId(), transaction.status().name());
    }

    // ---- PG ----

    protected String providerOrderId() {
        return ProviderOrderId.from(orderId);
    }

    /** 이 주문의 PG 거래 한 건. 정확히 하나여야 한다. */
    protected TestPgTransaction pgTransaction() {
        return PaymentTestSupport.onlyTransactionOf(pg, orderId);
    }

    protected void pgCompletes(TestPgStatus status) {
        PaymentTestSupport.completeAt(pg, orderId, status);
    }

    /** 이 주문과 상관없는 거래를 PG에 직접 넣는다 (제공자 계정은 같다). */
    protected TestPgTransaction seedOtherTransaction(String providerOrderId, long amount, TestPgStatus status) {
        return pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, providerOrderId, amount, status);
    }

    // ---- DB ----

    protected int stock() {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStock();
    }

    protected long balance() {
        return pointJpaRepository.findByUserId(USER_ID).orElseThrow().getBalance();
    }

    protected ReservationStatus reservation() {
        return stockReservationJpaRepository.findByOrderId(orderId).orElseThrow().getStatus();
    }

    protected PaymentAttemptModel attempt() {
        return paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
    }

    protected OrderAdminInfo order() {
        return orderFacade.getOrderForAdmin(orderId);
    }
}
