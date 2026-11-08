package com.loopers.application.payment;

import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentCheckToken;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.fixture.SqlCapture;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 설계 4.5 「업무 관찰 순서의 TX 경계 검증」 표. 실제 TX 메서드를 프록시로 부르고 각 단계를 commit 한 순서로 늦은 관찰·반영 순서를 재현한다.
 * 준비는 정상 beginConfirmation(CREATE 토큰)이고 QUERY 토큰은 실제 prepareVerification 으로 얻는다. 사례마다 고유 주문을 쓴다.
 */
@SpringBootTest(properties = SqlCapture.PROPERTY)
@Import(PaymentTxTestConfig.class)
@DisplayName("관찰의 적용 순서가 바뀌어도 종결을 보호하고, 확인 정보·충돌 요약은 마지막 commit 관찰이 된다.")
class PaymentObservationOrderIntegrationTest {

    private static final Long USER = 1L;
    private static final String KA = "tx-KA";
    private static final String KB = "tx-KB";

    @Autowired
    private PaymentTransactionService service;
    @Autowired
    private PaymentTxFixture fixture;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private ProductModel product;
    private Long orderId;
    private PaymentCheckToken createToken;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /** 2,000원 주문을 PAYMENT_PENDING·UNKNOWN·재고 확보 상태로 만들고 CREATE 토큰을 받는다. */
    private void beginOrder() {
        product = fixture.product(1_000L, 10L);
        orderId = fixture.draftOrder(USER, 2L, product);
        createToken = service.beginConfirmation(USER, orderId, PaymentTxFixture.command(PaymentTxFixture.KEY_A)).token();
    }

    private PaymentCheckToken query() {
        return service.prepareVerification(orderId, USER, null).token();
    }

    private PaymentObservation.TransactionObserved tx(ObservationSource source, String key, long amount, PgTransactionStatus status) {
        return new PaymentObservation.TransactionObserved(source, key, PaymentModel.providerOrderIdOf(orderId), amount, status);
    }

    private PaymentApplyResult apply(PaymentCheckToken token, PaymentObservation observation) {
        return service.applyObservation(token, observation);
    }

    /** 저장 키 KA 로 종결을 commit 한다. */
    private void terminateWithKa(PgTransactionStatus status) {
        apply(query(), tx(ObservationSource.QUERY, KA, 2_000L, status));
    }

