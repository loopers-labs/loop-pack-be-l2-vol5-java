package com.loopers.application.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConcurrentRequestResultsTest {

    @Test
    void collectsEveryRequestAfterBusinessRejectionAndTechnicalError() throws Exception {
        CoreException shortage = new CoreException(ErrorType.CONFLICT, "포인트 잔액이 부족합니다.");
        SQLException databaseError = new SQLException("교착 상태로 트랜잭션이 중단됐습니다.", "40001", 1213);
        List<ConcurrentRequestResults.Attempt> attempts = List.of(
            new ConcurrentRequestResults.Attempt("order:1", CompletableFuture.failedFuture(shortage)),
            new ConcurrentRequestResults.Attempt("order:2", CompletableFuture.failedFuture(databaseError)),
            new ConcurrentRequestResults.Attempt("order:3", CompletableFuture.completedFuture(null))
        );

        List<ConcurrentRequestResults.Result> results = ConcurrentRequestResults.collect(attempts, 1L);

        assertThat(results).extracting(ConcurrentRequestResults.Result::requestId)
            .containsExactly("order:1", "order:2", "order:3");
        assertThat(results).extracting(ConcurrentRequestResults.Result::outcome).containsExactly(
            ConcurrentRequestResults.Outcome.BUSINESS_REJECTED,
            ConcurrentRequestResults.Outcome.TECHNICAL_ERROR,
            ConcurrentRequestResults.Outcome.SUCCESS
        );
        assertThat(results.get(0).failure()).isSameAs(shortage);
        assertThat(results.get(1).failure()).isSameAs(databaseError);
        assertThat(results.get(2).failure()).isNull();
        ConcurrentRequestResults.Counts counts = ConcurrentRequestResults.count(results);
        assertThat(counts).isEqualTo(new ConcurrentRequestResults.Counts(1L, 1L, 1L));
        assertThat(counts.total()).isEqualTo(attempts.size());
        assertThatThrownBy(() -> ConcurrentRequestResults.assertExpectedCounts(
            results, 1L, 1L, 3L, exception -> exception == shortage
        )).isInstanceOf(AssertionError.class)
            .hasMessageContaining("order:2")
            .hasMessageContaining("java.sql.SQLException")
            .hasMessageContaining("교착 상태로 트랜잭션이 중단됐습니다.");
    }

    @Test
    void separatesInternalErrorsFromBusinessRejection() throws Exception {
        CoreException internalError = new CoreException(ErrorType.INTERNAL_ERROR);
        List<ConcurrentRequestResults.Attempt> attempts = List.of(
            new ConcurrentRequestResults.Attempt("order:1", CompletableFuture.failedFuture(internalError))
        );

        List<ConcurrentRequestResults.Result> results = ConcurrentRequestResults.collect(attempts, 1L);

        assertThat(results).allSatisfy(result ->
            assertThat(result.outcome()).isEqualTo(ConcurrentRequestResults.Outcome.TECHNICAL_ERROR)
        );
        assertThat(results.get(0).failure()).isSameAs(internalError);
        assertThat(ConcurrentRequestResults.count(results))
            .isEqualTo(new ConcurrentRequestResults.Counts(0L, 0L, 1L));
    }

    @Test
    void rejectsUnexpectedBusinessReasonsWhenCheckingExpectedCounts() throws Exception {
        CoreException unexpectedConflict = new CoreException(ErrorType.CONFLICT, "다른 업무 충돌");
        List<ConcurrentRequestResults.Attempt> attempts = List.of(
            new ConcurrentRequestResults.Attempt("order:1", CompletableFuture.failedFuture(unexpectedConflict))
        );
        List<ConcurrentRequestResults.Result> results = ConcurrentRequestResults.collect(attempts, 1L);

        assertThat(ConcurrentRequestResults.count(results))
            .isEqualTo(new ConcurrentRequestResults.Counts(0L, 1L, 0L));
        assertThatThrownBy(() -> ConcurrentRequestResults.assertExpectedCounts(
            results, 0L, 1L, 1L, exception -> exception.getErrorType() == ErrorType.CONFLICT
                && "포인트 잔액이 부족합니다.".equals(exception.getCustomMessage())
        )).isInstanceOf(AssertionError.class)
            .hasMessageContaining("예상하지 않은 업무 거절")
            .hasMessageContaining("order:1")
            .hasMessageContaining("다른 업무 충돌");
    }

    @Test
    void collectsRemainingRequestsAfterCompletionTimeout() throws Exception {
        FutureTask<Void> unfinished = new FutureTask<>(() -> null);
        List<ConcurrentRequestResults.Attempt> attempts = List.of(
            new ConcurrentRequestResults.Attempt("order:1", unfinished),
            new ConcurrentRequestResults.Attempt("order:2", CompletableFuture.completedFuture(null))
        );

        try {
            List<ConcurrentRequestResults.Result> results = ConcurrentRequestResults.collect(attempts, 0L);

            assertThat(results).extracting(ConcurrentRequestResults.Result::outcome).containsExactly(
                ConcurrentRequestResults.Outcome.TECHNICAL_ERROR,
                ConcurrentRequestResults.Outcome.SUCCESS
            );
            assertThat(results.get(0).failure()).isInstanceOf(TimeoutException.class);
            assertThat(ConcurrentRequestResults.count(results))
                .isEqualTo(new ConcurrentRequestResults.Counts(1L, 0L, 1L));
        } finally {
            unfinished.cancel(true);
        }
    }
}
