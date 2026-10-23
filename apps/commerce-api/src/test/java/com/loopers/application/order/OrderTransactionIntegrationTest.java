package com.loopers.application.order;

import com.loopers.application.payment.ControllableProbe;
import com.loopers.application.payment.PaymentTransactionProbe;
import com.loopers.application.payment.PaymentTxTestConfig;
import com.loopers.application.payment.PaymentReconcileFacade;
import com.loopers.application.payment.PaymentObservationApplier;
import com.loopers.application.payment.PaymentTransactionService;
import com.loopers.domain.order.OrderItemCommand;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.product.ProductModel;
import com.loopers.fixture.PgCommands;
import com.loopers.fixture.PgTestSupport;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.fixture.pg.PaymentStatus;
import com.loopers.fixture.pg.CreateOutcome;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTxTestConfig.class)
class OrderTransactionIntegrationTest extends PgTestSupport {

    @Autowired private OrderConfirmFacade confirm;
    @Autowired private OrderFacade orders;
    @Autowired private PaymentReconcileFacade reconcile;
    @Autowired private ProductFixture products;
    @Autowired private UserFixture users;
    @Autowired private ControllableProbe probe;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private PaymentTransactionService transactions;
    @Autowired private PaymentGateway gateway;
    @Autowired private PaymentObservationApplier applier;
    private Long user;
    private Long order;
    private ProductModel product;

    @BeforeEach
    void prepare() {
        user = users.createUserWithoutPoint().getId();
        product = products.createProduct("상품", 2000L, 5L);
        order = orders.create(user, List.of(new OrderItemCommand(product.getId(), 2L))).id();
    }

    @AfterEach
    void clean() {
        probe.clear();
        awaitPgIdle();
        cleanup.truncateAllTables();
    }