    @DisplayName("종결 뒤 같은 거래의 늦은 CREATE PENDING 은 종결·확인 정보를 그대로 두고 재고·이력·요약 효과가 없다.")
    @ParameterizedTest
    @EnumSource(value = PgTransactionStatus.class, names = {"SUCCESS", "FAILED"})
    void lateCreatePendingAfterTerminal(PgTransactionStatus terminal) {
        beginOrder();
        terminateWithKa(terminal);
        Map<String, Object> paymentAfterTerminal = fixture.payment(orderId);
        long stockAfterTerminal = fixture.stock(product.getId());

        PaymentApplyResult result = apply(createToken, tx(ObservationSource.CREATE, KA, 2_000L, PgTransactionStatus.PENDING));

        assertAll(
            () -> assertThat(result.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
            () -> assertThat(fixture.payment(orderId)).isEqualTo(paymentAfterTerminal),
            () -> assertThat(fixture.stock(product.getId())).isEqualTo(stockAfterTerminal),
            () -> assertThat(fixture.histories(orderId, "ORDER_RELEASE")).isEqualTo(terminal == PgTransactionStatus.FAILED ? 1L : 0L),
            () -> assertThat(fixture.conflicts(orderId)).isEmpty()
        );
    }

    @DisplayName("종결 뒤 다른 거래 KB 의 CREATE PENDING 은 IDENTITY_MISMATCH 요약이고 종결·KA·재고를 보존하며 KB 를 결합하지 않는다.")
    @Test
    void otherTransactionCreatePendingAfterTerminal() {
        beginOrder();
        terminateWithKa(PgTransactionStatus.SUCCESS);

        PaymentApplyResult result = apply(createToken, tx(ObservationSource.CREATE, KB, 2_000L, PgTransactionStatus.PENDING));

        assertAll(
            () -> assertThat(result.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(fixture.conflicts(orderId)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("code", "IDENTITY_MISMATCH").containsEntry("observed_transaction_key", KB)),
            () -> assertThat(fixture.payment(orderId)).containsEntry("status", "SUCCESS").containsEntry("transaction_key", KA)
                .containsEntry("verification_status", "CONFLICT"),
            () -> assertThat(fixture.order(orderId)).containsEntry("status", "CONFIRMED"),
            () -> assertThat(fixture.stock(product.getId())).isEqualTo(8L)
        );
    }

    @DisplayName("종결 뒤 늦은 CREATE 확인 불가는 거래 확인 없이 종결·확인 정보를 보존하고 후퇴하지 않는다.")
    @Test
    void lateCreateUnavailableAfterTerminal() {
        beginOrder();
        terminateWithKa(PgTransactionStatus.SUCCESS);
        Map<String, Object> paymentAfterTerminal = fixture.payment(orderId);

        PaymentApplyResult result = apply(createToken, new PaymentObservation.Unavailable(ObservationSource.CREATE));

        assertAll(
            () -> assertThat(result.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
            () -> assertThat(fixture.payment(orderId)).isEqualTo(paymentAfterTerminal),
            () -> assertThat(fixture.conflicts(orderId)).isEmpty()
        );
    }

    @DisplayName("종결 전에 준비한 QUERY 토큰의 PENDING 은 무시하고, 종결 뒤 준비한 토큰의 PENDING 은 TERMINAL_REGRESSION 이다.")
    @Test
    void queryPendingByPreparation() {
        beginOrder();
        PaymentCheckToken preparedBefore = query();
        terminateWithKa(PgTransactionStatus.SUCCESS);
        Map<String, Object> paymentAfterTerminal = fixture.payment(orderId);

        PaymentApplyResult ignored = apply(preparedBefore, tx(ObservationSource.QUERY, KA, 2_000L, PgTransactionStatus.PENDING));
        Map<String, Object> paymentAfterIgnored = fixture.payment(orderId);
        PaymentCheckToken preparedAfter = query();
        PaymentApplyResult regression = apply(preparedAfter, tx(ObservationSource.QUERY, KA, 2_000L, PgTransactionStatus.PENDING));

        assertAll(
            () -> assertThat(preparedBefore.terminalAtStart()).isFalse(),
            () -> assertThat(preparedAfter.terminalAtStart()).isTrue(),
            () -> assertThat(ignored.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
            () -> assertThat(paymentAfterIgnored).isEqualTo(paymentAfterTerminal),
            () -> assertThat(regression.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(fixture.conflicts(orderId)).singleElement().satisfies(row ->
                assertThat(row).containsEntry("code", "TERMINAL_REGRESSION")),
            () -> assertThat(fixture.payment(orderId)).containsEntry("status", "SUCCESS").containsEntry("verification_status", "CONFLICT"),
            () -> assertThat(fixture.stock(product.getId())).isEqualTo(8L)
        );
    }

    @DisplayName("확인 결과의 반영 순서가 바뀌면 마지막 commit 관찰이 확인 정보가 되고 업무 상태·재고는 그대로다.")
    @Test
    void verificationOrderReversed() {
        beginOrder();
        apply(createToken, tx(ObservationSource.CREATE, KA, 2_000L, PgTransactionStatus.PENDING));
        PaymentCheckToken a = query();
        PaymentCheckToken b = query();

        apply(b, new PaymentObservation.Unavailable(ObservationSource.QUERY));
        apply(a, tx(ObservationSource.QUERY, KA, 2_000L, PgTransactionStatus.PENDING));
        Object afterALast = fixture.payment(orderId).get("verification_status");
        apply(a, tx(ObservationSource.QUERY, KA, 2_000L, PgTransactionStatus.PENDING));
        apply(b, new PaymentObservation.Unavailable(ObservationSource.QUERY));

        assertAll(
            () -> assertThat(afterALast).isEqualTo("VERIFIED"),
            () -> assertThat(fixture.payment(orderId)).containsEntry("verification_status", "UNAVAILABLE").containsEntry("status", "PENDING"),
            () -> assertThat(fixture.order(orderId)).containsEntry("status", "PAYMENT_PENDING"),
            () -> assertThat(fixture.stock(product.getId())).isEqualTo(8L)
        );
    }

    @DisplayName("충돌 요약은 시작 순서와 무관하게 마지막 commit 충돌로 교체되는 한 행이다.")
    @Test
    void conflictOrderReversed() {
        beginOrder();
        apply(createToken, tx(ObservationSource.CREATE, KA, 2_000L, PgTransactionStatus.PENDING));
        PaymentCheckToken a = query();
        PaymentCheckToken b = query();

        apply(b, tx(ObservationSource.QUERY, KB, 2_000L, PgTransactionStatus.PENDING));
        apply(a, tx(ObservationSource.QUERY, KA, 1_999L, PgTransactionStatus.PENDING));

        assertAll(
            () -> assertThat(fixture.conflicts(orderId)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("code", "AMOUNT_MISMATCH").containsEntry("observed_amount", 1_999L)),
            () -> assertThat(fixture.order(orderId)).containsEntry("status", "PAYMENT_PENDING"),
            () -> assertThat(fixture.stock(product.getId())).isEqualTo(8L)
        );
    }

    @DisplayName("SUCCESS 뒤 같은 KA 의 반대 FAILED 는 TERMINAL_MISMATCH·반환 0 이고, 같은 SUCCESS 재적용은 효과가 없다.")
    @Test
    void oppositeAndSameTerminal() {
        beginOrder();
        terminateWithKa(PgTransactionStatus.SUCCESS);

        PaymentApplyResult opposite = apply(query(), tx(ObservationSource.QUERY, KA, 2_000L, PgTransactionStatus.FAILED));
        PaymentApplyResult same = apply(query(), tx(ObservationSource.QUERY, KA, 2_000L, PgTransactionStatus.SUCCESS));

        assertAll(
            () -> assertThat(opposite.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(fixture.conflicts(orderId)).singleElement().satisfies(row ->
                assertThat(row).containsEntry("code", "TERMINAL_MISMATCH")),
            () -> assertThat(same.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
            () -> assertThat(fixture.order(orderId)).containsEntry("status", "CONFIRMED"),
            () -> assertThat(fixture.histories(orderId, "ORDER_RELEASE")).isZero(),
            () -> assertThat(fixture.stock(product.getId())).isEqualTo(8L)
        );
    }

    @DisplayName("미종결 PENDING(KA) 에 같은 주문·금액의 KB(PENDING·SUCCESS) 는 IDENTITY_MISMATCH 이고 KA·상태·재고를 보존한다.")
    @ParameterizedTest
    @EnumSource(value = PgTransactionStatus.class, names = {"PENDING", "SUCCESS"})
    void otherTransactionBeforeTerminal(PgTransactionStatus status) {
        beginOrder();
        apply(createToken, tx(ObservationSource.CREATE, KA, 2_000L, PgTransactionStatus.PENDING));

        PaymentApplyResult result = apply(query(), tx(ObservationSource.QUERY, KB, 2_000L, status));

        assertAll(
            () -> assertThat(result.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(fixture.conflicts(orderId)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("code", "IDENTITY_MISMATCH").containsEntry("observed_transaction_key", KB)),
            () -> assertThat(fixture.payment(orderId)).containsEntry("status", "PENDING").containsEntry("transaction_key", KA)
                .containsEntry("verification_status", "CONFLICT"),
            () -> assertThat(fixture.order(orderId)).containsEntry("status", "PAYMENT_PENDING"),
            () -> assertThat(fixture.stock(product.getId())).isEqualTo(8L)
        );
    }

    @DisplayName("저장 키·금액이 함께 다르면 AMOUNT_MISMATCH 가 아닌 IDENTITY_MISMATCH 이고 관찰 금액을 보존한다.")
    @Test
    void keyAndAmountMismatch() {
        beginOrder();
        apply(createToken, tx(ObservationSource.CREATE, KA, 2_000L, PgTransactionStatus.PENDING));

        PaymentApplyResult result = apply(query(), tx(ObservationSource.QUERY, KB, 1L, PgTransactionStatus.PENDING));

        assertAll(
            () -> assertThat(result.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(fixture.conflicts(orderId)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("code", "IDENTITY_MISMATCH").containsEntry("observed_transaction_key", KB)
                .containsEntry("observed_amount", 1L)),
            () -> assertThat(fixture.payment(orderId)).containsEntry("transaction_key", KA)
        );
    }

    @DisplayName("식별이 같고 금액만 다르면 AMOUNT_MISMATCH 이고 관찰 금액을 보존한다.")
    @Test
    void amountOnlyMismatch() {
        beginOrder();
        apply(createToken, tx(ObservationSource.CREATE, KA, 2_000L, PgTransactionStatus.PENDING));

        PaymentApplyResult result = apply(query(), tx(ObservationSource.QUERY, KA, 2_001L, PgTransactionStatus.PENDING));

        assertAll(
            () -> assertThat(result.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(fixture.conflicts(orderId)).singleElement().satisfies(row ->
                assertThat(row).containsEntry("code", "AMOUNT_MISMATCH").containsEntry("observed_amount", 2_001L)),
            () -> assertThat(fixture.payment(orderId)).containsEntry("status", "PENDING").containsEntry("transaction_key", KA)
        );
    }

    @DisplayName("SUCCESS(KA) 뒤 새 QUERY 토큰의 KB·FAILED 는 TERMINAL_MISMATCH 가 아닌 IDENTITY_MISMATCH 이고 반환하지 않는다.")
    @Test
    void otherTransactionAfterTerminal() {
        beginOrder();
        terminateWithKa(PgTransactionStatus.SUCCESS);
        PaymentCheckToken after = query();

        PaymentApplyResult result = apply(after, tx(ObservationSource.QUERY, KB, 2_000L, PgTransactionStatus.FAILED));

        assertAll(
            () -> assertThat(after.terminalAtStart()).isTrue(),
            () -> assertThat(result.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(fixture.conflicts(orderId)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("code", "IDENTITY_MISMATCH").containsEntry("observed_transaction_key", KB)),
            () -> assertThat(fixture.payment(orderId)).containsEntry("status", "SUCCESS").containsEntry("transaction_key", KA),
            () -> assertThat(fixture.order(orderId)).containsEntry("status", "CONFIRMED"),
            () -> assertThat(fixture.histories(orderId, "ORDER_RELEASE")).isZero(),
            () -> assertThat(fixture.stock(product.getId())).isEqualTo(8L)
        );
    }
}
