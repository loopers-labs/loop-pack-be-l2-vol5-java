package com.loopers.application.payment;

import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.fixture.PgCommands;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import com.loopers.pgsimulator.PaymentStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class PaymentStoredConsistencyIntegrationTest extends PaymentHttpTestSupport {

    @Autowired private PaymentTransactionService transactions;

    @ParameterizedTest
    @CsvSource({
        "UNKNOWN,point_amount,1,RECONCILE",
        "UNKNOWN,paid_amount,999,RECONCILE",
        "PENDING,point_amount,1,CALLBACK",
        "PENDING,point_amount,NULL,CONFIRM",
        "PENDING,paid_amount,999,RECONCILE",
        "SUCCESS,paid_amount,3999,CONFIRM",
        "SUCCESS,paid_amount,NULL,RECONCILE",
        "FAILED,paid_amount,999,CONFIRM"
    })
    void rejectsStoredAmountsBeforeAnyPgCall(String status, String column, String value, String action) {
        if (status.equals("UNKNOWN")) {
            pg.enqueuePlan(PgOperation.CREATE, external(order), ResponsePlan.closeEarly());
        }
        assertThat(confirm(order).getStatusCode().value()).isEqualTo(202);
        if (status.equals("SUCCESS") || status.equals("FAILED")) {
            pg.setStatus(key(order), PaymentStatus.valueOf(status));
            assertThat(reconcile(order).getStatusCode().value()).isEqualTo(200);
        }
        jdbc.update("UPDATE orders SET " + column + " = ? WHERE id = ?",
            value.equals("NULL") ? null : Long.valueOf(value), order);
        awaitPgIdle();
        var before = databaseState();
        var pgBefore = pg.state();

        var response = switch (action) {
            case "CONFIRM" -> confirm(order);
            case "CALLBACK" -> callback(order, key(order), "SUCCESS");
            default -> reconcile(order);
        };

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(databaseState()).isEqualTo(before);
        assertThat(pg.state().counts()).isEqualTo(pgBefore.counts());
        assertThat(pg.state().transactions()).isEqualTo(pgBefore.transactions());
        noPointEffect();
    }

    @ParameterizedTest
    @EnumSource(ObservationSource.class)
    void rechecksAmountsInsideResultTransaction(ObservationSource source) {
        var begin = transactions.beginConfirmation(user, order, PgCommands.forOrder(order));
        var token = source == ObservationSource.CREATE ? begin.token()
            : transactions.prepareVerification(order, user, null).token();
        jdbc.update("UPDATE orders SET paid_amount = 999 WHERE id = ?", order);
        var before = databaseState();

        assertThatThrownBy(() -> transactions.applyObservation(token,
            new PaymentObservation.TransactionObserved(source, "verified-key", external(order), 4000,
                PgTransactionStatus.SUCCESS)))
            .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR);

        assertThat(databaseState()).isEqualTo(before);
        assertThat(stock(product.getId())).isEqualTo(3);
        assertThat(pg.state().counts().createReceived()).isZero();
        assertThat(pg.state().transactions()).isEmpty();
        noPointEffect();
    }

    private List<List<Map<String, Object>>> databaseState() {
        return List.of(
            jdbc.queryForList("SELECT * FROM orders ORDER BY id"),
            jdbc.queryForList("SELECT * FROM payment ORDER BY id"),
            jdbc.queryForList("SELECT * FROM payment_conflict ORDER BY id"),
            jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            jdbc.queryForList("SELECT * FROM stock_history ORDER BY id"),
            jdbc.queryForList("SELECT * FROM point ORDER BY id"),
            jdbc.queryForList("SELECT * FROM point_history ORDER BY id")
        );
    }
}