    private long stock() {
        return jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Long.class, product.getId());
    }

    @Test
    void rollsBackEverythingWhenLastOrderSaveFailsAfterFlush() {
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "beginConfirmation", order, () -> {
            assertThat(stock()).isEqualTo(3L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_history", Long.class)).isEqualTo(1);
            throw new IllegalStateException("SQL 후 TX A 실패");
        });
        assertThatThrownBy(() -> confirm.confirm(user, order, PgCommands.forOrder(order)))
            .hasMessage("SQL 후 TX A 실패");
        assertThat(orders.getOrder(user, order).status()).isEqualTo("DRAFT");
        assertThat(stock()).isEqualTo(5L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_history", Long.class)).isZero();
        assertThat(pg.state().counts().createReceived()).isZero();
    }

    @Test
    void respondsInternalServerErrorOnTechnicalFailure() throws Exception {
        success(order);
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", order, () -> {
            assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order))
                .isEqualTo("CONFIRMED");
            assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE order_id = ?", String.class, order))
                .isEqualTo("SUCCESS");
            throw new IllegalStateException("SQL 후 성공 TX B 실패");
        });
        String body = "{\"paymentAttemptKey\":\"" + PgCommands.forOrder(order).paymentAttemptKey()
            + "\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}";
        mvc.perform(post("/api/v1/orders/" + order + "/confirm")
                .header("X-USER-ID", user).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.meta.errorCode").value("Internal Server Error"));
        assertThat(orders.getOrder(user, order).status()).isEqualTo("PAYMENT_PENDING");
        assertThat(jdbc.queryForMap("SELECT status, verification_status FROM payment WHERE order_id = ?", order))
            .containsEntry("status", "UNKNOWN").containsEntry("verification_status", "NOT_CHECKED");
        assertThat(stock()).isEqualTo(3L);
        assertThat(pg.state().transactions()).singleElement().satisfies(transaction ->
            assertThat(transaction.status()).isEqualTo(PaymentStatus.SUCCESS));
        String transactionKey = pg.state().transactions().getFirst().transactionKey();
        probe.clear();
        assertThat(reconcile.reconcile(user, order).order().status()).isEqualTo("CONFIRMED");
        assertRecoveredSuccess(transactionKey);
        assertThat(reconcile.reconcile(user, order).order().status()).isEqualTo("CONFIRMED");
        assertRecoveredSuccess(transactionKey);
        assertThat(pg.state().counts().createReceived()).isEqualTo(1);
        assertThat(pg.state().counts().lookupByOrderReceived()).isEqualTo(1);
        assertThat(pg.state().counts().lookupByKeyReceived()).isEqualTo(1);
    }

    private void assertRecoveredSuccess(String transactionKey) {
        assertThat(stock()).isEqualTo(3);
        assertThat(jdbc.queryForMap("SELECT transaction_key, status FROM payment WHERE order_id = ?", order))
            .containsEntry("transaction_key", transactionKey).containsEntry("status", "SUCCESS");
        assertThat(jdbc.queryForMap("SELECT point_amount, paid_amount FROM orders WHERE id = ?", order))
            .containsEntry("point_amount", 0L).containsEntry("paid_amount", 4000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_history WHERE order_id = ? AND cause = 'ORDER_RESERVATION'",
            Long.class, order)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_history WHERE order_id = ? AND cause = 'ORDER_RELEASE'",
            Long.class, order)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_history WHERE cause = 'ORDER_USE'", Long.class)).isZero();
    }

    @Test
    void applyingSameExactRejectionTwiceReleasesReservationOnlyOnce() {
        var begin = transactions.beginConfirmation(user, order, PgCommands.forOrder(order));
        pg.enqueuePlan(PgOperation.CREATE, begin.request().providerOrderId(), ResponsePlan.outcome(CreateOutcome.REJECT_BEFORE_CREATE));
        var rejection = gateway.create(begin.request());
        assertThat(rejection).isInstanceOf(PaymentObservation.RejectedNoEffect.class);

        assertThat(applier.apply(begin.token(), rejection).order().status()).isEqualTo("PAYMENT_FAILED");
        assertThat(applier.apply(begin.token(), rejection).order().status()).isEqualTo("PAYMENT_FAILED");

        assertThat(stock()).isEqualTo(5);
        assertThat(jdbc.queryForMap("SELECT status, failure_kind, transaction_key FROM payment WHERE order_id = ?", order))
            .containsEntry("status", "FAILED").containsEntry("failure_kind", "REJECTED_NO_EFFECT").containsEntry("transaction_key", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_history WHERE order_id = ? AND cause = 'ORDER_RELEASE'",
            Long.class, order)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_history WHERE order_id = ? AND cause = 'ORDER_RESERVATION'",
            Long.class, order)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_history WHERE cause = 'ORDER_USE'", Long.class)).isZero();
        assertThat(pg.state().transactions()).isEmpty();
        assertThat(pg.state().counts().createReceived()).isEqualTo(1);
    }

    @Test
    void failureReleaseSqlRollbackDoesNotUndoCommittedReservation() {
        confirm.confirm(user, order, PgCommands.forOrder(order));
        String key = jdbc.queryForObject("SELECT transaction_key FROM payment WHERE order_id = ?", String.class, order);
        pg.setStatus(key, PaymentStatus.FAILED);
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", order, () -> {
            assertThat(stock()).isEqualTo(5L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_history WHERE cause = 'ORDER_RELEASE'", Long.class))
                .isEqualTo(1);
            throw new IllegalStateException("SQL 후 실패 반환 TX B 실패");
        });
        assertThatThrownBy(() -> reconcile.reconcile(user, order)).hasMessage("SQL 후 실패 반환 TX B 실패");
        assertThat(stock()).isEqualTo(3L);
        assertThat(orders.getOrder(user, order).payment().status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_history WHERE cause = 'ORDER_RELEASE'", Long.class)).isZero();
        probe.clear();
        assertThat(reconcile.reconcile(user, order).order().status()).isEqualTo("PAYMENT_FAILED");
        assertThat(reconcile.reconcile(user, order).order().status()).isEqualTo("PAYMENT_FAILED");
        assertThat(stock()).isEqualTo(5L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_history WHERE cause = 'ORDER_RELEASE'", Long.class)).isEqualTo(1);
    }
}
