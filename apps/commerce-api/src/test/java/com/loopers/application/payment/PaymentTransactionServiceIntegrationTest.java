package com.loopers.application.payment;

import com.loopers.application.order.OrderInfo;
import com.loopers.domain.order.OrderEventPublisher;
import com.loopers.domain.payment.ConflictEvidence;
import com.loopers.domain.payment.LocalNotDispatchedReason;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentCheckToken;
import com.loopers.domain.payment.PaymentConflictCode;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.SqlCapture;
import com.loopers.support.error.ErrorType;
import com.loopers.support.persistence.ConstraintViolationClassifier;
import com.loopers.testsupport.RecordingOrderEventPublisher;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * PaymentTransactionService 의 짧은 TX 를 실제 MySQL 에 commit 하며 확인한다(설계 4.3·4.5 PaymentTransactionIntegrationTest).
 * 테스트 부모 TX 없이 프록시로 부르고 결과는 새 연결로 읽는다. PG HTTP 는 없으며 관찰은 adapter 가 만들 수 있는 값으로 준비한다.
 */
@SpringBootTest(properties = SqlCapture.PROPERTY)
@Import(PaymentTxTestConfig.class)
@DisplayName("결제 TX 서비스는 짧은 TX 마다 주문·결제·재고·충돌 요약을 함께 commit 하거나 함께 rollback 한다.")
class PaymentTransactionServiceIntegrationTest {

    @TestBean(name = "kafkaOrderEventPublisher", methodName = RecordingOrderEventPublisher.FACTORY, enforceOverride = true)
    private OrderEventPublisher orderEventPublisher;

    private static final Long USER = 1L;
    private static final Long OTHER_USER = 2L;

