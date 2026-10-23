package com.loopers.infrastructure;

import com.loopers.domain.payment.DuplicatePaymentAttemptException;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflictReason;
import com.loopers.infrastructure.payment.PaymentAttemptJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PaymentAttemptRepositoryIntegrationTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-10-21T10:00:00+09:00");
    private static final ZonedDateTime EARLIER = NOW.minusMinutes(10);

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @DisplayName("주기 조회가 미종결 시도만 좁히도록 상태와 확인 시각의 복합 인덱스를 둔다.")
    @Test
    void keepsStatusAndLastCheckedIndexForPeriodicLookup() {
        assertThat(jdbcTemplate.queryForList("""
            SELECT COLUMN_NAME
            FROM information_schema.statistics
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempts'
              AND INDEX_NAME = 'idx_payment_attempts_status_checked'
            ORDER BY SEQ_IN_INDEX
            """, String.class)).containsExactly("status", "last_checked_at");
    }

    @Nested
    class SaveAndFind {

        @DisplayName("여러 주문의 시도를 한 번에 읽고, 시도가 없는 주문은 결과에 없다.")
        @Test
        void findsAttemptsOfSeveralOrdersAtOnce() {
            save(key(1), 1L, "00000001");
            save(key(2), 2L, "00000002");
            save(key(3), 3L, "00000003");

            assertThat(paymentAttemptRepository.findAllByOrderIds(List.of(1L, 3L, 4L)))
                .extracting(PaymentAttempt::getOrderId)
                .containsExactlyInAnyOrder(1L, 3L);
            assertThat(paymentAttemptRepository.findAllByOrderIds(List.of())).isEmpty();
        }

        @DisplayName("저장한 시도를 ID·시도 키·주문 ID·PG 주문 식별자로 다시 읽으면 같은 값으로 복원한다.")
        @Test
        void restoresSavedAttemptByEveryIdentifier() {
            PaymentAttempt saved = save("8F14E45F-CEEA-467A-9575-2C9B3E5A1D20", 42L, "7f3a9c1e");

            List<PaymentAttempt> found = List.of(
                paymentAttemptRepository.findById(saved.getId()).orElseThrow(),
                paymentAttemptRepository.findByPaymentAttemptKey("8f14e45f-ceea-467a-9575-2c9b3e5a1d20").orElseThrow(),
                paymentAttemptRepository.findByOrderId(42L).orElseThrow(),
                paymentAttemptRepository.findByProviderOrderId("LP-ORD-0000000042-7f3a9c1e").orElseThrow()
            );

            assertThat(found).allSatisfy(attempt -> {
                assertThat(attempt.getId()).isEqualTo(saved.getId());
                assertThat(attempt.getPaymentAttemptKey()).isEqualTo("8f14e45f-ceea-467a-9575-2c9b3e5a1d20");
                assertThat(attempt.getOrderId()).isEqualTo(42L);
                assertThat(attempt.getProviderOrderId()).isEqualTo("LP-ORD-0000000042-7f3a9c1e");
                assertThat(attempt.getAmount()).isEqualTo(7_000L);
                assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
                assertThat(attempt.getTransactionKey()).isNull();
                assertThat(attempt.getConflictReason()).isNull();
            });
        }

        @DisplayName("없는 식별자로 조회하면 비어 있다.")
        @Test
        void returnsEmptyForUnknownIdentifiers() {
            assertThat(paymentAttemptRepository.findByPaymentAttemptKey(key(9))).isEmpty();
            assertThat(paymentAttemptRepository.findByOrderId(9L)).isEmpty();
            assertThat(paymentAttemptRepository.findByProviderOrderId("LP-ORD-0000000009-00000000")).isEmpty();
        }
    }

    @Nested
    class UniqueConstraints {

        @DisplayName("같은 시도 키는 다른 주문이라도 저장하지 않고, 재요청 판정에 쓰도록 시도 중복으로 알린다.")
        @Test
        void rejectsDuplicateAttemptKey() {
            save(key(1), 1L, "00000001");

            assertThatThrownBy(() -> save(key(1), 2L, "00000002"))
                .isInstanceOf(DuplicatePaymentAttemptException.class);
        }

        @DisplayName("같은 주문의 두 번째 시도는 저장하지 않고, 재요청 판정에 쓰도록 시도 중복으로 알린다.")
        @Test
        void rejectsSecondAttemptForSameOrder() {
            save(key(1), 1L, "00000001");

            assertThatThrownBy(() -> save(key(2), 1L, "00000002"))
                .isInstanceOf(DuplicatePaymentAttemptException.class);
        }

        @DisplayName("같은 PG 주문 식별자는 저장하지 않으며, 재요청이 아니므로 기술 오류로 남긴다.")
        @Test
        void rejectsDuplicateProviderOrderId() {
            save(key(1), 1L, "00000001");
            PaymentAttempt duplicated = PaymentAttempt.reconstitute(
                null, key(2), 2L, "LP-ORD-0000000001-00000001", null, 7_000L,
                PaymentAttemptStatus.REQUESTED, null, null
            );

            assertThatThrownBy(() -> paymentAttemptRepository.save(duplicated))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicatePaymentAttemptException.class)
                .hasMessageContaining("uk_payment_attempts_provider_order_id");
        }
    }

    @Nested
    class MarkPending {

        @DisplayName("거래 키가 없는 REQUESTED·UNKNOWN 시도는 PENDING과 거래 키를 함께 기록하고 상태 변경 시각을 바꾼다.")
        @Test
        void recordsPendingFromRequestedAndUnknown() {
            PaymentAttempt requested = save(key(1), 1L, "00000001");
            PaymentAttempt unknown = save(key(2), 2L, "00000002");
            force(unknown.getId(), PaymentAttemptStatus.UNKNOWN, null, EARLIER);

            assertThat(paymentAttemptRepository.markPending(requested.getId(), "TX-1", NOW)).isEqualTo(1);
            assertThat(paymentAttemptRepository.markPending(unknown.getId(), "TX-2", NOW)).isEqualTo(1);

            assertState(requested.getId(), PaymentAttemptStatus.PENDING, "TX-1");
            assertState(unknown.getId(), PaymentAttemptStatus.PENDING, "TX-2");
            assertThat(entity(requested.getId()).getUpdatedAt().toInstant()).isEqualTo(NOW.toInstant());
        }

        @DisplayName("이미 PENDING이거나 다른 거래 키가 있거나 종결된 시도는 바꾸지 않는다.")
        @Test
        void keepsPendingDifferentKeyAndTerminalAttempts() {
            PaymentAttempt pending = save(key(1), 1L, "00000001");
            force(pending.getId(), PaymentAttemptStatus.PENDING, "TX-1", EARLIER);
            PaymentAttempt unknownWithKey = save(key(2), 2L, "00000002");
            force(unknownWithKey.getId(), PaymentAttemptStatus.UNKNOWN, "TX-2", EARLIER);
            PaymentAttempt succeeded = save(key(3), 3L, "00000003");
            force(succeeded.getId(), PaymentAttemptStatus.SUCCEEDED, "TX-3", EARLIER);

            assertThat(paymentAttemptRepository.markPending(pending.getId(), "TX-1", NOW)).isZero();
            assertThat(paymentAttemptRepository.markPending(unknownWithKey.getId(), "TX-9", NOW)).isZero();
            assertThat(paymentAttemptRepository.markPending(succeeded.getId(), "TX-3", NOW)).isZero();

            assertState(pending.getId(), PaymentAttemptStatus.PENDING, "TX-1");
            assertState(unknownWithKey.getId(), PaymentAttemptStatus.UNKNOWN, "TX-2");
            assertState(succeeded.getId(), PaymentAttemptStatus.SUCCEEDED, "TX-3");
            assertThat(entity(pending.getId()).getUpdatedAt().toInstant()).isEqualTo(EARLIER.toInstant());
        }
    }

    @Nested
    class MarkUnknown {

        @DisplayName("거래 키가 없는 REQUESTED 시도만 UNKNOWN이 된다.")
        @Test
        void marksOnlyRequestedWithoutKey() {
            PaymentAttempt requested = save(key(1), 1L, "00000001");
            PaymentAttempt pending = save(key(2), 2L, "00000002");
            force(pending.getId(), PaymentAttemptStatus.PENDING, "TX-2", EARLIER);
            PaymentAttempt rejected = save(key(3), 3L, "00000003");
            force(rejected.getId(), PaymentAttemptStatus.FAILED, null, EARLIER);

            assertThat(paymentAttemptRepository.markUnknown(requested.getId(), NOW)).isEqualTo(1);
            assertThat(paymentAttemptRepository.markUnknown(pending.getId(), NOW)).isZero();
            assertThat(paymentAttemptRepository.markUnknown(rejected.getId(), NOW)).isZero();

            assertState(requested.getId(), PaymentAttemptStatus.UNKNOWN, null);
            assertState(pending.getId(), PaymentAttemptStatus.PENDING, "TX-2");
            assertState(rejected.getId(), PaymentAttemptStatus.FAILED, null);
        }
    }

    @Nested
    class Terminate {

        @DisplayName("미종결 시도는 거래 키가 없거나 같은 키일 때 종결 상태와 거래 키를 함께 저장한다.")
        @Test
        void terminatesUnterminatedAttemptWithKey() {
            PaymentAttempt requested = save(key(1), 1L, "00000001");
            PaymentAttempt pending = save(key(2), 2L, "00000002");
            force(pending.getId(), PaymentAttemptStatus.PENDING, "TX-2", EARLIER);
            PaymentAttempt unknown = save(key(3), 3L, "00000003");
            force(unknown.getId(), PaymentAttemptStatus.UNKNOWN, null, EARLIER);

            assertThat(paymentAttemptRepository.terminate(requested.getId(), PaymentAttemptStatus.SUCCEEDED, "TX-1", NOW))
                .isEqualTo(1);
            assertThat(paymentAttemptRepository.terminate(pending.getId(), PaymentAttemptStatus.FAILED, "TX-2", NOW))
                .isEqualTo(1);
            assertThat(paymentAttemptRepository.terminate(unknown.getId(), PaymentAttemptStatus.SUCCEEDED, "TX-3", NOW))
                .isEqualTo(1);

            assertState(requested.getId(), PaymentAttemptStatus.SUCCEEDED, "TX-1");
            assertState(pending.getId(), PaymentAttemptStatus.FAILED, "TX-2");
            assertState(unknown.getId(), PaymentAttemptStatus.SUCCEEDED, "TX-3");
            assertThat(entity(pending.getId()).getUpdatedAt().toInstant()).isEqualTo(NOW.toInstant());
        }

        @DisplayName("기록한 거래 키와 다른 키나 이미 종결된 시도는 종결하지 않는다.")
        @Test
        void keepsDifferentKeyAndTerminalAttempts() {
            PaymentAttempt pending = save(key(1), 1L, "00000001");
            force(pending.getId(), PaymentAttemptStatus.PENDING, "TX-1", EARLIER);
            PaymentAttempt succeeded = save(key(2), 2L, "00000002");
            force(succeeded.getId(), PaymentAttemptStatus.SUCCEEDED, "TX-2", EARLIER);

            assertThat(paymentAttemptRepository.terminate(pending.getId(), PaymentAttemptStatus.SUCCEEDED, "TX-9", NOW))
                .isZero();
            assertThat(paymentAttemptRepository.terminate(succeeded.getId(), PaymentAttemptStatus.FAILED, "TX-2", NOW))
                .isZero();

            assertState(pending.getId(), PaymentAttemptStatus.PENDING, "TX-1");
            assertState(succeeded.getId(), PaymentAttemptStatus.SUCCEEDED, "TX-2");
        }

        @DisplayName("종결 상태가 아닌 값으로는 종결하지 않고 예외를 던진다.")
        @Test
        void rejectsNonTerminalStatus() {
            PaymentAttempt requested = save(key(1), 1L, "00000001");

            assertThatThrownBy(() ->
                paymentAttemptRepository.terminate(requested.getId(), PaymentAttemptStatus.PENDING, "TX-1", NOW)
            ).isInstanceOf(IllegalArgumentException.class);

            assertState(requested.getId(), PaymentAttemptStatus.REQUESTED, null);
        }
    }

    @Nested
    class RejectBeforeCreate {

        @DisplayName("거래 키가 없는 REQUESTED·UNKNOWN 시도만 FAILED가 되고 거래 키는 비어 있다.")
        @Test
        void failsOnlyAttemptsWithoutTransaction() {
            PaymentAttempt requested = save(key(1), 1L, "00000001");
            PaymentAttempt unknown = save(key(2), 2L, "00000002");
            force(unknown.getId(), PaymentAttemptStatus.UNKNOWN, null, EARLIER);
            PaymentAttempt pending = save(key(3), 3L, "00000003");
            force(pending.getId(), PaymentAttemptStatus.PENDING, "TX-3", EARLIER);
            PaymentAttempt rejected = save(key(4), 4L, "00000004");
            force(rejected.getId(), PaymentAttemptStatus.FAILED, null, EARLIER);

            assertThat(paymentAttemptRepository.rejectBeforeCreate(requested.getId(), NOW)).isEqualTo(1);
            assertThat(paymentAttemptRepository.rejectBeforeCreate(unknown.getId(), NOW)).isEqualTo(1);
            assertThat(paymentAttemptRepository.rejectBeforeCreate(pending.getId(), NOW)).isZero();
            assertThat(paymentAttemptRepository.rejectBeforeCreate(rejected.getId(), NOW)).isZero();

            assertState(requested.getId(), PaymentAttemptStatus.FAILED, null);
            assertState(unknown.getId(), PaymentAttemptStatus.FAILED, null);
            assertState(pending.getId(), PaymentAttemptStatus.PENDING, "TX-3");
        }
    }

    @Nested
    class RecordConflict {

        @DisplayName("처음 충돌만 사유와 시각을 기록하고 상태·거래 키·상태 변경 시각은 바꾸지 않는다.")
        @Test
        void recordsFirstConflictOnly() {
            PaymentAttempt pending = save(key(1), 1L, "00000001");
            force(pending.getId(), PaymentAttemptStatus.PENDING, "TX-1", EARLIER);

            assertThat(paymentAttemptRepository.recordConflict(
                pending.getId(), PaymentConflictReason.TRANSACTION_MISMATCH, NOW)).isEqualTo(1);
            assertThat(paymentAttemptRepository.recordConflict(
                pending.getId(), PaymentConflictReason.RESULT_CONTRADICTION, NOW.plusMinutes(1))).isZero();

            PaymentAttempt reloaded = reload(pending.getId());
            assertThat(reloaded.getConflictReason()).isEqualTo(PaymentConflictReason.TRANSACTION_MISMATCH);
            assertThat(reloaded.getConflictDetectedAt().toInstant()).isEqualTo(NOW.toInstant());
            assertThat(reloaded.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(reloaded.getTransactionKey()).isEqualTo("TX-1");
            assertThat(entity(pending.getId()).getUpdatedAt().toInstant()).isEqualTo(EARLIER.toInstant());
        }
    }

    @Nested
    class TouchLastChecked {

        @DisplayName("확인 시각만 기록하고 상태와 상태 변경 시각은 바꾸지 않는다.")
        @Test
        void recordsCheckTimeWithoutChangingState() {
            PaymentAttempt requested = save(key(1), 1L, "00000001");
            force(requested.getId(), PaymentAttemptStatus.REQUESTED, null, EARLIER);

            assertThat(paymentAttemptRepository.touchLastChecked(requested.getId(), NOW)).isEqualTo(1);

            PaymentAttemptJpaEntity entity = entity(requested.getId());
            assertThat(entity.getLastCheckedAt().toInstant()).isEqualTo(NOW.toInstant());
            assertThat(entity.getUpdatedAt().toInstant()).isEqualTo(EARLIER.toInstant());
            assertThat(entity.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
        }
    }

    @Nested
    class FindDueForCheck {

        @DisplayName("기준 시간이 지난 미종결 시도 중 조회 대상 기간 안의 것을 확인 시각이 비었거나 오래된 순으로 건수만큼 찾는다.")
        @Test
        void findsDueUnterminatedAttemptsInCheckOrder() {
            PaymentAttempt neverChecked = due(key(1), 1L, PaymentAttemptStatus.REQUESTED, null);
            PaymentAttempt checkedRecently = due(key(2), 2L, PaymentAttemptStatus.PENDING, NOW.minusMinutes(5));
            PaymentAttempt checkedLongAgo = due(key(3), 3L, PaymentAttemptStatus.UNKNOWN, NOW.minusMinutes(30));
            due(key(4), 4L, PaymentAttemptStatus.SUCCEEDED, null);
            PaymentAttempt changedJustNow = due(key(5), 5L, PaymentAttemptStatus.REQUESTED, null);
            force(changedJustNow.getId(), PaymentAttemptStatus.REQUESTED, null, NOW);
            PaymentAttempt outOfWindow = due(key(6), 6L, PaymentAttemptStatus.REQUESTED, null);
            forceCreatedAt(outOfWindow.getId(), NOW.minusDays(2));

            ZonedDateTime changedBefore = NOW.minusMinutes(1);
            ZonedDateTime createdAfter = NOW.minusHours(24);

            assertThat(paymentAttemptRepository.findDueForCheck(changedBefore, createdAfter, 10))
                .extracting(PaymentAttempt::getId)
                .containsExactly(neverChecked.getId(), checkedLongAgo.getId(), checkedRecently.getId());
            assertThat(paymentAttemptRepository.findDueForCheck(changedBefore, createdAfter, 2))
                .extracting(PaymentAttempt::getId)
                .containsExactly(neverChecked.getId(), checkedLongAgo.getId());
        }

        private PaymentAttempt due(String key, Long orderId, PaymentAttemptStatus status, ZonedDateTime lastCheckedAt) {
            PaymentAttempt attempt = save(key, orderId, "%08d".formatted(orderId));
            force(attempt.getId(), status, status == PaymentAttemptStatus.REQUESTED ? null : "TX-" + orderId, EARLIER);
            forceCreatedAt(attempt.getId(), NOW.minusHours(1));
            entityManager.createNativeQuery("UPDATE payment_attempts SET last_checked_at = :checkedAt WHERE id = :id")
                .setParameter("checkedAt", lastCheckedAt)
                .setParameter("id", attempt.getId())
                .executeUpdate();
            return attempt;
        }
    }

    private PaymentAttempt save(String key, Long orderId, String suffix) {
        PaymentAttempt saved = paymentAttemptRepository.save(PaymentAttempt.create(key, orderId, 7_000L, suffix));
        entityManager.flush();
        entityManager.clear();
        return saved;
    }

    private void force(Long attemptId, PaymentAttemptStatus status, String transactionKey, ZonedDateTime updatedAt) {
        entityManager.createNativeQuery("""
                UPDATE payment_attempts
                SET status = :status, transaction_key = :transactionKey, updated_at = :updatedAt
                WHERE id = :id
                """)
            .setParameter("status", status.name())
            .setParameter("transactionKey", transactionKey)
            .setParameter("updatedAt", updatedAt)
            .setParameter("id", attemptId)
            .executeUpdate();
        entityManager.clear();
    }

    private void forceCreatedAt(Long attemptId, ZonedDateTime createdAt) {
        entityManager.createNativeQuery("UPDATE payment_attempts SET created_at = :createdAt WHERE id = :id")
            .setParameter("createdAt", createdAt)
            .setParameter("id", attemptId)
            .executeUpdate();
        entityManager.clear();
    }

    private PaymentAttempt reload(Long attemptId) {
        entityManager.clear();
        return paymentAttemptRepository.findById(attemptId).orElseThrow();
    }

    private PaymentAttemptJpaEntity entity(Long attemptId) {
        entityManager.clear();
        return entityManager.find(PaymentAttemptJpaEntity.class, attemptId);
    }

    private void assertState(Long attemptId, PaymentAttemptStatus status, String transactionKey) {
        PaymentAttempt reloaded = reload(attemptId);
        assertThat(reloaded.getStatus()).isEqualTo(status);
        assertThat(reloaded.getTransactionKey()).isEqualTo(transactionKey);
    }

    private static String key(int number) {
        return "00000000-0000-4000-8000-%012d".formatted(number);
    }
}
