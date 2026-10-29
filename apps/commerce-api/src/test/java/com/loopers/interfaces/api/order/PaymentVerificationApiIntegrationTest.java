package com.loopers.interfaces.api.order;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.order.PaymentReport;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.domain.payment.PaymentRecord;
import com.loopers.infrastructure.payment.TestPgServer;
import com.loopers.infrastructure.payment.TestPgServer.CreateBehavior;
import com.loopers.infrastructure.payment.TestPgServer.LookupBehavior;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentVerificationApiIntegrationTest.FixedClockConfig.class)
class PaymentVerificationApiIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    @Autowired private MockMvc mvc;
    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private OrderApplicationService orders;
    @Autowired private UserJpaRepository users;
    @Autowired private TestPgFixture pg;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    private TestPgServer server;
    private long productId;
    private long orderId;

    @BeforeEach
    void prepare() {
        server = pg.start();
        users.save(new UserJpaEntity(1));
        long brandId = brands.create("브랜드").id().value();
        productId = products.create(brandId, "상품", 1000, 5).id();
        orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 2))).id();
    }

    @AfterEach
    void clean() throws Exception {
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    private void start(CreateBehavior behavior) {
        server.createBehavior(behavior);
        orders.confirm(1, orderId, TestPgFixture.command());
    }

    @Test
    @DisplayName("주문 상세는 확인 필요를 표시하고 본인 수동 재조회만 깨끗한 성공으로 이를 해제한다")
    void customerSeesAndResolvesReview() throws Exception {
        start(CreateBehavior.ACCEPT_PENDING);
        String key = transactionKey();
        server.replaceTransaction(key, providerOrderId(), 999, "SUCCESS");
        orders.reconcilePayment(orderId);
        mvc.perform(get("/api/v1/orders/{id}", orderId).header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.providerStatus").value("NEEDS_REVIEW"));
        server.replaceTransaction(key, providerOrderId(), 2000, "SUCCESS");
        mvc.perform(post("/api/v1/orders/{id}/payment/reconcile", orderId).header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.providerStatus").value("SUCCESS"));
        scene("CONFIRMED", 3, "SUCCESS", 1, 1, 2);
    }

    @Test
    @DisplayName("본인 재조회는 처리 중이면 202와 시도 상태·조회 경로를 반환하고 성공 후에는 200으로 한 번 확정한다")
    void customerReconcilesPendingThenSuccess() throws Exception {
        start(CreateBehavior.ACCEPT_PENDING);
        mvc.perform(post("/api/v1/orders/{id}/payment/reconcile", orderId).header("X-USER-ID", "1"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
            .andExpect(jsonPath("$.data.providerStatus").value("PENDING"))
            .andExpect(jsonPath("$.data.statusUrl").value("/api/v1/orders/" + orderId));
        scene("PAYMENT_PENDING", 3, "PENDING", 1, 1, 1);
        server.changeStatus(transactionKey(), "SUCCESS");
        mvc.perform(post("/api/v1/orders/{id}/payment/reconcile", orderId).header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        scene("CONFIRMED", 3, "SUCCESS", 1, 1, 2);
    }

    @Test
    @DisplayName("고객 재조회는 타인·없는 주문과 식별 누락을 PG 호출 전에 거절한다")
    void customerReconcileProtectsOwnership() throws Exception {
        start(CreateBehavior.ACCEPT_PENDING);
        users.save(new UserJpaEntity(2));
        mvc.perform(post("/api/v1/orders/{id}/payment/reconcile", orderId).header("X-USER-ID", "2"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders/{id}/payment/reconcile", 999999).header("X-USER-ID", "1"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders/{id}/payment/reconcile", orderId))
            .andExpect(status().isUnauthorized());
        scene("PAYMENT_PENDING", 3, "PENDING", 1, 1, 0);
    }

    @Test
    @DisplayName("고객 재조회에서 실제 PG 조회가 실패하면 202와 현재 상태를 반환하고 조회 실패 이상을 남긴다")
    void customerLookupFailureIsPending() throws Exception {
        start(CreateBehavior.ACCEPT_PENDING);
        server.lookupBehavior(LookupBehavior.ERROR_500);
        mvc.perform(post("/api/v1/orders/{id}/payment/reconcile", orderId).header("X-USER-ID", "1"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.statusUrl").value("/api/v1/orders/" + orderId));
        scene("PAYMENT_PENDING", 3, "PENDING", 1, 1, 1);
        anomaly("LOOKUP_FAILED", 1, false);
    }

    @Test
    @DisplayName("과제 형식의 providerOrderId callback은 금액 없이 권위 조회하고 본문 성공만으로 확정하지 않는다")
    void acceptsQuestCallbackContract() throws Exception {
        start(CreateBehavior.ACCEPT_PENDING);
        String body = "{\"providerOrderId\":\"" + providerOrderId() + "\",\"transactionKey\":\""
            + transactionKey() + "\",\"status\":\"SUCCESS\"}";
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        scene("PAYMENT_PENDING", 3, "PENDING", 1, 1, 1);
        server.changeStatus(transactionKey(), "SUCCESS");
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        scene("CONFIRMED", 3, "SUCCESS", 1, 1, 2);
    }

    @Test
    @DisplayName("키 없는 응답 유실 뒤 PG 성공 callback은 조회로 확정하고 재고 추가 차감과 create 재발송이 없다")
    void confirmsLostResponseByCallback() throws Exception {
        start(CreateBehavior.DELAY_AFTER_CREATE);
        server.changeStatus(transactionKey(), "SUCCESS");
        callback("SUCCESS").andExpect(status().isOk());
        scene("CONFIRMED", 3, "SUCCESS", 1, 1, 1);
        assertThat(anomalyCount()).isZero();
    }

    @Test
    @DisplayName("저장 키가 없어도 조회한 일치 거래 키와 callback 키가 다르면 영구 이상만 남기고 성공을 반영한다")
    void comparesCallbackWithVerifiedKeyWhenStoredKeyMissing() throws Exception {
        start(CreateBehavior.DELAY_AFTER_CREATE);
        server.changeStatus(transactionKey(), "SUCCESS");

        callback("FORGED-KEY", "SUCCESS", providerOrderId()).andExpect(status().isOk());

        scene("CONFIRMED", 3, "SUCCESS", 1, 1, 1);
        anomaly("CALLBACK_KEY_MISMATCH", 1, false);
        assertThat(anomalyCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select transaction_key from payment_records where order_id = ?", String.class, orderId))
            .isEqualTo(transactionKey());
    }

    @ParameterizedTest(name = "저장 키 없는 조회 결과={0}")
    @ValueSource(strings = {"FAILED", "EMPTY", "MULTIPLE", "MISMATCH"})
    @DisplayName("저장 키와 검증된 단일 거래가 없으면 callback 키 불일치 이상을 남기지 않는다")
    void doesNotCompareCallbackWithoutReliableKey(String outcome) throws Exception {
        start(CreateBehavior.ERROR_500_WITHOUT_CREATE);
        String expectedKind;
        String expectedStatus;
        int transactions;
        switch (outcome) {
            case "FAILED" -> {
                server.lookupBehavior(LookupBehavior.ERROR_500);
                expectedKind = "LOOKUP_FAILED";
                expectedStatus = "UNKNOWN";
                transactions = 0;
            }
            case "EMPTY" -> {
                expectedKind = "NO_TRANSACTION";
                expectedStatus = "UNKNOWN";
                transactions = 0;
            }
            case "MULTIPLE" -> {
                server.addTransaction(providerOrderId(), 2000, "SUCCESS");
                server.addTransaction(providerOrderId(), 2000, "SUCCESS");
                expectedKind = "DUPLICATE_TRANSACTIONS";
                expectedStatus = "NEEDS_REVIEW";
                transactions = 2;
            }
            case "MISMATCH" -> {
                server.addTransaction(providerOrderId(), 999, "SUCCESS");
                expectedKind = "TRANSACTION_MISMATCH";
                expectedStatus = "NEEDS_REVIEW";
                transactions = 1;
            }
            default -> throw new IllegalArgumentException(outcome);
        }

        callback("UNVERIFIED-KEY", "SUCCESS", providerOrderId()).andExpect(status().isOk());

        scene("PAYMENT_PENDING", 3, expectedStatus, transactions, 1, 1);
        anomaly(expectedKind, 1, false);
        assertThat(anomalyCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("PG 실패 callback을 두 번 받아도 재고는 한 번 반환하고 조회는 두 번 수행한다")
    void returnsStockOnceForDuplicateFailedCallbacks() throws Exception {
        start(CreateBehavior.ACCEPT_PENDING);
        server.changeStatus(transactionKey(), "FAILED");
        callback("FAILED").andExpect(status().isOk());
        callback("FAILED").andExpect(status().isOk());
        scene("PAYMENT_FAILED", 5, "FAILED", 1, 1, 2);
        assertThat(anomalyCount()).isZero();
    }

    @Test
    @DisplayName("가짜 성공 callback의 상태와 금액은 무시하고 PG가 PENDING이면 결제 대기를 유지한다")
    void ignoresForgedCallbackStatus() throws Exception {
        start(CreateBehavior.ACCEPT_PENDING);
        callback("SUCCESS").andExpect(status().isOk());
        scene("PAYMENT_PENDING", 3, "PENDING", 1, 1, 1);
        assertThat(anomalyCount()).isZero();
    }

    @Test
    @DisplayName("callback 키가 다르면 영구 이상만 기록하고 확인 필요로 바꾸지 않은 채 실제 PG 성공을 반영한다")
    void recordsCallbackKeyMismatchWithoutReview() throws Exception {
        start(CreateBehavior.ACCEPT_PENDING);
        server.changeStatus(transactionKey(), "SUCCESS");
        callback("FORGED-KEY", "SUCCESS", providerOrderId()).andExpect(status().isOk());
        scene("CONFIRMED", 3, "SUCCESS", 1, 1, 1);
        anomaly("CALLBACK_KEY_MISMATCH", 1, false);
    }

    @Test
    @DisplayName("CONFIRMED 주문에 저장 키와 다른 callback 키가 오면 영구 이상 한 행을 남기고 상태·재고를 유지한다")
    void recordsCallbackKeyMismatchForConfirmedOrder() throws Exception {
        start(CreateBehavior.ACCEPT_SUCCESS);

        callback("FORGED-KEY", "SUCCESS", providerOrderId()).andExpect(status().isOk());

        scene("CONFIRMED", 3, "SUCCESS", 1, 1, 1);
        anomaly("CALLBACK_KEY_MISMATCH", 1, false);
        assertThat(anomalyCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select transaction_key from payment_records where order_id = ?", String.class, orderId))
            .isEqualTo(transactionKey());
    }

    @Test
    @DisplayName("거래 0건의 이상은 반복 누적 후 성공하면 해결되며 종결 뒤 0건 조회로 다시 열리지 않는다")
    void accumulatesAndResolvesNoTransaction() throws Exception {
        start(CreateBehavior.ERROR_500_WITHOUT_CREATE);
        reconcile().andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(orderId))
            .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
            .andExpect(jsonPath("$.data.paymentMethod").value("PG"));
        reconcile().andExpect(status().isOk());
        scene("PAYMENT_PENDING", 3, "UNKNOWN", 0, 1, 2);
        anomaly("NO_TRANSACTION", 2, false);
        server.addTransaction(providerOrderId(), 2000, "SUCCESS");
        reconcile().andExpect(status().isOk());
        scene("CONFIRMED", 3, "SUCCESS", 1, 1, 3);
        anomaly("NO_TRANSACTION", 2, true);
        server.removeTransaction(transactionKey());
        reconcile().andExpect(status().isOk());
        scene("CONFIRMED", 3, "SUCCESS", 0, 1, 4);
        anomaly("NO_TRANSACTION", 2, true);
    }

    @Test
    @DisplayName("CONFIRMED 주문의 중복 callback에서 PG 조회가 실패해도 상태·재고를 유지하고 이상을 남기지 않는다")
    void doesNotRecordLookupFailureForConfirmedOrder() throws Exception {
        start(CreateBehavior.ACCEPT_SUCCESS);
        server.lookupBehavior(LookupBehavior.ERROR_500);

        callback("SUCCESS").andExpect(status().isOk());
        callback("SUCCESS").andExpect(status().isOk());

        scene("CONFIRMED", 3, "SUCCESS", 1, 1, 2);
        assertThat(anomalyCount()).isZero();
    }

    @Test
    @DisplayName("결제 대기 주문에 해결된 거래 없음 이상이 다시 생기면 같은 행의 횟수를 늘리고 해결 시각을 비운다")
    void reopensResolvedAnomalyOnlyWhilePending() throws Exception {
        start(CreateBehavior.ERROR_500_WITHOUT_CREATE);
        reconcile().andExpect(status().isOk());
        jdbc.update("update payment_anomalies set resolved_at = first_seen_at where order_id = ? and kind = 'NO_TRANSACTION'",
            orderId);
        anomaly("NO_TRANSACTION", 1, true);

        reconcile().andExpect(status().isOk());

        scene("PAYMENT_PENDING", 3, "UNKNOWN", 0, 1, 2);
        anomaly("NO_TRANSACTION", 2, false);
        assertThat(anomalyCount()).isEqualTo(1);
    }

    @ParameterizedTest(name = "조회 실패={0}")
    @EnumSource(value = LookupBehavior.class, names = {"ERROR_500", "DELAY"})
    @DisplayName("PG 조회가 실패하면 callback은 200이고 결제 대기와 상태를 유지하며 일시적 이상을 남긴다")
    void recordsLookupFailure(LookupBehavior behavior) throws Exception {
        start(CreateBehavior.ACCEPT_PENDING);
        server.lookupBehavior(behavior);
        callback("SUCCESS").andExpect(status().isOk());
        scene("PAYMENT_PENDING", 3, "PENDING", 1, 1, 1);
        anomaly("LOOKUP_FAILED", 1, false);
    }

    @Test
    @DisplayName("중복 거래는 확인 필요로 격리하고 callback 조회를 막으며 수동 조회도 중복이면 유지한다")
    void quarantinesDuplicateTransactions() throws Exception {
        start(CreateBehavior.ACCEPT_PENDING);
        server.addTransaction(providerOrderId(), 2000, "SUCCESS");
        callback("SUCCESS").andExpect(status().isOk());
        scene("PAYMENT_PENDING", 3, "NEEDS_REVIEW", 2, 1, 1);
        anomaly("DUPLICATE_TRANSACTIONS", 1, false);
        callback("SUCCESS").andExpect(status().isOk());
        scene("PAYMENT_PENDING", 3, "NEEDS_REVIEW", 2, 1, 1);
        reconcile().andExpect(status().isOk());
        scene("PAYMENT_PENDING", 3, "NEEDS_REVIEW", 2, 1, 2);
        anomaly("DUPLICATE_TRANSACTIONS", 2, false);
    }

    @Test
    @DisplayName("금액 불일치 뒤 조회 실패와 PENDING은 확인 필요를 지우지 않고 수동 확정 결과만 해제한다")
    void preservesReviewUntilCleanManualFinalResult() throws Exception {
        start(CreateBehavior.ERROR_500_WITHOUT_CREATE);
        String key = server.addTransaction(providerOrderId(), 999, "SUCCESS");
        reconcile().andExpect(status().isOk());
        scene("PAYMENT_PENDING", 3, "NEEDS_REVIEW", 1, 1, 1);
        anomaly("TRANSACTION_MISMATCH", 1, false);
        server.lookupBehavior(LookupBehavior.ERROR_500);
        reconcile().andExpect(status().isOk());
        scene("PAYMENT_PENDING", 3, "NEEDS_REVIEW", 1, 1, 2);
        anomaly("LOOKUP_FAILED", 1, false);
        server.lookupBehavior(LookupBehavior.NORMAL);
        server.replaceTransaction(key, providerOrderId(), 2000, "PENDING");
        reconcile().andExpect(status().isOk());
        scene("PAYMENT_PENDING", 3, "NEEDS_REVIEW", 1, 1, 3);
        server.changeStatus(key, "SUCCESS");
        reconcile().andExpect(status().isOk());
        scene("CONFIRMED", 3, "SUCCESS", 1, 1, 4);
        anomaly("TRANSACTION_MISMATCH", 1, false);
        anomaly("LOOKUP_FAILED", 1, true);
    }

    @ParameterizedTest(name = "불일치 값={0}")
    @ValueSource(strings = {"amount", "orderId", "transactionKey"})
    @DisplayName("거래 한 건의 주문 ID·금액·저장 키가 다르면 확인 필요로 격리하고 기존 키를 보존한다")
    void quarantinesEachMismatch(String field) throws Exception {
        start(CreateBehavior.ACCEPT_PENDING);
        String savedKey = transactionKey();
        switch (field) {
            case "amount" -> server.replaceTransaction(savedKey, providerOrderId(), 999, "SUCCESS");
            case "orderId" -> server.lookupBehavior(LookupBehavior.WRONG_ORDER_ID);
            case "transactionKey" -> {
                savedKey = "TR-STORED-OTHER";
                jdbc.update("update payment_records set transaction_key = ? where order_id = ?", savedKey, orderId);
            }
            default -> throw new IllegalArgumentException(field);
        }
        reconcile().andExpect(status().isOk());
        scene("PAYMENT_PENDING", 3, "NEEDS_REVIEW", 1, 1, 1);
        anomaly("TRANSACTION_MISMATCH", 1, false);
        assertThat(jdbc.queryForObject("select transaction_key from payment_records where order_id = ?", String.class, orderId))
            .isEqualTo(savedKey);
    }

    @Test
    @DisplayName("종결 주문의 반대 결과와 DRAFT 결과는 상태와 재고를 유지하고 영구 이상을 남긴다")
    void recordsConflictAndDraftResult() throws Exception {
        start(CreateBehavior.ACCEPT_SUCCESS);
        server.changeStatus(transactionKey(), "FAILED");
        reconcile().andExpect(status().isOk());
        scene("CONFIRMED", 3, "SUCCESS", 1, 1, 1);
        anomaly("CONFLICTING_RESULT", 1, false);
        long draft = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
        orders.applyPaymentResult(draft, new PaymentReport(PaymentRecord.ProviderStatus.SUCCESS, "TR-DRAFT"));
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, draft)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select occurrences from payment_anomalies where order_id = ? and kind = 'RESULT_BEFORE_PAYMENT'",
            Long.class, draft)).isEqualTo(1);
    }

    @Test
    @DisplayName("모르는 callback은 200이고 이상을 쓰지 않으며 형식 오류는 400이다")
    void ignoresUnknownAndRejectsMalformedCallback() throws Exception {
        callback("TR-X", "SUCCESS", "UNKNOWN-ORDER").andExpect(status().isOk());
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        assertThat(anomalyCount()).isZero();
        assertThat(server.createCalls()).isZero();
        assertThat(server.lookupCalls()).isZero();
    }

    @Test
    @DisplayName("관리자 아닌 요청은 유효한 CSRF로도 403이며 관리자 조회에서 없는 주문은 404이고 PG 아닌 주문은 409다")
    void enforcesAdminBoundaryAndErrors() throws Exception {
        mvc.perform(post(reconcilePath()).with(csrf()).with(user("customer").roles("USER")))
            .andExpect(status().isForbidden());
        mvc.perform(post(reconcilePath()).with(csrf())).andExpect(status().isForbidden());
        reconcile().andExpect(status().isConflict());
        mvc.perform(post("/api-admin/v1/orders/999999/payment/reconcile").with(csrf()).with(user("admin").roles("ADMIN")))
            .andExpect(status().isNotFound());
        assertThat(anomalyCount()).isZero();
        assertThat(server.lookupCalls()).isZero();
        assertThat(server.createCalls()).isZero();
    }

    @ParameterizedTest(name = "먼저 실행할 경로 callback={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("callback과 reconcile 순서가 달라도 한 번 실패하고 재고 반환 결과는 같다")
    void acceptsEitherOrder(boolean callbackFirst) throws Exception {
        start(CreateBehavior.ACCEPT_PENDING);
        server.changeStatus(transactionKey(), "FAILED");
        if (callbackFirst) {
            callback("FAILED").andExpect(status().isOk());
            reconcile().andExpect(status().isOk());
        } else {
            reconcile().andExpect(status().isOk());
            callback("FAILED").andExpect(status().isOk());
        }
        scene("PAYMENT_FAILED", 5, "FAILED", 1, 1, 2);
        assertThat(anomalyCount()).isZero();
    }

    @Test
    @DisplayName("callback과 reconcile 동시 요청은 둘 다 성공하고 최종 실패 전이와 재고 반환은 한 번이다")
    void handlesConcurrentCallbackAndReconcile() throws Exception {
        start(CreateBehavior.ACCEPT_PENDING);
        server.changeStatus(transactionKey(), "FAILED");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<Integer> callback = () -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("시작 대기 초과");
                }
                return callback("FAILED").andReturn().getResponse().getStatus();
            };
            Callable<Integer> reconcile = () -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("시작 대기 초과");
                }
                return reconcile().andReturn().getResponse().getStatus();
            };
            List<Future<Integer>> futures = List.of(executor.submit(callback), executor.submit(reconcile));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            int success = 0;
            int rejected = 0;
            int technical = 0;
            List<Throwable> technicalFailures = new ArrayList<>();
            for (Future<Integer> future : futures) {
                try {
                    int response = future.get(10, TimeUnit.SECONDS);
                    if (response == 200) {
                        success++;
                    } else if (response >= 400 && response < 500) {
                        rejected++;
                    } else {
                        technical++;
                    }
                } catch (ExecutionException e) {
                    technical++;
                    technicalFailures.add(e.getCause());
                } catch (TimeoutException e) {
                    technical++;
                    technicalFailures.add(e);
                }
            }
            assertThat(success + rejected + technical).isEqualTo(2);
            assertThat(success).isEqualTo(2);
            assertThat(rejected).isZero();
            assertThat(technical).as("기술 오류: %s", technicalFailures).isZero();
            scene("PAYMENT_FAILED", 5, "FAILED", 1, 1, 2);
            assertThat(anomalyCount()).isZero();
        } finally {
            go.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String providerOrderId() {
        return String.format("LP-ORD-%010d", orderId);
    }

    private String transactionKey() {
        return server.transactions().get(0).transactionKey();
    }

    private ResultActions callback(String status) throws Exception {
        return callback(transactionKey(), status, providerOrderId());
    }

    private ResultActions callback(String key, String status, String order) throws Exception {
        return mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
            .content("{\"transactionKey\":\"" + key + "\",\"orderId\":\"" + order + "\",\"amount\":1,\"status\":\"" + status + "\"}"));
    }

    private String reconcilePath() {
        return "/api-admin/v1/orders/" + orderId + "/payment/reconcile";
    }

    private ResultActions reconcile() throws Exception {
        return mvc.perform(post(reconcilePath()).with(csrf()).with(user("admin").roles("ADMIN")));
    }

    private void scene(String status, int stock, String providerStatus, int transactions, int creates, int lookups) {
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, orderId)).isEqualTo(status);
        assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId)).isEqualTo(stock);
        assertThat(jdbc.queryForMap("select provider_id, provider_status from payment_records where order_id = ?", orderId))
            .containsEntry("provider_id", "test-pg").containsEntry("provider_status", providerStatus);
        assertThat(server.transactions()).hasSize(transactions);
        assertThat(server.createCalls()).isEqualTo(creates);
        assertThat(server.lookupCalls()).isEqualTo(lookups);
    }

    private long anomalyCount() {
        return jdbc.queryForObject("select count(*) from payment_anomalies", Long.class);
    }

    private void anomaly(String kind, long occurrences, boolean resolved) {
        assertThat(jdbc.queryForObject("select count(*) from payment_anomalies where order_id = ? and kind = ?", Long.class,
            orderId, kind)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select occurrences from payment_anomalies where order_id = ? and kind = ?", Long.class,
            orderId, kind)).isEqualTo(occurrences);
        assertThat(jdbc.queryForObject("select resolved_at is not null from payment_anomalies where order_id = ? and kind = ?",
            Boolean.class, orderId, kind)).isEqualTo(resolved);
        Instant firstSeen = jdbc.queryForObject("select first_seen_at from payment_anomalies where order_id = ? and kind = ?",
            (result, row) -> result.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant(), orderId, kind);
        assertThat(firstSeen).isEqualTo(NOW);
        Instant lastSeen = jdbc.queryForObject("select last_seen_at from payment_anomalies where order_id = ? and kind = ?",
            (result, row) -> result.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant(), orderId, kind);
        assertThat(lastSeen).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("select last_summary from payment_anomalies where order_id = ? and kind = ?", String.class,
            orderId, kind)).doesNotContain(TestPgFixture.CARD_NO);
        if (resolved) {
            Instant resolvedAt = jdbc.queryForObject("select resolved_at from payment_anomalies where order_id = ? and kind = ?",
                (result, row) -> result.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant(), orderId, kind);
            assertThat(resolvedAt).isEqualTo(NOW);
        }
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
