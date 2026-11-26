package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.UnresolvedStatusSummary;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미종결 상태별 요약 집계를 실제 MySQL 로 검증한다 (10주차 업무 지표 입력).
 * 상태는 DB 에 직접 쓰지 않고 운영 경로(markPendingIfNotSettled·markUnknownIfRequested·settleIfOpen)로 만든다.
 * 조건부 UPDATE 는 트랜잭션을 요구하므로 TransactionTemplate 으로 각각 커밋하고, 집계는 새 조회로 확인한다.
 */
@SpringBootTest
class PaymentAttemptRepositoryIntegrationTest {

    private static final String CARD_TYPE = "TEST";

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private TransactionTemplate transactionTemplate;

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Nested
    @DisplayName("summarizeUnresolved 는")
    class SummarizeUnresolved {

        @Test
        @DisplayName("시도가 하나도 없으면 미종결 세 상태를 모두 0 건·시각 없음으로 돌려준다.")
        void returnsZeroForEveryUnresolvedStatus_whenEmpty() {
            List<UnresolvedStatusSummary> summaries = paymentAttemptRepository.summarizeUnresolved();

            assertThat(summaries).extracting(UnresolvedStatusSummary::status)
                .containsExactly(
                    PaymentAttempt.Status.REQUESTED, PaymentAttempt.Status.PENDING, PaymentAttempt.Status.UNKNOWN);
            assertThat(summaries).allSatisfy(summary -> {
                assertThat(summary.count()).isZero();
                assertThat(summary.oldestCreatedAt()).isNull();
            });
        }

        @Test
        @DisplayName("행이 있는 상태만 집계하고 행이 없는 상태는 0 건·시각 없음으로 채운다.")
        void fillsAbsentStatusWithZeroAndNoTimestamp() {
            savedAttempt(1L);
            savedAttempt(2L);
            markUnknown(savedAttempt(3L));

            Map<PaymentAttempt.Status, UnresolvedStatusSummary> summaries = summarize();

            assertThat(summaries.get(PaymentAttempt.Status.REQUESTED).count()).isEqualTo(2L);
            assertThat(summaries.get(PaymentAttempt.Status.UNKNOWN).count()).isEqualTo(1L);
            assertThat(summaries.get(PaymentAttempt.Status.PENDING).count()).isZero();
            assertThat(summaries.get(PaymentAttempt.Status.PENDING).oldestCreatedAt()).isNull();
        }

        @Test
        @DisplayName("종결 상태인 SUCCESS·FAILED 는 집계에서 제외한다.")
        void excludesTerminalStatuses() {
            savedAttempt(1L);
            markUnknown(savedAttempt(2L));
            markPending(savedAttempt(3L));
            settle(savedAttempt(4L), PaymentAttempt.Status.SUCCESS, null);
            settle(savedAttempt(5L), PaymentAttempt.Status.FAILED, PaymentAttempt.FailureReason.PG_FAILED);

            List<UnresolvedStatusSummary> summaries = paymentAttemptRepository.summarizeUnresolved();

            assertThat(summaries).extracting(UnresolvedStatusSummary::count).containsExactly(1L, 1L, 1L);
            assertThat(totalCount(summaries)).isEqualTo(3L);
        }

        @Test
        @DisplayName("UNKNOWN 이 종결되면 그 상태의 건수가 줄고 합계도 줄어든다.")
        void decreasesWhenUnknownIsSettled() {
            Long first = savedAttempt(1L);
            Long second = savedAttempt(2L);
            markUnknown(first);
            markUnknown(second);
            assertThat(summarize().get(PaymentAttempt.Status.UNKNOWN).count()).isEqualTo(2L);

            settle(first, PaymentAttempt.Status.SUCCESS, null);

            List<UnresolvedStatusSummary> summaries = paymentAttemptRepository.summarizeUnresolved();
            assertThat(summarize(summaries).get(PaymentAttempt.Status.UNKNOWN).count()).isEqualTo(1L);
            assertThat(totalCount(summaries)).isEqualTo(1L);
        }

        @Test
        @DisplayName("가장 오래된 시각은 그 상태에서 가장 먼저 만들어진 시도의 createdAt 이다.")
        void reportsOldestCreatedAtOfThatStatus() {
            Long oldest = savedAttempt(1L);
            Long newer = savedAttempt(2L);
            markUnknown(oldest);
            markUnknown(newer);
            ZonedDateTime oldestCreatedAt = createdAtOf(oldest);
            ZonedDateTime newerCreatedAt = createdAtOf(newer);
            assertThat(oldestCreatedAt).isBefore(newerCreatedAt);

            UnresolvedStatusSummary unknown = summarize().get(PaymentAttempt.Status.UNKNOWN);

            assertThat(unknown.count()).isEqualTo(2L);
            assertThat(unknown.oldestCreatedAt()).isNotNull();
            assertThat(unknown.oldestCreatedAt().toInstant()).isEqualTo(oldestCreatedAt.toInstant());
        }

        @Test
        @DisplayName("가장 오래된 시도가 종결되면 그 상태의 가장 오래된 시각이 다음 시도로 밀린다.")
        void movesOldestCreatedAt_whenOldestIsSettled() {
            Long oldest = savedAttempt(1L);
            Long newer = savedAttempt(2L);
            markUnknown(oldest);
            markUnknown(newer);

            settle(oldest, PaymentAttempt.Status.SUCCESS, null);

            UnresolvedStatusSummary unknown = summarize().get(PaymentAttempt.Status.UNKNOWN);
            assertThat(unknown.count()).isEqualTo(1L);
            assertThat(unknown.oldestCreatedAt().toInstant()).isEqualTo(createdAtOf(newer).toInstant());
        }
    }

    private Map<PaymentAttempt.Status, UnresolvedStatusSummary> summarize() {
        return summarize(paymentAttemptRepository.summarizeUnresolved());
    }

    private Map<PaymentAttempt.Status, UnresolvedStatusSummary> summarize(List<UnresolvedStatusSummary> summaries) {
        return summaries.stream().collect(Collectors.toMap(UnresolvedStatusSummary::status, Function.identity()));
    }

    private long totalCount(List<UnresolvedStatusSummary> summaries) {
        return summaries.stream().mapToLong(UnresolvedStatusSummary::count).sum();
    }

    private ZonedDateTime createdAtOf(Long attemptId) {
        return paymentAttemptRepository.findById(attemptId).orElseThrow().getCreatedAt();
    }

    private Long savedAttempt(long orderId) {
        PaymentAttempt saved = paymentAttemptRepository.save(new PaymentAttempt(
            orderId, orderId, "attempt-key-" + orderId, PaymentAttempt.providerOrderIdOf(orderId), 1_000L, CARD_TYPE));
        return saved.getId();
    }

    private void markUnknown(Long attemptId) {
        transactionTemplate.executeWithoutResult(status ->
            assertThat(paymentAttemptRepository.markUnknownIfRequested(attemptId)).isEqualTo(1));
    }

    private void markPending(Long attemptId) {
        transactionTemplate.executeWithoutResult(status ->
            assertThat(paymentAttemptRepository.markPendingIfNotSettled(attemptId, "TX-" + attemptId)).isEqualTo(1));
    }

    private void settle(Long attemptId, PaymentAttempt.Status status, PaymentAttempt.FailureReason failureReason) {
        transactionTemplate.executeWithoutResult(tx ->
            assertThat(paymentAttemptRepository.settleIfOpen(attemptId, status, failureReason, "TX-" + attemptId))
                .isEqualTo(1));
    }
}
