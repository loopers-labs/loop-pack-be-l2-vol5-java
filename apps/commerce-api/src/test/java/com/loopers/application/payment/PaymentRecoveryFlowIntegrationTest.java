package com.loopers.application.payment;

import com.loopers.fixture.pg.PaymentStatus;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Timeout(10)
class PaymentRecoveryFlowIntegrationTest extends PaymentHttpTestSupport {
    @Autowired private DataSource dataSource;
    @Autowired private com.loopers.application.product.ProductFacade productFacade;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void lostCreateResponseConvergesWithCallbackFirstOrLookupFirst(boolean callbackFirst) throws Exception {
        pg.enqueuePlan(PgOperation.CREATE, external(order), ResponsePlan.closeEarly());
        assertThat(confirm(order).getStatusCode().value()).isEqualTo(202);
        assertThat(payment(order)).containsEntry("status", "UNKNOWN").containsEntry("transaction_key", null);
        String key = pg.state().transactions().getFirst().transactionKey();
        pg.setStatus(key, PaymentStatus.SUCCESS);
        if (callbackFirst) {
            assertAck(key, 200);
            assertThat(reconcile(order).getStatusCode().value()).isEqualTo(200);
        } else {
            assertThat(reconcile(order).getStatusCode().value()).isEqualTo(200);
            assertAck(key, 200);
        }
        assertAck(key, 200);
        assertThat(detail(order).path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(detail(order).path("paidAmount").asLong()).isEqualTo(4000);
        assertThat(key(order)).isEqualTo(key);
        assertThat(stock(product.getId())).isEqualTo(3);
        assertThat(histories(order, "ORDER_RESERVATION")).isEqualTo(1);
        assertThat(histories(order, "ORDER_RELEASE")).isZero();
        assertThat(pg.state().counts().createReceived()).isEqualTo(1);
        noPointEffect();
    }

    private void assertAck(String key, int expected) throws Exception {
        var batch = pg.sendCallbacks(key, 1).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(batch.deliveries()).singleElement().satisfies(delivery -> {
            assertThat(delivery.failure()).isNull();
            assertThat(delivery.httpStatus()).isEqualTo(expected);
        });
    }

    @ParameterizedTest
    @CsvSource({"false,4000,SUCCESS", "true,4000,FAILED", "false,5000,SUCCESS"})
    void validOtherKeyOfSameOrderConflictsBeforeAmountOrTerminal(boolean terminal, long amount, String status) throws Exception {
        assertThat(confirm(order).getStatusCode().value()).isEqualTo(202);
        String original = key(order);
        if (terminal) {
            pg.setStatus(original, PaymentStatus.SUCCESS);
            assertAck(original, 200);
        }
        String other = directPgCreate(order, amount);
        pg.setStatus(other, PaymentStatus.valueOf(status));
        int successBefore = circuits.circuitBreaker("pgLookup").getMetrics().getNumberOfSuccessfulCalls();
        long lookupsBefore = pg.state().counts().lookupByKeyReceived();
        long createsBefore = pg.state().counts().createReceived();
        assertAck(other, 409);
        assertThat(jdbc.queryForMap("SELECT code, observed_transaction_key, observed_amount FROM payment_conflict"))
            .containsEntry("code", "IDENTITY_MISMATCH").containsEntry("observed_transaction_key", other)
            .containsEntry("observed_amount", amount);
        assertThat(payment(order)).containsEntry("transaction_key", original).containsEntry("verification_status", "CONFLICT");
        assertThat(detail(order).path("status").asText()).isEqualTo(terminal ? "CONFIRMED" : "PAYMENT_PENDING");
        assertThat(circuits.circuitBreaker("pgLookup").getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(successBefore + 1);
        assertThat(pg.state().counts().lookupByKeyReceived()).isEqualTo(lookupsBefore + 1);
        assertThat(pg.state().counts().createReceived()).isEqualTo(createsBefore);
        assertThat(stock(product.getId())).isEqualTo(3);
        assertThat(histories(order, "ORDER_RELEASE")).isZero();
        noPointEffect();
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "B", "C"})
    void forgedCallbackHasExactlyOneAuthoritativeReadAndNoExtraEffects(String scenario) {
        confirm(order);
        String original = key(order);
        String target = original;
        Long otherOrder = null;
        Map<String, Object> otherBefore = null;
        if (scenario.equals("B")) {
            target = "missing-transaction-key";
        } else if (scenario.equals("C")) {
            otherOrder = draft(products.createProduct("별도 상품", 2000L, 5L));
            confirm(otherOrder);
            target = key(otherOrder);
            pg.setStatus(target, PaymentStatus.SUCCESS);
            otherBefore = payment(otherOrder);
        }
        var before = pg.state();
        var prior = payment(order);
        int successful = circuits.circuitBreaker("pgLookup").getMetrics().getNumberOfSuccessfulCalls();
        int createSamples = circuits.circuitBreaker("pgCreate").getMetrics().getNumberOfBufferedCalls();
        var response = callback(order, target, "SUCCESS");
        assertThat(response.getStatusCode().value()).isEqualTo(scenario.equals("C") ? 409 : 202);
        if (!scenario.equals("C")) {
            assertThat(response.getBody().has("data")).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_conflict", Long.class)).isZero();
        } else {
            assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo("PAYMENT_RESULT_CONFLICT");
            assertThat(jdbc.queryForMap("SELECT code, observed_order_id, observed_transaction_key FROM payment_conflict"))
                .containsEntry("code", "IDENTITY_MISMATCH").containsEntry("observed_order_id", external(otherOrder))
                .containsEntry("observed_transaction_key", target);
            assertThat(payment(otherOrder)).isEqualTo(otherBefore);
        }
        assertThat(payment(order)).containsEntry("status", "PENDING").containsEntry("transaction_key", original)
            .containsEntry("verification_status", scenario.equals("A") ? "VERIFIED" : scenario.equals("B") ? "UNAVAILABLE" : "CONFLICT");
        assertThat(payment(order).get("verification_checked_at")).isNotNull().isNotEqualTo(prior.get("verification_checked_at"));
        assertThat(detail(order).path("status").asText()).isEqualTo("PAYMENT_PENDING");
        assertThat(detail(order).has("paidAmount")).isFalse();
        var after = pg.state();
        assertThat(after.counts().lookupByKeyReceived()).isEqualTo(before.counts().lookupByKeyReceived() + 1);
        assertThat(after.counts().lookupByOrderReceived()).isEqualTo(before.counts().lookupByOrderReceived());
        assertThat(after.counts().createReceived()).isEqualTo(before.counts().createReceived());
        assertThat(after.counts().callbackSent()).isEqualTo(before.counts().callbackSent());
        assertThat(after.transactions()).isEqualTo(before.transactions());
        assertThat(circuits.circuitBreaker("pgLookup").getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(successful + 1);
        assertThat(circuits.circuitBreaker("pgLookup").getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuits.circuitBreaker("pgCreate").getMetrics().getNumberOfBufferedCalls()).isEqualTo(createSamples);
        assertThat(stock(product.getId())).isEqualTo(3);
        assertThat(histories(order, "ORDER_RESERVATION")).isEqualTo(1);
        assertThat(histories(order, "ORDER_RELEASE")).isZero();
        noPointEffect();
    }

    @Test
    void concurrentCallbackAndReconcileWaitForCommittedFailureAndReleaseOnce() throws Exception {
        confirm(order);
        String key = key(order);
        pg.setStatus(key, PaymentStatus.FAILED);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", order, () -> {
            reached.countDown();
            awaitLatch(release);
        });
        try {
            var first = async(() -> callback(order, key, "FAILED"));
            assertThat(reached.await(3, TimeUnit.SECONDS)).isTrue();
            var second = async(() -> reconcile(order));
            await().atMost(Duration.ofSeconds(3)).until(() -> lockWait("orders"));
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
            assertThat(second.get(5, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
            assertThat(histories(order, "ORDER_RELEASE")).isEqualTo(1);
            assertThat(stock(product.getId())).isEqualTo(5);
            assertThat(detail(order).path("status").asText()).isEqualTo("PAYMENT_FAILED");
        } finally {
            release.countDown();
        }
    }

    @Test
    void sameExternalTransactionAcrossOrdersRollsBackLoserAndCommitsSeparateConflict() throws Exception {
        Long other = draft(products.createProduct("다른 상품", 2000L, 5L));
        for (Long id : new Long[] {order, other}) {
            pg.enqueuePlan(PgOperation.CREATE, external(id), ResponsePlan.closeEarly());
            assertThat(confirm(id).getStatusCode().value()).isEqualTo(202);
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, "shared-key",
                ResponsePlan.raw(200, transaction("shared-key", external(id), 4000, "SUCCESS"), null));
        }
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", order, () -> {
            reached.countDown();
            awaitLatch(release);
        });
        try {
            var first = async(() -> callback(order, "shared-key", "SUCCESS"));
            assertThat(reached.await(3, TimeUnit.SECONDS)).isTrue();
            var second = async(() -> callback(other, "shared-key", "SUCCESS"));
            await().atMost(Duration.ofSeconds(3)).until(() -> lockWait("payment"));
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
            assertThat(second.get(5, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(409);
            assertThat(detail(order).path("status").asText()).isEqualTo("CONFIRMED");
            assertThat(payment(other)).containsEntry("status", "UNKNOWN").containsEntry("transaction_key", null)
                .containsEntry("verification_status", "CONFLICT");
            assertThat(jdbc.queryForMap("SELECT code, payment_id FROM payment_conflict"))
                .containsEntry("code", "TRANSACTION_ALREADY_BOUND").containsEntry("payment_id", payment(other).get("id"));
            assertThat(histories(other, "ORDER_RELEASE")).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test
    void realPgDelayHoldsNoDbConnectionOrProductLock() throws Exception {
        pg.enqueuePlan(PgOperation.CREATE, external(order), ResponsePlan.delay(3000));
        var confirmation = async(() -> confirm(order));
        await().atMost(Duration.ofMillis(700)).pollInterval(Duration.ofMillis(5))
            .until(() -> pg.state().counts().createReceived() == 1 && !pg.state().transactions().isEmpty());
        assertThat(confirmation.isDone()).isFalse();
        assertThat(dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
        var admin = async(() -> productFacade.changeStock(product.getId(), 10L));
        assertThat(admin.get(400, TimeUnit.MILLISECONDS).getStockQuantity()).isEqualTo(10L);
        assertThat(confirmation.isDone()).isFalse();
        assertThat(confirmation.get(3, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(202);
        assertThat(payment(order)).containsEntry("status", "UNKNOWN").containsEntry("verification_status", "UNAVAILABLE")
            .containsEntry("failure_kind", null);
        assertThat(histories(order, "ORDER_RELEASE")).isZero();
        awaitPgIdle();
        String key = pg.state().transactions().getFirst().transactionKey();
        pg.setStatus(key, PaymentStatus.FAILED);
        assertThat(reconcile(order).getStatusCode().value()).isEqualTo(200);
        assertThat(stock(product.getId())).isEqualTo(12);
        assertThat(histories(order, "ORDER_RELEASE")).isEqualTo(1);
        noPointEffect();
    }

    private boolean lockWait(String table) throws Exception {
        try (var connection = DriverManager.getConnection(System.getProperty("datasource.mysql-jpa.main.jdbc-url"), "root", "test");
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state = 'LOCK WAIT' AND LOWER(trx_query) LIKE ?")) {
            statement.setString(1, "%" + table + "%");
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getLong(1) > 0; }
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).as("TX 장벽 유한 대기").isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPTY", "MULTIPLE", "THREE", "MIXED"})
    void orderLookupKeepsUnavailableOrMultipleConflictWithoutChoosingArbitraryTransaction(String mode) {
        pg.enqueuePlan(PgOperation.CREATE, external(order), ResponsePlan.closeEarly());
        confirm(order);
        if (mode.equals("EMPTY")) {
            pg.enqueuePlan(PgOperation.LOOKUP_ORDER, external(order), ResponsePlan.raw(200, "[]", null));
        } else if (mode.equals("MULTIPLE") || mode.equals("THREE")) {
            directPgCreate(order, 4000);
            if (mode.equals("THREE")) { directPgCreate(order, 4000); }
        } else {
            pg.enqueuePlan(PgOperation.LOOKUP_ORDER, external(order), ResponsePlan.raw(200,
                "[" + transaction("mixed-a", external(order), 4000, "SUCCESS") + ","
                    + transaction("mixed-b", "LP-ORD-9999999999", 4000, "FAILED") + "]", null));
        }
        long creates = pg.state().counts().createReceived();
        assertThat(reconcile(order).getStatusCode().value()).isEqualTo(mode.equals("EMPTY") ? 202 : 409);
        assertThat(payment(order)).containsEntry("status", "UNKNOWN").containsEntry("transaction_key", null)
            .containsEntry("verification_status", mode.equals("EMPTY") ? "UNAVAILABLE" : "CONFLICT");
        assertThat(circuits.circuitBreaker("pgLookup").getMetrics().getNumberOfSuccessfulCalls())
            .isEqualTo(mode.equals("MIXED") ? 0 : 1);
        assertThat(circuits.circuitBreaker("pgLookup").getMetrics().getNumberOfFailedCalls())
            .isEqualTo(mode.equals("MIXED") ? 1 : 0);
        if (mode.equals("EMPTY")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_conflict", Long.class)).isZero();
        } else {
            assertThat(jdbc.queryForMap("SELECT code, observed_count FROM payment_conflict"))
                .containsEntry("code", "MULTIPLE_TRANSACTIONS").containsEntry("observed_count", mode.equals("THREE") ? 3 : 2);
        }
        assertThat(pg.state().counts().lookupByOrderReceived()).isEqualTo(1);
        assertThat(pg.state().counts().createReceived()).isEqualTo(creates);
        assertThat(histories(order, "ORDER_RELEASE")).isZero();
        assertThat(stock(product.getId())).isEqualTo(3);
    }

    @Test
    void conflictSummarySqlFailureReturns500AndKeepsLastCommittedEvidence() {
        confirm(order);
        String key = key(order);
        pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, key,
            ResponsePlan.raw(200, transaction(key, "LP-ORD-9999999999", 4000, "SUCCESS"), null));
        assertThat(reconcile(order).getStatusCode().value()).isEqualTo(409);
        Map<String, Object> previous = jdbc.queryForMap("SELECT * FROM payment_conflict");
        Map<String, Object> previousPayment = payment(order);
        pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, key,
            ResponsePlan.raw(200, transaction(key, external(order), 5000, "SUCCESS"), null));
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", order, () -> {
            assertThat(jdbc.queryForObject("SELECT code FROM payment_conflict", String.class)).isEqualTo("AMOUNT_MISMATCH");
            throw new IllegalStateException("충돌 요약 SQL 후 실패");
        });
        assertThat(reconcile(order).getStatusCode().value()).isEqualTo(500);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_conflict")).isEqualTo(previous);
        assertThat(payment(order)).isEqualTo(previousPayment);
        probe.clear();
        assertThat(reconcile(order).getStatusCode().value()).isEqualTo(202);
        assertThat(payment(order)).containsEntry("verification_status", "VERIFIED");
        assertThat(jdbc.queryForMap("SELECT * FROM payment_conflict")).isEqualTo(previous);
        assertThat(stock(product.getId())).isEqualTo(3);
        assertThat(histories(order, "ORDER_RELEASE")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void lateCallbackAfterImmediateSuccessPreservesTerminalAndReservation(String status) throws Exception {
        success(order);
        assertThat(confirm(order).getStatusCode().value()).isEqualTo(200);
        String key = key(order);
        pg.setStatus(key, PaymentStatus.valueOf(status));
        assertAck(key, status.equals("SUCCESS") ? 200 : 409);
        assertThat(detail(order).path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(detail(order).path("paidAmount").asLong()).isEqualTo(4000);
        assertThat(stock(product.getId())).isEqualTo(3);
        assertThat(histories(order, "ORDER_RESERVATION")).isEqualTo(1);
        assertThat(histories(order, "ORDER_RELEASE")).isZero();
        assertThat(pg.state().counts().createReceived()).isEqualTo(1);
        assertThat(pg.state().counts().lookupByKeyReceived()).isEqualTo(1);
        if (status.equals("FAILED")) {
            assertThat(jdbc.queryForObject("SELECT code FROM payment_conflict", String.class)).isEqualTo("TERMINAL_MISMATCH");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"OVERFLOW", "MISSING_PRODUCT", "SOFT_DELETED"})
    void reservationReturnHandlesCurrentStockDeletionAndOverflowAtomically(String mode) {
        confirm(order);
        pg.setStatus(key(order), PaymentStatus.FAILED);
        if (mode.equals("OVERFLOW")) {
            productFacade.changeStock(product.getId(), Long.MAX_VALUE);
        } else if (mode.equals("MISSING_PRODUCT")) {
            jdbc.update("DELETE FROM product WHERE id = ?", product.getId());
        } else {
            productFacade.delete(product.getId());
        }
        var before = payment(order);
        assertThat(reconcile(order).getStatusCode().value()).isEqualTo(mode.equals("SOFT_DELETED") ? 200 : 500);
        if (mode.equals("SOFT_DELETED")) {
            assertThat(stock(product.getId())).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT deleted_at FROM product WHERE id = ?", Object.class, product.getId())).isNotNull();
            assertThat(histories(order, "ORDER_RELEASE")).isEqualTo(1);
        } else {
            assertThat(payment(order)).isEqualTo(before);
            assertThat(histories(order, "ORDER_RELEASE")).isZero();
            if (mode.equals("OVERFLOW")) { assertThat(stock(product.getId())).isEqualTo(Long.MAX_VALUE); }
        }
        noPointEffect();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING_ITEM", "ZERO_QUANTITY", "AMOUNT_MISMATCH"})
    void invalidStoredItemsAreRejectedBeforeAuthorityLookup(String mode) {
        confirm(order);
        var before = payment(order);
        if (mode.equals("MISSING_ITEM")) {
            jdbc.update("DELETE FROM order_item WHERE order_id = ?", order);
        } else if (mode.equals("ZERO_QUANTITY")) {
            jdbc.update("UPDATE order_item SET quantity = 0 WHERE order_id = ?", order);
        } else {
            jdbc.update("UPDATE order_item SET unit_price = 5000 WHERE order_id = ?", order);
        }
        assertThat(reconcile(order).getStatusCode().value()).isEqualTo(500);
        assertThat(payment(order)).isEqualTo(before);
        assertThat(pg.state().counts().lookupByKeyReceived()).isZero();
        assertThat(histories(order, "ORDER_RELEASE")).isZero();
        assertThat(stock(product.getId())).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void normalPendingSingleCallbackHasExactCountsAndNoPointEffects(String terminal) throws Exception {
        assertThat(confirm(order).getStatusCode().value()).isEqualTo(202);
        String key = key(order);
        assertThat(payment(order)).containsEntry("status", "PENDING").containsEntry("verification_status", "VERIFIED");
        assertThat(stock(product.getId())).isEqualTo(3);
        pg.setStatus(key, PaymentStatus.valueOf(terminal));
        assertThat(pg.state().counts().callbackSent()).isZero();
        assertThat(pg.state().counts().lookupByKeyReceived()).isZero();
        assertThat(payment(order)).containsEntry("status", "PENDING");
        assertAck(key, 200);
        var result = detail(order);
        assertThat(result.path("status").asText()).isEqualTo(terminal.equals("SUCCESS") ? "CONFIRMED" : "PAYMENT_FAILED");
        assertThat(payment(order)).containsEntry("status", terminal).containsEntry("transaction_key", key)
            .containsEntry("verification_status", "VERIFIED");
        assertThat(result.has("paidAmount")).isEqualTo(terminal.equals("SUCCESS"));
        assertThat(stock(product.getId())).isEqualTo(terminal.equals("SUCCESS") ? 3 : 5);
        assertThat(histories(order, "ORDER_RESERVATION")).isEqualTo(1);
        assertThat(histories(order, "ORDER_RELEASE")).isEqualTo(terminal.equals("SUCCESS") ? 0 : 1);
        assertThat(pg.state().counts().createReceived()).isEqualTo(1);
        assertThat(pg.state().counts().lookupByKeyReceived()).isEqualTo(1);
        assertThat(pg.state().counts().lookupByOrderReceived()).isZero();
        assertThat(pg.state().counts().callbackSent()).isEqualTo(1);
        noPointEffect();
    }

    @ParameterizedTest
    @ValueSource(strings = {"KEY", "ORDER", "AMOUNT", "CLOSE_EARLY"})
    void lookupPreservesDirectConflictOrUnavailableAcrossActualController(String mode) {
        if (mode.equals("ORDER")) { pg.enqueuePlan(PgOperation.CREATE, external(order), ResponsePlan.closeEarly()); }
        confirm(order);
        String original = key(order);
        if (mode.equals("ORDER")) {
            pg.enqueuePlan(PgOperation.LOOKUP_ORDER, external(order), ResponsePlan.raw(200,
                "[" + transaction("other-order-key", "LP-ORD-9999999999", 4000, "SUCCESS") + "]", null));
        } else if (mode.equals("CLOSE_EARLY")) {
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, original, ResponsePlan.closeEarly());
        } else {
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, original, ResponsePlan.raw(200,
                transaction(mode.equals("KEY") ? "different-key" : original, external(order),
                    mode.equals("AMOUNT") ? 5000 : 4000, "SUCCESS"), null));
        }
        assertThat(reconcile(order).getStatusCode().value()).isEqualTo(mode.equals("CLOSE_EARLY") ? 202 : 409);
        assertThat(payment(order)).containsEntry("transaction_key", original)
            .containsEntry("verification_status", mode.equals("CLOSE_EARLY") ? "UNAVAILABLE" : "CONFLICT");
        if (!mode.equals("CLOSE_EARLY")) {
            assertThat(jdbc.queryForObject("SELECT code FROM payment_conflict", String.class))
                .isEqualTo(mode.equals("AMOUNT") ? "AMOUNT_MISMATCH" : "IDENTITY_MISMATCH");
        }
        var metrics = circuits.circuitBreaker("pgLookup").getMetrics();
        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(mode.equals("AMOUNT") ? 1 : 0);
        assertThat(metrics.getNumberOfFailedCalls()).isEqualTo(mode.equals("AMOUNT") ? 0 : 1);
        assertThat(pg.state().counts().lookupByOrderReceived() + pg.state().counts().lookupByKeyReceived()).isEqualTo(1);
        assertThat(stock(product.getId())).isEqualTo(3);
        assertThat(histories(order, "ORDER_RELEASE")).isZero();
    }
}
