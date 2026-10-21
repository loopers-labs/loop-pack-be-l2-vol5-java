package com.loopers.payment.adapter.out.persistence;

import com.loopers.common.domain.Money;
import com.loopers.payment.application.port.out.PaymentPort;
import com.loopers.payment.domain.ExternalPaymentStatus;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentObservation;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 결제 시도 저장 (plan.md 2·9). 테스트 트랜잭션 없이 저장마다 commit해 unique 위반을 그 자리에서 본다.
 */
@SpringBootTest
class PaymentPersistenceAdapterIntegrationTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 21, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));
    private static final String TX_KEY = "20261021:TR:abc";

    @Autowired
    private PaymentPort paymentPort;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static Payment requested(Long orderId, String attemptKey) {
        return Payment.request(orderId, 7L, attemptKey, "LOCAL_PG", Money.of(7_000), NOW);
    }

    private static Payment pending(Long orderId) {
        Payment payment = requested(orderId, "attempt-" + orderId);
        payment.markSending(NOW);
        payment.recordAccepted(TX_KEY + orderId, NOW);
        return payment;
    }

    @DisplayName("plan.md 9 결제를 저장하고 다시 읽으면 식별자·금액·상태·거래 키·조회 기록이 그대로다.")
    @Test
    void savesAndReloads() {
        // arrange
        Payment payment = pending(42L);
        payment.applyObservation(new PaymentObservation(TX_KEY + 42L, "LP-ORD-0000000042", Money.of(7_000), ExternalPaymentStatus.SUCCESS, null), NOW.plusSeconds(3));

        // act
        Long id = paymentPort.save(payment).getId();
        Payment reloaded = paymentPort.findById(id).orElseThrow();

        // assert
        assertThat(id).isPositive();
        assertThat(reloaded.getOrderId()).isEqualTo(42L);
        assertThat(reloaded.getUserId()).isEqualTo(7L);
        assertThat(reloaded.getAttemptKey()).isEqualTo("attempt-42");
        assertThat(reloaded.getProvider()).isEqualTo("LOCAL_PG");
        assertThat(reloaded.getProviderOrderId()).isEqualTo("LP-ORD-0000000042");
        assertThat(reloaded.getAmount()).isEqualTo(Money.of(7_000));
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(reloaded.getPgStatus()).isEqualTo(ExternalPaymentStatus.SUCCESS);
        assertThat(reloaded.getTransactionKey()).isEqualTo("20261021:TR:abc42");
        assertThat(reloaded.getRequestedAt()).isEqualTo(NOW);
        assertThat(reloaded.getSentAt()).isEqualTo(NOW);
        assertThat(reloaded.getCompletedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(reloaded.getCheckCount()).isEqualTo(1);
    }

    @DisplayName("plan.md 2 같은 주문에 두 번째 결제 시도는 저장되지 않는다 (order_id unique).")
    @Test
    void orderIdIsUnique() {
        // arrange
        paymentPort.save(requested(42L, "attempt-1"));

        // act & assert
        assertThatThrownBy(() -> paymentPort.save(requested(42L, "attempt-2")))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(paymentPort.findByAttemptKey("attempt-2")).isEmpty();
        assertThat(paymentPort.findByOrderId(42L)).get().extracting(Payment::getAttemptKey).isEqualTo("attempt-1");
    }

    @DisplayName("plan.md 2 같은 시도 키로 다른 주문의 결제는 저장되지 않는다 (attempt_key unique).")
    @Test
    void attemptKeyIsUnique() {
        // arrange
        paymentPort.save(requested(42L, "attempt-1"));

        // act & assert
        assertThatThrownBy(() -> paymentPort.save(requested(43L, "attempt-1")))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(paymentPort.findByOrderId(43L)).isEmpty();
    }

    @DisplayName("주문·시도 키·PG 주문 식별자로 같은 결제를 찾고, 없는 값은 비어 있다.")
    @Test
    void findsByIdentifiers() {
        // arrange
        Long id = paymentPort.save(requested(42L, "attempt-1")).getId();

        // act & assert
        assertThat(paymentPort.findByOrderId(42L)).get().extracting(Payment::getId).isEqualTo(id);
        assertThat(paymentPort.findByAttemptKey("attempt-1")).get().extracting(Payment::getId).isEqualTo(id);
        assertThat(paymentPort.findByProviderOrderId("LP-ORD-0000000042")).get().extracting(Payment::getId).isEqualTo(id);
        assertThat(paymentPort.findByOrderId(43L)).isEmpty();
        assertThat(paymentPort.findByAttemptKey("attempt-2")).isEmpty();
        assertThat(paymentPort.findByProviderOrderId("LP-ORD-0000000043")).isEmpty();
    }

    @DisplayName("ADR-W5-04 트랜잭션 안에서 쓰기 잠금으로 결제를 찾는다.")
    @Test
    void findsForUpdateInTransaction() {
        // arrange
        Long id = paymentPort.save(requested(42L, "attempt-1")).getId();

        // act
        Payment locked = transactionTemplate.execute(status -> paymentPort.findByIdForUpdate(id).orElseThrow());

        // assert
        assertThat(locked.getOrderId()).isEqualTo(42L);
        Optional<Payment> missing = transactionTemplate.execute(status -> paymentPort.findByIdForUpdate(id + 100));
        assertThat(missing).isEmpty();
    }

    @DisplayName("plan.md 5-1 reconcile 대상은 PENDING·UNKNOWN 중 다음 조회 시각이 지금 이전(같음 포함)인 결제이고, 다음 조회 시각이 이른 순·limit만큼이다.")
    @Test
    void findsDueForCheck() {
        // arrange: 수락·UNKNOWN 시각 + 5초가 다음 조회 시각이다
        paymentPort.save(requested(1L, "attempt-1"));                                         // REQUESTED — 제외
        Payment sending = requested(2L, "attempt-2");
        sending.markSending(NOW);
        paymentPort.save(sending);                                                              // SENDING — 제외
        Long dueNowId = paymentPort.save(unknownAt(3L, NOW.minusSeconds(5))).getId();           // 다음 조회 = NOW — 포함
        Long oldestId = paymentPort.save(pendingAt(4L, NOW.minusMinutes(1))).getId();           // 다음 조회 = NOW-55s — 포함
        Long middleId = paymentPort.save(unknownAt(5L, NOW.minusSeconds(30))).getId();          // 다음 조회 = NOW-25s — 포함
        paymentPort.save(unknownAt(6L, NOW));                                                   // 다음 조회 = NOW+5s — 제외
        Payment done = pending(7L);
        done.applyObservation(new PaymentObservation(TX_KEY + 7L, "LP-ORD-0000000007", Money.of(7_000), ExternalPaymentStatus.SUCCESS, null), NOW);
        paymentPort.save(done);                                                                 // SUCCESS — 제외

        // act
        List<Payment> all = paymentPort.findDueForCheck(NOW, 10);
        List<Payment> first = paymentPort.findDueForCheck(NOW, 1);

        // assert
        assertThat(all).extracting(Payment::getId).containsExactly(oldestId, middleId, dueNowId);
        assertThat(first).extracting(Payment::getId).containsExactly(oldestId);
    }

    @DisplayName("P-14 만료 대상은 PENDING·UNKNOWN 중 발송 시각(sent_at)이 기준보다 이른(같음 제외) 결제이고, 발송이 이른 순·limit만큼이다.")
    @Test
    void findsExpirable() {
        // arrange: 기준 = NOW. 발송 시각이 NOW보다 이르면 대상
        paymentPort.save(requested(1L, "attempt-1"));                                         // REQUESTED(발송 시각 없음) — 제외
        Payment sending = requested(2L, "attempt-2");
        sending.markSending(NOW.minusHours(1));
        paymentPort.save(sending);                                                              // SENDING — 제외 (정리 스케줄러 몫)
        Long middleId = paymentPort.save(unknownAt(3L, NOW.minusMinutes(10))).getId();          // UNKNOWN 10분 전 — 포함
        Long oldestId = paymentPort.save(pendingAt(4L, NOW.minusMinutes(40))).getId();          // PENDING 40분 전 — 포함
        Long newestId = paymentPort.save(unknownAt(5L, NOW.minusSeconds(1))).getId();           // UNKNOWN 1초 전 — 포함
        paymentPort.save(unknownAt(6L, NOW));                                                   // 기준과 같은 시각 — 제외
        paymentPort.save(pendingAt(7L, NOW.plusMinutes(1)));                                    // 기준 뒤 — 제외
        Payment done = pendingAt(8L, NOW.minusHours(2));
        done.applyObservation(new PaymentObservation(TX_KEY + 8L, "LP-ORD-0000000008", Money.of(7_000), ExternalPaymentStatus.SUCCESS, null), NOW);
        paymentPort.save(done);                                                                 // SUCCESS — 제외

        // act
        List<Long> all = paymentPort.findExpirable(NOW, 10);
        List<Long> first = paymentPort.findExpirable(NOW, 2);

        // assert
        assertThat(all).containsExactly(oldestId, middleId, newestId);
        assertThat(first).containsExactly(oldestId, middleId);
    }

    @DisplayName("plan.md 5-1 다음 조회 시각은 저장 뒤 다시 읽어도 그대로다.")
    @Test
    void savesNextCheckAt() {
        // act
        Long id = paymentPort.save(unknownAt(42L, NOW)).getId();

        // assert
        assertThat(paymentPort.findById(id).orElseThrow().getNextCheckAt()).isEqualTo(NOW.plusSeconds(5));
    }

    private static Payment pendingAt(Long orderId, ZonedDateTime acceptedAt) {
        Payment payment = requested(orderId, "attempt-" + orderId);
        payment.markSending(acceptedAt);
        payment.recordAccepted(TX_KEY + orderId, acceptedAt);
        return payment;
    }

    private static Payment unknownAt(Long orderId, ZonedDateTime unknownAt) {
        Payment payment = requested(orderId, "attempt-" + orderId);
        payment.markSending(unknownAt);
        payment.recordUnknown(unknownAt);
        return payment;
    }
}
