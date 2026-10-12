package com.loopers.application.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

final class ConcurrentRequestResults {

    private ConcurrentRequestResults() {}

    static List<Result> collect(List<Attempt> attempts, long timeoutSeconds) throws InterruptedException {
        List<Result> results = new ArrayList<>();
        for (Attempt attempt : attempts) {
            try {
                attempt.completion().get(timeoutSeconds, TimeUnit.SECONDS);
                results.add(new Result(attempt.requestId(), Outcome.SUCCESS, null));
            } catch (ExecutionException exception) {
                Throwable failure = exception.getCause();
                Outcome outcome = failure instanceof CoreException coreException
                    && coreException.getErrorType() != ErrorType.INTERNAL_ERROR
                    ? Outcome.BUSINESS_REJECTED : Outcome.TECHNICAL_ERROR;
                results.add(new Result(attempt.requestId(), outcome, failure));
            } catch (TimeoutException | CancellationException exception) {
                results.add(new Result(attempt.requestId(), Outcome.TECHNICAL_ERROR, exception));
            }
        }
        return List.copyOf(results);
    }

    static Counts count(List<Result> results) {
        return new Counts(
            results.stream().filter(result -> result.outcome() == Outcome.SUCCESS).count(),
            results.stream().filter(result -> result.outcome() == Outcome.BUSINESS_REJECTED).count(),
            results.stream().filter(result -> result.outcome() == Outcome.TECHNICAL_ERROR).count()
        );
    }

    static void assertExpectedCounts(
        List<Result> results, long successful, long businessRejected, long total,
        Predicate<CoreException> expectedBusinessRejection
    ) {
        Counts counts = count(results);
        assertThat(results).hasSize((int) total);
        assertThat(counts.total()).as("성공 + 업무 거절 + 기술 오류 = 전체 요청").isEqualTo(total);
        assertThat(counts.technicalErrors())
            .withFailMessage("기술 오류가 발생한 요청:%n%s", describeTechnicalErrors(results)).isZero();
        results.stream().filter(result -> result.outcome() == Outcome.BUSINESS_REJECTED)
            .forEach(result -> assertThat(expectedBusinessRejection.test((CoreException) result.failure()))
                .as("예상하지 않은 업무 거절: %s", result).isTrue());
        assertThat(counts.successful()).as("성공 요청 수").isEqualTo(successful);
        assertThat(counts.businessRejected()).as("업무 거절 요청 수").isEqualTo(businessRejected);
    }

    private static String describeTechnicalErrors(List<Result> results) {
        StringWriter description = new StringWriter();
        PrintWriter writer = new PrintWriter(description);
        results.stream().filter(result -> result.outcome() == Outcome.TECHNICAL_ERROR).forEach(result -> {
            writer.println("요청: " + result.requestId());
            result.failure().printStackTrace(writer);
        });
        return description.toString();
    }

    record Attempt(String requestId, Future<?> completion) {}

    record Result(String requestId, Outcome outcome, Throwable failure) {}

    record Counts(long successful, long businessRejected, long technicalErrors) {
        long total() {
            return successful + businessRejected + technicalErrors;
        }
    }

    enum Outcome {
        SUCCESS,
        BUSINESS_REJECTED,
        TECHNICAL_ERROR
    }
}
