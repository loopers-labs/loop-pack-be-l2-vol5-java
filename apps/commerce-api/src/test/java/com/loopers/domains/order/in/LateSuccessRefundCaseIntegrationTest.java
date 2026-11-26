package com.loopers.domains.order.in;

import com.loopers.common.domain.Money;
import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.order.app.OrderApplicationService;
import com.loopers.domains.payment.app.port.PaymentRefundCaseRepository;
import com.loopers.domains.payment.domain.PaymentRecord;
import com.loopers.domains.payment.domain.RefundCase;
import com.loopers.domains.payment.domain.RefundCase.MoneyStatus;
import com.loopers.domains.payment.domain.RefundCase.Owner;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.domains.payment.out.TestPgServer;
import com.loopers.domains.product.app.ProductApplicationService;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 만료 때 환불 확인 대상이 생기고, 만료·결제 실패 주문의 늦은 성공이 그 대상으로 합류하는지 본다(10주차 5단계 F1·F8·Q2, 5b 합류 행).
// jitter는 상한을 그대로 고르는 고정 대역(PaymentLookupScheduleIntegrationTest.TimeConfig)이라 다음 조회 시각을 정확히 본다.
// 대상은 포트로 새로 읽는다. 서비스 트랜잭션이 끝난 뒤 다른 트랜잭션에서 읽으므로 DB에 남은 값이다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentLookupScheduleIntegrationTest.TimeConfig.class)
class LateSuccessRefundCaseIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofMinutes(2));
    private static final Instant LATE = EXPIRES_AT.plus(Duration.ofMinutes(10));
    @Autowired private MockMvc mvc;
    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private OrderApplicationService orders;
    @Autowired private PaymentRefundCaseRepository refundCases;
    @Autowired private MutablePaymentClock clock;
    @Autowired private TestPgFixture pg;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private MeterRegistry meterRegistry;
    private TestPgServer server;
    private long productId;
    private long orderId;

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        server = pg.start();
        users.save(new UserJpaEntity(1));
        long brand = brands.create("브랜드").id();
        productId = products.create(brand, "상품", 1000, 5).id();
        orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 2))).id();
    }

    @AfterEach
    void clean() throws Exception {
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("만료되면 같은 트랜잭션에서 대상이 확인 중·자동 담당으로 생기고 만료 직전 상태 PENDING·거래 키·금액·기한·첫 조회(상한 1분 고정 대역)를 담으며 만료를 다시 돌려도 한 건이다")
    void opensCaseOnExpiry() throws Exception {
        start(CreateBehavior.ACCEPT_PENDING);
        String key = transactionKey();
        clock.set(EXPIRES_AT);

        orders.expireOverduePayments();
        orders.expireOverduePayments();

        assertThat(caseCount()).isEqualTo(1);
        RefundCase refundCase = refundCase().orElseThrow();
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHECKING);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.AUTO);
        assertThat(refundCase.getPriorProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.PENDING);
        assertThat(refundCase.getTransactionKey()).isEqualTo(key);
        assertThat(refundCase.getProviderId()).isEqualTo(TestPgFixture.PROVIDER_ID);
        assertThat(refundCase.getProviderOrderId()).isEqualTo(providerOrderId());
        assertThat(refundCase.getAmount()).isEqualTo(new Money(2000));
        assertThat(refundCase.getOpenedAt()).isEqualTo(EXPIRES_AT);
        assertThat(refundCase.getHandoffAt()).isEqualTo(EXPIRES_AT.plus(Duration.ofDays(2)));
        assertThat(refundCase.getPromiseAt()).isEqualTo(EXPIRES_AT.plus(Duration.ofDays(4)));
        assertThat(refundCase.getNextCheckAt()).isEqualTo(EXPIRES_AT.plus(RefundCase.FIRST_CHECK_DELAY));
        assertThat(refundCase.getCheckAttempts()).isZero();
        assertOrder("PAYMENT_EXPIRED", 5);
        assertThat(paymentProviderStatus()).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("거래 키 없이 결과 모름으로 만료되면 대상은 직전 상태 UNKNOWN·키 없음이다")
    void opensCaseWithoutKeyForUnknownPayment() throws Exception {
        start(CreateBehavior.ERROR_500_AFTER_CREATE);
        clock.set(EXPIRES_AT);

        orders.expireOverduePayments();

        assertThat(caseCount()).isEqualTo(1);
        RefundCase refundCase = refundCase().orElseThrow();
        assertThat(refundCase.getPriorProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.UNKNOWN);
        assertThat(refundCase.getTransactionKey()).isNull();
    }

    @ParameterizedTest(name = "늦은 성공 경로={0}")
    @ValueSource(strings = {"callback", "customer-reconcile", "admin-reconcile"})
    @DisplayName("만료 주문에 늦은 성공이 두 번 오면 같은 확인 중 대상에 거래 키를 저장하고 다음 조회를 그 시각으로 당기며 대상은 한 건·주문은 만료 그대로·충돌 이상은 유지된다")
    void joinsLateSuccessIntoExistingCase(String path) throws Exception {
        start(CreateBehavior.ERROR_500_AFTER_CREATE);
        clock.set(EXPIRES_AT);
        orders.expireOverduePayments();
        jdbc.update("update payment_refund_cases set next_check_at = date_add(next_check_at, interval 1 day) where order_id = ?",
            orderId);
        String key = transactionKey();
        server.changeStatus(key, "SUCCESS");
        clock.set(LATE);

        lateSuccess(path);
        lateSuccess(path);

        assertThat(caseCount()).isEqualTo(1);
        RefundCase refundCase = refundCase().orElseThrow();
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHECKING);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.AUTO);
        assertThat(refundCase.getTransactionKey()).isEqualTo(key);
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE);
        assertThat(refundCase.getOpenedAt()).isEqualTo(EXPIRES_AT);
        assertThat(refundCase.getPriorProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.UNKNOWN);
        assertOrder("PAYMENT_EXPIRED", 5);
        assertThat(paymentProviderStatus()).isEqualTo("EXPIRED");
        assertThat(unresolved("CONFLICTING_RESULT")).isEqualTo(1);
    }

    @Test
    @DisplayName("대상 기록 없이 만료된 주문(기능 이전 만료)에 늦은 성공 callback이 오면 확인 중·자동 담당 대상을 새로 한 건 만들고 키를 저장한다")
    void opensCaseForLateSuccessWithoutCase() throws Exception {
        start(CreateBehavior.ACCEPT_PENDING);
        clock.set(EXPIRES_AT);
        orders.expireOverduePayments();
        jdbc.update("delete from payment_refund_cases");
        String key = transactionKey();
        server.changeStatus(key, "SUCCESS");
        clock.set(LATE);

        lateSuccess("callback");
        lateSuccess("callback");

        assertThat(caseCount()).isEqualTo(1);
        RefundCase refundCase = refundCase().orElseThrow();
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHECKING);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.AUTO);
        assertThat(refundCase.getTransactionKey()).isEqualTo(key);
        assertThat(refundCase.getOpenedAt()).isEqualTo(LATE);
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE);
        assertThat(refundCase.getPriorProviderStatus()).isNull();
        assertThat(unresolved("CONFLICTING_RESULT")).isEqualTo(1);
    }

    @ParameterizedTest(name = "늦은 성공 경로={0}")
    @ValueSource(strings = {"callback", "customer-reconcile", "admin-reconcile"})
    @DisplayName("PG가 FAILED라 해 결제 실패가 된 주문에 늦은 성공이 두 번 오면 자동 환불 대상이 아니라 청구 확인·사람 담당(RESULT_REVERSED) 대상 한 건만 남고 조회는 계속한다")
    void handsReversedFailureToHuman(String path) throws Exception {
        start(CreateBehavior.ACCEPT_FAILED);
        assertOrder("PAYMENT_FAILED", 5);
        String key = transactionKey();
        server.changeStatus(key, "SUCCESS");
        clock.set(LATE);

        lateSuccess(path);
        lateSuccess(path);

        assertThat(caseCount()).isEqualTo(1);
        RefundCase refundCase = refundCase().orElseThrow();
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(refundCase.getOwnerReason()).isEqualTo(RefundCase.RESULT_REVERSED);
        assertThat(refundCase.getOwnerChangedAt()).isEqualTo(LATE);
        assertThat(refundCase.getTransactionKey()).isEqualTo(key);
        assertThat(refundCase.getPriorProviderStatus()).isEqualTo(PaymentRecord.ProviderStatus.FAILED);
        assertThat(refundCase.getConcludedAt()).isNull();
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE.plus(RefundCase.PERSON_CHECK_DELAY));
        assertOrder("PAYMENT_FAILED", 5);
        assertThat(paymentProviderStatus()).isEqualTo("FAILED");
        assertThat(unresolved("CONFLICTING_RESULT")).isEqualTo(1);
    }

    @Test
    @DisplayName("청구 없음으로 끝난 만료 주문의 대상에 늦은 성공 callback이 오면 사실이 뒤집혀 청구 확인·사람 담당(RESULT_REVERSED)으로 다시 열린 대상 한 건만 남는다")
    void reopensNoChargeCaseOnLateSuccess() throws Exception {
        start(CreateBehavior.ACCEPT_PENDING);
        clock.set(EXPIRES_AT);
        orders.expireOverduePayments();
        closeNoCharge();
        String key = transactionKey();
        server.changeStatus(key, "SUCCESS");
        clock.set(LATE);

        lateSuccess("callback");

        assertThat(caseCount()).isEqualTo(1);
        RefundCase refundCase = refundCase().orElseThrow();
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(refundCase.getOwnerReason()).isEqualTo(RefundCase.RESULT_REVERSED);
        assertThat(refundCase.getConcludedAt()).isNull();
        assertThat(refundCase.getNextCheckAt()).isEqualTo(LATE.plus(RefundCase.PERSON_CHECK_DELAY));
        assertOrder("PAYMENT_EXPIRED", 5);
    }

    @Test
    @DisplayName("확정 주문에 늦은 FAILED가 오는 충돌은 돈을 돌려줄 대상이 아니라 환불 확인 대상을 만들지 않고 충돌 이상만 남긴다")
    void ignoresFailureAfterConfirmation() throws Exception {
        start(CreateBehavior.ACCEPT_SUCCESS);
        server.changeStatus(transactionKey(), "FAILED");

        lateSuccess("admin-reconcile");

        assertThat(caseCount()).isZero();
        assertOrder("CONFIRMED", 3);
        assertThat(unresolved("CONFLICTING_RESULT")).isEqualTo(1);
    }

    @ParameterizedTest(name = "앞선 종결={0}")
    @ValueSource(strings = {"payment-failed", "closed-no-charge"})
    @DisplayName("결과 뒤집힘으로 사람 담당이 되면 commit 뒤 handed_to_human counter가 1 늘고 늦은 성공이 다시 와도 더 늘지 않는다")
    void countsReversalHandoffOnce(String prior) throws Exception {
        if (prior.equals("payment-failed")) {
            start(CreateBehavior.ACCEPT_FAILED);
        } else {
            start(CreateBehavior.ACCEPT_PENDING);
            clock.set(EXPIRES_AT);
            orders.expireOverduePayments();
            closeNoCharge();
        }
        server.changeStatus(transactionKey(), "SUCCESS");
        clock.set(LATE);
        double before = handedToHuman();

        lateSuccess("callback");
        lateSuccess("admin-reconcile");

        assertThat(refundCase().orElseThrow().isPersonOpen()).isTrue();
        assertThat(handedToHuman() - before).isEqualTo(1.0);
    }

    @Test
    @DisplayName("확인 중 대상에 늦은 성공이 합류하는 것은 담당 변경이 아니라 handed_to_human counter가 늘지 않는다")
    void doesNotCountJoinIntoTrackingCase() throws Exception {
        start(CreateBehavior.ACCEPT_PENDING);
        clock.set(EXPIRES_AT);
        orders.expireOverduePayments();
        server.changeStatus(transactionKey(), "SUCCESS");
        clock.set(LATE);
        double before = handedToHuman();

        lateSuccess("callback");

        assertThat(refundCase().orElseThrow().getMoneyStatus()).isEqualTo(MoneyStatus.CHECKING);
        assertThat(refundCase().orElseThrow().getOwner()).isEqualTo(Owner.AUTO);
        assertThat(handedToHuman() - before).isZero();
    }

    private double handedToHuman() {
        Counter counter = meterRegistry.find("commerce.payment.refund_outcome").tag("result", "handed_to_human").counter();
        return counter == null ? 0 : counter.count();
    }

    private void start(CreateBehavior behavior) throws Exception {
        server.createBehavior(behavior);
        String body = "{\"paymentAttemptKey\":\"" + UUID.randomUUID() + "\",\"cardType\":\"" + TestPgFixture.CARD_TYPE
            + "\",\"cardNo\":\"" + TestPgFixture.CARD_NO + "\"}";
        mvc.perform(post("/api/v1/orders/{id}/confirm", orderId).header("X-USER-ID", "1")
            .contentType(MediaType.APPLICATION_JSON).content(body));
        assertThat(server.createCalls()).isEqualTo(1);
    }

    private void lateSuccess(String path) throws Exception {
        ResultActions result = switch (path) {
            case "callback" -> mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionKey\":\"" + transactionKey() + "\",\"providerOrderId\":\"" + providerOrderId()
                    + "\",\"status\":\"SUCCESS\"}"));
            case "customer-reconcile" -> mvc.perform(post("/api/v1/orders/{id}/payment/reconcile", orderId)
                .header("X-USER-ID", "1"));
            case "admin-reconcile" -> mvc.perform(post("/api-admin/v1/orders/{id}/payment/reconcile", orderId)
                .with(csrf()).with(user("admin").roles("ADMIN")));
            default -> throw new IllegalArgumentException(path);
        };
        result.andExpect(status().isOk());
        if (!path.equals("callback")) {
            result.andExpect(jsonPath("$.data.status").value(orderStatus()));
        }
    }

    private void closeNoCharge() {
        jdbc.update("update payment_refund_cases set money_status = 'NO_CHARGE', concluded_at = opened_at, next_check_at = null"
            + " where order_id = ?", orderId);
    }

    private Optional<RefundCase> refundCase() {
        return refundCases.findByOrderId(orderId);
    }

    private long caseCount() {
        return jdbc.queryForObject("select count(*) from payment_refund_cases where order_id = ?", Long.class, orderId);
    }

    private String transactionKey() {
        return server.transactions().get(0).transactionKey();
    }

    private String providerOrderId() {
        return String.format("LP-ORD-%010d", orderId);
    }

    private String orderStatus() {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private String paymentProviderStatus() {
        return jdbc.queryForObject("select provider_status from payment_records where order_id = ?", String.class, orderId);
    }

    private long unresolved(String kind) {
        return jdbc.queryForObject("select count(*) from payment_anomalies where order_id = ? and kind = ? and resolved_at is null",
            Long.class, orderId, kind);
    }

    private void assertOrder(String status, int stock) {
        assertThat(orderStatus()).isEqualTo(status);
        assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId)).isEqualTo(stock);
    }
}