    @Autowired
    private PaymentTransactionService service;
    @Autowired
    private PaymentTxFixture fixture;
    @Autowired
    private ControllableProbe probe;
    @Autowired
    private ConstraintViolationClassifier classifier;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        probe.clear();
        databaseCleanUp.truncateAllTables();
    }

    private BeginConfirmationResult begin(Long orderId) {
        return service.beginConfirmation(USER, orderId, PaymentTxFixture.command(PaymentTxFixture.KEY_A));
    }

    private static PaymentObservation.TransactionObserved observed(ObservationSource source, Long orderId, String key,
                                                                   long amount, PgTransactionStatus status) {
        return new PaymentObservation.TransactionObserved(source, key, PaymentModel.providerOrderIdOf(orderId), amount,
            status);
    }

    @DisplayName("결제를 시작할 때,")
    @Nested
    class Begin {

        @DisplayName("사전 준비는 READY 이고 상태를 바꾸지 않으며, TX A 는 결제·선차감·확보 이력·PAYMENT_PENDING 을 함께 commit 한다.")
        @Test
        void sendsAfterReservation() {
            ProductModel p1 = fixture.product(2_000L, 10L);
            ProductModel p2 = fixture.product(3_000L, 5L);
            Long orderId = fixture.draftOrder(USER, 2L, p1, p2);

            PrepareConfirmationResult prepared = service.prepareConfirmation(USER, orderId,
                PaymentTxFixture.command(PaymentTxFixture.KEY_A));
            long paymentsAfterPrepare = fixture.paymentCount(orderId);
            BeginConfirmationResult begun = begin(orderId);

            Map<String, Object> payment = fixture.payment(orderId);
            assertAll(
                () -> assertThat(prepared.status()).isEqualTo(PrepareConfirmationResult.Status.READY),
                () -> assertThat(paymentsAfterPrepare).isZero(),
                () -> assertThat(begun.status()).isEqualTo(BeginConfirmationResult.Status.SEND),
                () -> assertThat(begun.request().providerOrderId()).isEqualTo(PaymentModel.providerOrderIdOf(orderId)),
                () -> assertThat(begun.request().amount()).isEqualTo(10_000L),
                () -> assertThat(begun.token().source()).isEqualTo(ObservationSource.CREATE),
                () -> assertThat(begun.token().terminalAtStart()).isFalse(),
                () -> assertThat(payment).containsEntry("status", "UNKNOWN").containsEntry("verification_status", "NOT_CHECKED")
                    .containsEntry("transaction_key", null).containsEntry("amount", 10_000L),
                () -> assertThat(fixture.order(orderId)).containsEntry("status", "PAYMENT_PENDING")
                    .containsEntry("payment_method", "PG").containsEntry("point_amount", 0L).containsEntry("paid_amount", null),
                () -> assertThat(fixture.stock(p1.getId())).isEqualTo(8L),
                () -> assertThat(fixture.stock(p2.getId())).isEqualTo(3L),
                () -> assertThat(fixture.histories(orderId, "ORDER_RESERVATION")).isEqualTo(2L)
            );
        }

        @DisplayName("같은 주문·같은 키를 다시 시작하면 STORED 이고 재고·결제가 더 생기지 않는다. 사전 준비도 STORED 다.")
        @Test
        void storedOnRetry() {
            ProductModel product = fixture.product(1_000L, 10L);
            Long orderId = fixture.draftOrder(USER, 1L, product);
            begin(orderId);

            BeginConfirmationResult again = begin(orderId);
            PrepareConfirmationResult prepared = service.prepareConfirmation(USER, orderId,
                PaymentTxFixture.command(PaymentTxFixture.KEY_A.toUpperCase()));

            assertAll(
                () -> assertThat(again.status()).isEqualTo(BeginConfirmationResult.Status.STORED),
                () -> assertThat(again.stored().payment().status()).isEqualTo("UNKNOWN"),
                () -> assertThat(prepared.status()).as("키는 정규화해 비교").isEqualTo(PrepareConfirmationResult.Status.STORED),
                () -> assertThat(fixture.paymentCount(orderId)).isEqualTo(1L),
                () -> assertThat(fixture.stock(product.getId())).isEqualTo(9L),
                () -> assertThat(fixture.histories(orderId, "ORDER_RESERVATION")).isEqualTo(1L)
            );
        }

        @DisplayName("같은 주문의 새 키와 다른 주문에 쓴 키는 PAYMENT_ATTEMPT_CONFLICT 이고 아무것도 바꾸지 않는다.")
        @Test
        void attemptConflicts() {
            ProductModel product = fixture.product(1_000L, 10L);
            Long first = fixture.draftOrder(USER, 1L, product);
            Long second = fixture.draftOrder(USER, 1L, product);
            begin(first);

            assertAll(
                () -> assertThatThrownBy(() -> service.beginConfirmation(USER, first, PaymentTxFixture.command(PaymentTxFixture.KEY_B)))
                    .extracting("errorType").isEqualTo(ErrorType.PAYMENT_ATTEMPT_CONFLICT),
                () -> assertThatThrownBy(() -> service.prepareConfirmation(USER, second, PaymentTxFixture.command(PaymentTxFixture.KEY_A)))
                    .extracting("errorType").isEqualTo(ErrorType.PAYMENT_ATTEMPT_CONFLICT),
                () -> assertThatThrownBy(() -> begin(second))
                    .extracting("errorType").isEqualTo(ErrorType.PAYMENT_ATTEMPT_CONFLICT),
                () -> assertThat(fixture.paymentCount(second)).isZero(),
                () -> assertThat(fixture.order(second)).containsEntry("status", "DRAFT"),
                () -> assertThat(fixture.stock(product.getId())).isEqualTo(9L)
            );
        }

        @DisplayName("다른 고객은 ORDER_NOT_FOUND, 형식이 틀린 키·카드는 INVALID_REQUEST, 과거 POINT 확정은 카드 검사보다 먼저 ORDER_NOT_CONFIRMABLE 이다.")
        @Test
        void rejectsInput() {
            ProductModel product = fixture.product(1_000L, 10L);
            Long orderId = fixture.draftOrder(USER, 1L, product);
            Long pointOrder = fixture.draftOrder(USER, 1L, product);
            jdbcTemplate.update("UPDATE orders SET status = 'CONFIRMED', payment_method = 'POINT', point_amount = 1000, "
                + "paid_amount = 1000 WHERE id = ?", pointOrder);

            assertAll(
                () -> assertThatThrownBy(() -> service.beginConfirmation(OTHER_USER, orderId, PaymentTxFixture.command(PaymentTxFixture.KEY_A)))
                    .extracting("errorType").isEqualTo(ErrorType.ORDER_NOT_FOUND),
                () -> assertThatThrownBy(() -> service.beginConfirmation(USER, orderId, PaymentTxFixture.command("nope")))
                    .extracting("errorType").isEqualTo(ErrorType.INVALID_REQUEST),
                () -> assertThatThrownBy(() -> service.beginConfirmation(USER, orderId,
                    new PaymentConfirmCommand(PaymentTxFixture.KEY_A, "VISA", "0000-0000-0000-0000")))
                    .extracting("errorType").isEqualTo(ErrorType.INVALID_REQUEST),
                () -> assertThatThrownBy(() -> service.beginConfirmation(USER, pointOrder,
                    new PaymentConfirmCommand("bad", "VISA", "1")))
                    .extracting("errorType").isEqualTo(ErrorType.ORDER_NOT_CONFIRMABLE),
                () -> assertThat(fixture.paymentCount(orderId)).isZero()
            );
        }

        @DisplayName("재고 부족·삭제된 상품이면 거절하고 결제·재고·이력·주문을 모두 바꾸지 않는다.")
        @Test
        void rollsBackOnStockFailure() {
            ProductModel enough = fixture.product(1_000L, 10L);
            ProductModel scarce = fixture.product(1_000L, 1L);
            Long shortOrder = fixture.draftOrder(USER, 2L, enough, scarce);
            ProductModel deleted = fixture.product(1_000L, 10L);
            Long deletedOrder = fixture.draftOrder(USER, 1L, deleted);
            productFixture.deleteProduct(deleted.getId());

            assertAll(
                () -> assertThatThrownBy(() -> begin(shortOrder)).extracting("errorType").isEqualTo(ErrorType.INSUFFICIENT_STOCK),
                () -> assertThatThrownBy(() -> service.beginConfirmation(USER, deletedOrder,
                    PaymentTxFixture.command(PaymentTxFixture.KEY_B))).extracting("errorType").isEqualTo(ErrorType.PRODUCT_NOT_FOUND),
                () -> assertThat(fixture.stock(enough.getId())).isEqualTo(10L),
                () -> assertThat(fixture.paymentCount(shortOrder)).isZero(),
                () -> assertThat(fixture.histories(shortOrder, "ORDER_RESERVATION")).isZero(),
                () -> assertThat(fixture.order(shortOrder)).containsEntry("status", "DRAFT").containsEntry("payment_method", null)
            );
        }

        @DisplayName("TX A 의 실제 SQL 뒤 commit 전 실패는 결제·선차감·이력·주문 변경을 모두 rollback 한다.")
        @Test
        void rollsBackAfterSql() {
            ProductModel product = fixture.product(1_000L, 10L);
            Long orderId = fixture.draftOrder(USER, 3L, product);
            probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "beginConfirmation", orderId, () -> {
                throw new IllegalStateException("commit 직전 실패 주입");
            });

            assertThatThrownBy(() -> begin(orderId)).hasMessageContaining("실패 주입");

            assertAll(
                () -> assertThat(fixture.paymentCount(orderId)).isZero(),
                () -> assertThat(fixture.stock(product.getId())).isEqualTo(10L),
                () -> assertThat(fixture.histories(orderId, "ORDER_RESERVATION")).isZero(),
                () -> assertThat(fixture.order(orderId)).containsEntry("status", "DRAFT")
            );
        }
    }

    @DisplayName("관찰을 반영할 때,")
    @Nested
    class Apply {

        @DisplayName("유효 SUCCESS 는 주문 확정·결제액 기록이고 재고를 더 차감하거나 이력을 남기지 않는다.")
        @Test
        void confirms() {
            ProductModel product = fixture.product(1_000L, 10L);
            Long orderId = fixture.draftOrder(USER, 2L, product);
            PaymentCheckToken token = begin(orderId).token();

            PaymentApplyResult result = service.applyObservation(token,
                observed(ObservationSource.CREATE, orderId, "tx-A", 2_000L, PgTransactionStatus.SUCCESS));

            assertAll(
                () -> assertThat(result.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
                () -> assertThat(result.order().payment().status()).isEqualTo("SUCCESS"),
                () -> assertThat(fixture.order(orderId)).containsEntry("status", "CONFIRMED").containsEntry("paid_amount", 2_000L),
                () -> assertThat(fixture.payment(orderId)).containsEntry("status", "SUCCESS").containsEntry("transaction_key", "tx-A")
                    .containsEntry("verification_status", "VERIFIED"),
                () -> assertThat(fixture.stock(product.getId())).isEqualTo(8L),
                () -> assertThat(fixture.histories(orderId, "ORDER_RELEASE")).isZero()
            );
        }

        @DisplayName("유효 FAILED 는 고정 품목 수량을 한 번 반환하고, 같은 FAILED 재적용은 반환·이력을 더 만들지 않는다.")
        @Test
        void releasesOnce() {
            ProductModel p1 = fixture.product(1_000L, 10L);
            ProductModel p2 = fixture.product(500L, 4L);
            Long orderId = fixture.draftOrder(USER, 2L, p1, p2);
            PaymentCheckToken token = begin(orderId).token();
            PaymentCheckToken query = service.prepareVerification(orderId, USER, null).token();

            PaymentApplyResult first = service.applyObservation(query,
                observed(ObservationSource.QUERY, orderId, "tx-A", 3_000L, PgTransactionStatus.FAILED));
            PaymentApplyResult again = service.applyObservation(query,
                observed(ObservationSource.QUERY, orderId, "tx-A", 3_000L, PgTransactionStatus.FAILED));

            assertAll(
                () -> assertThat(token.source()).isEqualTo(ObservationSource.CREATE),
                () -> assertThat(first.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
                () -> assertThat(again.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
                () -> assertThat(fixture.order(orderId)).containsEntry("status", "PAYMENT_FAILED").containsEntry("paid_amount", null),
                () -> assertThat(fixture.payment(orderId)).containsEntry("failure_kind", "PG_FAILED"),
                () -> assertThat(fixture.stock(p1.getId())).isEqualTo(10L),
                () -> assertThat(fixture.stock(p2.getId())).isEqualTo(4L),
                () -> assertThat(fixture.histories(orderId, "ORDER_RELEASE")).isEqualTo(2L)
            );
        }

        @DisplayName("반환은 그사이 관리자 변경을 덮지 않고 현재 재고에 더하며, 삭제된 상품에도 반환한다.")
        @Test
        void releasesToCurrentStock() {
            ProductModel product = fixture.product(1_000L, 10L);
            Long orderId = fixture.draftOrder(USER, 3L, product);
            PaymentCheckToken token = begin(orderId).token();
            jdbcTemplate.update("UPDATE product SET stock_quantity = 50 WHERE id = ?", product.getId());
            productFixture.deleteProduct(product.getId());

            service.applyObservation(token, new PaymentObservation.RejectedNoEffect(PaymentModel.providerOrderIdOf(orderId), 3_000L));

            assertAll(
                () -> assertThat(fixture.stock(product.getId())).isEqualTo(53L),
                () -> assertThat(fixture.payment(orderId)).containsEntry("failure_kind", "REJECTED_NO_EFFECT")
                    .containsEntry("transaction_key", null)
            );
        }

        @DisplayName("로컬 미발송은 반환하고 FAILED/원인이며, PG 확인이 아니라 확인 정보는 NOT_CHECKED 그대로다.")
        @Test
        void localNotDispatched() {
            ProductModel product = fixture.product(1_000L, 10L);
            Long orderId = fixture.draftOrder(USER, 1L, product);
            PaymentCheckToken token = begin(orderId).token();

            service.applyObservation(token, new PaymentObservation.LocalNotDispatched(LocalNotDispatchedReason.CIRCUIT_REJECTED));

            assertAll(
                () -> assertThat(fixture.order(orderId)).containsEntry("status", "PAYMENT_FAILED"),
                () -> assertThat(fixture.payment(orderId)).containsEntry("failure_kind", "CIRCUIT_REJECTED")
                    .containsEntry("verification_status", "NOT_CHECKED").containsEntry("verification_checked_at", null),
                () -> assertThat(fixture.stock(product.getId())).isEqualTo(10L)
            );
        }

        @DisplayName("PENDING·확인 불가는 업무 상태·재고를 유지하고 확인 정보만 바꾼다.")
        @Test
        void pendingAndUnavailable() {
            ProductModel product = fixture.product(1_000L, 10L);
            Long orderId = fixture.draftOrder(USER, 1L, product);
            PaymentCheckToken token = begin(orderId).token();

            PaymentApplyResult unavailable = service.applyObservation(token, new PaymentObservation.Unavailable(ObservationSource.CREATE));
            PaymentApplyResult pending = service.applyObservation(token,
                observed(ObservationSource.CREATE, orderId, "tx-A", 1_000L, PgTransactionStatus.PENDING));

            assertAll(
                () -> assertThat(unavailable.outcome()).isEqualTo(PaymentApplyResult.Outcome.UNAVAILABLE),
                () -> assertThat(pending.outcome()).isEqualTo(PaymentApplyResult.Outcome.PENDING),
                () -> assertThat(fixture.order(orderId)).containsEntry("status", "PAYMENT_PENDING"),
                () -> assertThat(fixture.payment(orderId)).containsEntry("status", "PENDING").containsEntry("verification_status", "VERIFIED"),
                () -> assertThat(fixture.stock(product.getId())).isEqualTo(9L)
            );
        }

        @DisplayName("충돌은 요약 한 행을 commit 하고, 다음 충돌이 교체하며, 정상 확인은 요약을 지우지 않는다.")
        @Test
        void conflictSummary() {
            ProductModel product = fixture.product(1_000L, 10L);
            Long orderId = fixture.draftOrder(USER, 1L, product);
            PaymentCheckToken token = begin(orderId).token();
            service.applyObservation(token, observed(ObservationSource.CREATE, orderId, "tx-A", 1_000L, PgTransactionStatus.PENDING));
            PaymentCheckToken query = service.prepareVerification(orderId, USER, null).token();

            PaymentApplyResult amount = service.applyObservation(query,
                observed(ObservationSource.QUERY, orderId, "tx-A", 999L, PgTransactionStatus.SUCCESS));
            List<Map<String, Object>> afterAmount = fixture.conflicts(orderId);
            PaymentApplyResult multiple = service.applyObservation(query, new PaymentObservation.Conflict(ObservationSource.QUERY,
                PaymentConflictCode.MULTIPLE_TRANSACTIONS, ConflictEvidence.ofCount(3)));
            service.applyObservation(query, observed(ObservationSource.QUERY, orderId, "tx-A", 1_000L, PgTransactionStatus.PENDING));

            assertAll(
                () -> assertThat(amount.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
                () -> assertThat(afterAmount).singleElement().satisfies(row -> assertThat(row)
                    .containsEntry("code", "AMOUNT_MISMATCH").containsEntry("observed_amount", 999L)),
                () -> assertThat(multiple.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
                () -> assertThat(fixture.conflicts(orderId)).singleElement().satisfies(row -> assertThat(row)
                    .containsEntry("code", "MULTIPLE_TRANSACTIONS").containsEntry("observed_count", 3)
                    .containsEntry("observed_amount", null)),
                () -> assertThat(fixture.payment(orderId)).as("정상 확인 뒤 확인 정보는 VERIFIED")
                    .containsEntry("verification_status", "VERIFIED"),
                () -> assertThat(fixture.order(orderId)).containsEntry("status", "PAYMENT_PENDING")
            );
        }

        @DisplayName("성공 반영·실패 반환·충돌 요약의 실제 SQL 뒤 실패는 전부 rollback 되고 직전 commit 상태가 남는다.")
        @Test
        void rollsBackAfterSql() {
            ProductModel product = fixture.product(1_000L, 10L);
            Long success = fixture.draftOrder(USER, 1L, product);
            Long failed = fixture.draftOrder(USER, 1L, product);
            Long conflicted = fixture.draftOrder(USER, 1L, product);
            PaymentCheckToken successToken = begin(success).token();
            PaymentCheckToken failedToken = service.beginConfirmation(USER, failed, PaymentTxFixture.command(PaymentTxFixture.KEY_B)).token();
            PaymentCheckToken conflictToken = service.beginConfirmation(USER, conflicted,
                PaymentTxFixture.command("123e4567-e89b-12d3-a456-426614174002")).token();
            for (Long orderId : new Long[] {success, failed, conflicted}) {
                probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", orderId, () -> {
                    throw new IllegalStateException("commit 직전 실패 주입");
                });
            }

            assertAll(
                () -> assertThatThrownBy(() -> service.applyObservation(successToken,
                    observed(ObservationSource.CREATE, success, "tx-S", 1_000L, PgTransactionStatus.SUCCESS))),
                () -> assertThatThrownBy(() -> service.applyObservation(failedToken,
                    observed(ObservationSource.CREATE, failed, "tx-F", 1_000L, PgTransactionStatus.FAILED))),
                () -> assertThatThrownBy(() -> service.applyObservation(conflictToken,
                    observed(ObservationSource.CREATE, conflicted, "tx-C", 1L, PgTransactionStatus.PENDING))),
                () -> assertThat(fixture.order(success)).containsEntry("status", "PAYMENT_PENDING").containsEntry("paid_amount", null),
                () -> assertThat(fixture.payment(success)).containsEntry("status", "UNKNOWN").containsEntry("transaction_key", null)
                    .containsEntry("verification_status", "NOT_CHECKED"),
                () -> assertThat(fixture.order(failed)).containsEntry("status", "PAYMENT_PENDING"),
                () -> assertThat(fixture.histories(failed, "ORDER_RELEASE")).isZero(),
                () -> assertThat(fixture.stock(product.getId())).isEqualTo(7L),
                () -> assertThat(fixture.conflicts(conflicted)).isEmpty(),
                () -> assertThat(fixture.payment(conflicted)).containsEntry("verification_status", "NOT_CHECKED")
            );
        }
    }

    @DisplayName("확인을 준비할 때,")
    @Nested
    class Verification {

        @DisplayName("콜백 후보는 0건이면 ORDER_NOT_FOUND, 1건이면 orderId, 2건 이상이면 임의로 고르지 않는 내부 오류다.")
        @Test
        void resolvesCallbackCandidate() {
            ProductModel product = fixture.product(1_000L, 10L);
            Long orderId = fixture.draftOrder(USER, 1L, product);
            begin(orderId);
            String providerOrderId = PaymentModel.providerOrderIdOf(orderId);
            Long other = fixture.draftOrder(USER, 1L, product);
            Long found = service.resolveCallbackOrderId(providerOrderId);
            jdbcTemplate.update("""
                INSERT INTO payment (order_id, attempt_key, provider, provider_account, provider_order_id, amount, status,
                    dispatch_claimed_at, verification_status, created_at, updated_at)
                VALUES (?, '123e4567-e89b-12d3-a456-426614174009', 'TEST_PG', '135135', ?, 1000, 'UNKNOWN', NOW(6),
                    'NOT_CHECKED', NOW(6), NOW(6))
                """, other, providerOrderId);

            assertAll(
                () -> assertThat(found).isEqualTo(orderId),
                () -> assertThatThrownBy(() -> service.resolveCallbackOrderId("LP-ORD-0000009999"))
                    .extracting("errorType").isEqualTo(ErrorType.ORDER_NOT_FOUND),
                () -> assertThatThrownBy(() -> service.resolveCallbackOrderId(providerOrderId))
                    .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR)
            );
        }

        @DisplayName("대사는 저장 키가 없으면 외부 주문 ID, 있으면 그 키, 콜백은 알림 키를 조회 대상으로 하고 상태를 바꾸지 않는다.")
        @Test
        void preparesTargets() {
            ProductModel product = fixture.product(1_000L, 10L);
            Long orderId = fixture.draftOrder(USER, 1L, product);
            PaymentCheckToken createToken = begin(orderId).token();

            VerificationTarget noKey = service.prepareVerification(orderId, USER, null);
            service.applyObservation(createToken, observed(ObservationSource.CREATE, orderId, "tx-A", 1_000L, PgTransactionStatus.PENDING));
            Map<String, Object> beforeTargets = fixture.payment(orderId);
            VerificationTarget withKey = service.prepareVerification(orderId, USER, null);
            VerificationTarget callback = service.prepareVerification(orderId, null, "tx-Z");

            assertAll(
                () -> assertThat(noKey.kind()).isEqualTo(VerificationTarget.LookupKind.PROVIDER_ORDER_ID),
                () -> assertThat(noKey.target()).isEqualTo(PaymentModel.providerOrderIdOf(orderId)),
                () -> assertThat(withKey.kind()).isEqualTo(VerificationTarget.LookupKind.TRANSACTION_KEY),
                () -> assertThat(withKey.target()).isEqualTo("tx-A"),
                () -> assertThat(callback.target()).isEqualTo("tx-Z"),
                () -> assertThat(callback.token().source()).isEqualTo(ObservationSource.QUERY),
                () -> assertThat(callback.token().terminalAtStart()).isFalse(),
                () -> assertThat(fixture.payment(orderId)).as("준비는 상태를 바꾸지 않음").isEqualTo(beforeTargets),
                () -> assertThatThrownBy(() -> service.prepareVerification(orderId, OTHER_USER, null))
                    .extracting("errorType").isEqualTo(ErrorType.ORDER_NOT_FOUND)
            );
        }
    }

    @DisplayName("다른 결제에 이미 결합된 거래를 반영하면 결과 반영 TX 는 unique 위반으로 rollback 되고, 새 기록 TX 가 TRANSACTION_ALREADY_BOUND 를 남긴다.")
    @Test
    void transactionAlreadyBound() {
        ProductModel product = fixture.product(1_000L, 10L);
        Long first = fixture.draftOrder(USER, 1L, product);
        Long second = fixture.draftOrder(USER, 1L, product);
        PaymentCheckToken firstToken = begin(first).token();
        PaymentCheckToken secondToken = service.beginConfirmation(USER, second, PaymentTxFixture.command(PaymentTxFixture.KEY_B)).token();
        service.applyObservation(firstToken, observed(ObservationSource.CREATE, first, "tx-SHARED", 1_000L, PgTransactionStatus.SUCCESS));
        PaymentObservation.TransactionObserved reused = observed(ObservationSource.CREATE, second, "tx-SHARED", 1_000L,
            PgTransactionStatus.PENDING);

        Throwable violation = catchThrowable(() -> service.applyObservation(secondToken, reused));
        Map<String, Object> afterRollback = fixture.payment(second);
        PaymentApplyResult recorded = service.recordConstraintConflict(secondToken, reused);

        assertAll(
            () -> assertThat(violation).isInstanceOf(DataIntegrityViolationException.class),
            () -> assertThat(classifier.namedUniqueViolation(violation)).hasValue("uk_payment_transaction"),
            () -> assertThat(afterRollback).containsEntry("status", "UNKNOWN").containsEntry("transaction_key", null),
            () -> assertThat(recorded.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(fixture.conflicts(second)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("code", "TRANSACTION_ALREADY_BOUND").containsEntry("observed_transaction_key", "tx-SHARED")),
            () -> assertThat(fixture.payment(second)).containsEntry("status", "UNKNOWN").containsEntry("transaction_key", null)
                .containsEntry("verification_status", "CONFLICT"),
            () -> assertThat(fixture.order(second)).containsEntry("status", "PAYMENT_PENDING")
        );
    }

    @DisplayName("PG 주문 정보에는 결제 시도의 방식·상태·키·금액·확인 결과가 들어간다.")
    @Test
    void orderInfoHasPayment() {
        ProductModel product = fixture.product(1_000L, 10L);
        Long orderId = fixture.draftOrder(USER, 2L, product);
        PaymentCheckToken token = begin(orderId).token();

        PaymentApplyResult result = service.applyObservation(token,
            observed(ObservationSource.CREATE, orderId, "tx-A", 2_000L, PgTransactionStatus.PENDING));

        assertThat(result.order().payment()).isEqualTo(new OrderInfo.PaymentInfo(
            "PG", "PENDING", PaymentTxFixture.KEY_A, 2_000L, "VERIFIED"));
    }
}
