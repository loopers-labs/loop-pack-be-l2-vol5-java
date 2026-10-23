package com.loopers.interfaces.api.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.application.payment.PaymentReconcileFacade;
import com.loopers.fixture.PgCommands;
import com.loopers.fixture.PgTestSupport;
import com.loopers.fixture.PastPointOrderFixture;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderConfirmFacade;
import com.loopers.domain.order.OrderItemCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.fixture.pg.PaymentStatus;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("관리자 주문 API 는 소유자와 무관하게 주문을 조회한다.")
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class OrderAdminV1ApiTest extends PgTestSupport {

    @MockitoSpyBean
    private PaymentRepository payments;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String ENDPOINT = "/api-admin/v1/orders";

    @Autowired
    private PastPointOrderFixture pastPointOrders;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private OrderConfirmFacade orderConfirmFacade;
    @Autowired
    private PaymentReconcileFacade reconcile;
    @Autowired
    private PointFacade pointFacade;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("GET /api-admin/v1/orders")
    @Nested
    class GetOrders {
        @DisplayName("구매자를 포함해 모든 사용자의 주문을 최신순으로 반환한다.")
        @Test
        void returnsEveryOrder() throws Exception {
            UserModel first = userFixture.createUserWithPoint();
            UserModel second = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 100L);
            orderFacade.create(first.getId(), List.of(new OrderItemCommand(shoes.getId(), 1L)));
            OrderInfo latest = orderFacade.create(second.getId(), List.of(new OrderItemCommand(shoes.getId(), 2L)));

            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(latest.id()))
                .andExpect(jsonPath("$.data.items[0].userId").value(second.getId()))
                .andExpect(jsonPath("$.data.items[1].userId").value(first.getId()));
        }

        @DisplayName("주문마다 그 주문의 모든 품목과 금액·상태를 포함한다.")
        @Test
        void includesItemsAndAmounts() throws Exception {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 100L);
            ProductModel cap = productFixture.createProduct("모자", 5_000L, 100L);
            orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(shoes.getId(), 1L),
                new OrderItemCommand(cap.getId(), 2L)
            ));

            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data.items[0].totalAmount").value(20_000))
                .andExpect(jsonPath("$.data.items[0].items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].pointAmount").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].paidAmount").doesNotExist());
        }

        @DisplayName("[잠정] page·size·sort=oldest 로 끊어 반환한다.")
        @Test
        void returnsRequestedPage() throws Exception {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 100L);
            OrderInfo first = orderFacade.create(user.getId(), List.of(new OrderItemCommand(shoes.getId(), 1L)));
            orderFacade.create(user.getId(), List.of(new OrderItemCommand(shoes.getId(), 2L)));
            orderFacade.create(user.getId(), List.of(new OrderItemCommand(shoes.getId(), 3L)));

            mockMvc.perform(get(ENDPOINT).param("page", "0").param("size", "2").param("sort", "oldest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(first.id()));
        }

        @DisplayName("지원하지 않는 sort 는 400 INVALID_SORT 로 거절한다.")
        @Test
        void rejectsUnsupportedSort() throws Exception {
            mockMvc.perform(get(ENDPOINT).param("sort", "likes_desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_SORT"));
        }
    }

    @DisplayName("GET /api-admin/v1/orders/{orderId}")
    @Nested
    class GetOrder {
        @DisplayName("확정된 주문의 구매자·품목·포인트 사용액·결제액을 반환한다.")
        @Test
        void returnsConfirmedOrder() throws Exception {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 50_000L);
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 100L);
            OrderInfo created = orderFacade.create(user.getId(), List.of(new OrderItemCommand(shoes.getId(), 2L)));
            pastPointOrders.confirm(user.getId(), created.id());

            mockMvc.perform(get(ENDPOINT + "/" + created.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(created.id()))
                .andExpect(jsonPath("$.data.userId").value(user.getId()))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.totalAmount").value(20_000))
                .andExpect(jsonPath("$.data.pointAmount").value(20_000))
                .andExpect(jsonPath("$.data.paidAmount").value(20_000))
                .andExpect(jsonPath("$.data.payment.method").value("POINT"))
                .andExpect(jsonPath("$.data.payment.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.payment.orderUrl").value(ENDPOINT + "/" + created.id()))
                .andExpect(jsonPath("$.data.payment.paymentAttemptKey").doesNotExist())
                .andExpect(jsonPath("$.data.payment.reconcileUrl").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].productId").value(shoes.getId()))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(10_000))
                .andExpect(jsonPath("$.data.items[0].amount").value(20_000));
        }

        @DisplayName("존재하지 않는 주문은 404 ORDER_NOT_FOUND 로 응답한다.")
        @Test
        void rejectsUnknownOrder() throws Exception {
            mockMvc.perform(get(ENDPOINT + "/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("ORDER_NOT_FOUND"));
        }
    }

    @Test
    void pgDetailsUseAdminOrderUrlAndCustomerReconcileUrl() throws Exception {
        var user = userFixture.createUserWithoutPoint();
        var product = productFixture.createProduct("PG 상품", 2000L, 5L);
        var order = orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 1L)));
        orderConfirmFacade.confirm(user.getId(), order.id(), PgCommands.forOrder(order.id()));
        long getsBefore = pg.state().counts().lookupByKeyReceived();
        mockMvc.perform(get(ENDPOINT + "/" + order.id()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.payment.method").value("PG"))
            .andExpect(jsonPath("$.data.payment.orderUrl").value(ENDPOINT + "/" + order.id()))
            .andExpect(jsonPath("$.data.payment.reconcileUrl").value("/api/v1/orders/" + order.id() + "/payment/reconcile"))
            .andExpect(jsonPath("$.data.payment.transactionKey").doesNotExist())
            .andExpect(jsonPath("$.data.payment.providerOrderId").doesNotExist());
        assertThat(pg.state().counts().lookupByKeyReceived()).isEqualTo(getsBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "PENDING", "SUCCESS", "FAILED"})
    void detailAndPageExposeStoredPgStatesWithoutQueryingPg(String paymentStatus) throws Exception {
        var user = userFixture.createUserWithoutPoint();
        var product = productFixture.createProduct("PG 상품", 2000L, 5L);
        var order = orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 1L)));
        if (paymentStatus.equals("UNKNOWN")) {
            pg.enqueuePlan(PgOperation.CREATE, com.loopers.domain.payment.PaymentModel.providerOrderIdOf(order.id()),
                ResponsePlan.closeEarly());
        }
        orderConfirmFacade.confirm(user.getId(), order.id(), PgCommands.forOrder(order.id()));
        if (paymentStatus.equals("SUCCESS") || paymentStatus.equals("FAILED")) {
            String key = jdbc.queryForObject("SELECT transaction_key FROM payment WHERE order_id = ?", String.class, order.id());
            pg.setStatus(key, PaymentStatus.valueOf(paymentStatus));
            reconcile.reconcile(user.getId(), order.id());
        }
        awaitPgIdle();
        var counts = pg.state().counts();
        var saved = jdbc.queryForMap("SELECT * FROM payment WHERE order_id = ?", order.id());
        String orderStatus = paymentStatus.equals("SUCCESS") ? "CONFIRMED"
            : paymentStatus.equals("FAILED") ? "PAYMENT_FAILED" : "PAYMENT_PENDING";

        for (String path : List.of(ENDPOINT + "/" + order.id(), ENDPOINT)) {
            String prefix = path.equals(ENDPOINT) ? "$.data.items[0]" : "$.data";
            var response = mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath(prefix + ".status").value(orderStatus))
                .andExpect(jsonPath(prefix + ".pointAmount").value(0))
                .andExpect(jsonPath(prefix + ".payment.method").value("PG"))
                .andExpect(jsonPath(prefix + ".payment.status").value(paymentStatus))
                .andExpect(jsonPath(prefix + ".payment.amount").value(2000))
                .andExpect(jsonPath(prefix + ".payment.verificationStatus").value(saved.get("verification_status")))
                .andExpect(jsonPath(prefix + ".payment.orderUrl").value(ENDPOINT + "/" + order.id()))
                .andExpect(jsonPath(prefix + ".payment.reconcileUrl").value("/api/v1/orders/" + order.id() + "/payment/reconcile"));
            if (paymentStatus.equals("SUCCESS")) {
                response.andExpect(jsonPath(prefix + ".paidAmount").value(2000));
            } else {
                response.andExpect(jsonPath(prefix + ".paidAmount").doesNotExist());
            }
        }
        assertThat(pg.state().counts()).isEqualTo(counts);
        assertThat(jdbc.queryForMap("SELECT * FROM payment WHERE order_id = ?", order.id())).isEqualTo(saved);
    }

    @Test
    void pageLoadsPaymentsOnceWithoutPerOrderLookup() throws Exception {
        var user = userFixture.createUserWithoutPoint();
        var product = productFixture.createProduct("상품", 2000L, 5L);
        var draft = orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 1L)));
        var pending = orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 1L)));
        orderConfirmFacade.confirm(user.getId(), pending.id(), PgCommands.forOrder(pending.id()));
        org.mockito.Mockito.clearInvocations(payments);
        mockMvc.perform(get(ENDPOINT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.items[0].payment.status").value("PENDING"))
            .andExpect(jsonPath("$.data.items[1].payment").doesNotExist());
        org.mockito.Mockito.verify(payments).findAllByOrderIds(List.of(pending.id(), draft.id()));
        org.mockito.Mockito.verify(payments, org.mockito.Mockito.never())
            .findByOrderId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pgOrderWithoutPaymentIsInternalErrorInsteadOfPointFallback() throws Exception {
        var user = userFixture.createUserWithoutPoint();
        var product = productFixture.createProduct("상품", 2000L, 5L);
        var order = orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 1L)));
        jdbc.update("UPDATE orders SET status = 'PAYMENT_PENDING', payment_method = 'PG', point_amount = 0 WHERE id = ?", order.id());
        mockMvc.perform(get(ENDPOINT + "/" + order.id()))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.meta.errorCode").value("Internal Server Error"));
    }
}
