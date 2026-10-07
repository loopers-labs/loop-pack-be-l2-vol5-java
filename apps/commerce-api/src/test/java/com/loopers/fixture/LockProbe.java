package com.loopers.fixture;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 잠금 범위 확인용. 다른 스레드의 독립 트랜잭션·connection 에서 {@code FOR UPDATE NOWAIT} 를 실행해
 * 대상 행을 지금 다른 트랜잭션이 잠그고 있는지 기다리지 않고 판정한다. 운영 lock timeout 설정과 무관하다.
 *
 * <p>확인 스레드가 끝났는지는 {@code shutdownNow}·Future 취소가 아니라 executor 종료 확인으로만 판단한다.
 * 스레드가 끝났다면 TransactionTemplate 의 commit/rollback 과 connection 반납도 끝난 것이다.
 * 종료를 확인하지 못하면 살아 있는 트랜잭션이 남았을 수 있으므로 종료 미확인 상태를 남기고,
 * 이 도우미를 쓰는 테스트는 같은 테스트 DB 의 정리를 보류한다.
 */
@Component
public class LockProbe {

    /** MySQL: NOWAIT 로 요청한 잠금을 즉시 얻지 못함 */
    private static final int LOCK_NOWAIT = 3572;
    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(30);

    public enum Result { LOCKED, ACQUIRED }

    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;

    /** 한 번이라도 확인 스레드 종료를 확인하지 못했으면 true. 되돌리지 않는다. */
    private volatile boolean unterminatedThreadLeft;

    public LockProbe(TransactionTemplate transactionTemplate, JdbcTemplate jdbcTemplate) {
        this.transactionTemplate = transactionTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** sql 은 {@code ... FOR UPDATE NOWAIT} 로 끝나는 단일 파라미터 SELECT 다. */
    public Result probe(String sql, Long id) {
        return runConfirmingTermination(() -> transactionTemplate.execute(status -> {
            try {
                jdbcTemplate.queryForList(sql, id);
                return Result.ACQUIRED;
            } catch (DataAccessException e) {
                if (e.getMostSpecificCause() instanceof SQLException sqlException
                    && sqlException.getErrorCode() == LOCK_NOWAIT) {
                    return Result.LOCKED;
                }
                throw e;
            }
        }), RESULT_TIMEOUT, TERMINATION_TIMEOUT);
    }

    /** 지금까지 시작한 확인 스레드의 종료를 모두 확인했으면 true. false 면 테스트 DB 정리·재사용을 안전하다고 보지 않는다. */
    public boolean allThreadsTerminated() {
        return !unterminatedThreadLeft;
    }

    /**
     * 작업을 별도 스레드에서 실행해 결과를 기다린 뒤 shutdownNow 하고 스레드 종료를 확인한다.
     * 종료를 확인하지 못하면(대기 시간 초과·대기 중단) 종료 미확인 상태를 남기고 예외를 던진다.
     */
    <T> T runConfirmingTermination(Callable<T> task, Duration resultTimeout, Duration terminationTimeout) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        T result = null;
        Exception failure = null;
        try {
            result = executor.submit(task).get(resultTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = e;
        } catch (ExecutionException | TimeoutException e) {
            failure = e;
        }

        executor.shutdownNow();
        if (!awaitTermination(executor, terminationTimeout)) {
            unterminatedThreadLeft = true;
            throw new IllegalStateException("잠금 확인 스레드 종료 미확인: 테스트 DB 정리·재사용을 보류한다", failure);
        }
        if (failure != null) {
            throw new IllegalStateException("잠금 확인 실패", failure);
        }
        return result;
    }

    /** 종료를 확인한 경우에만 true. 대기가 중단되면 인터럽트 상태를 복원하고 미확인(false)으로 둔다. */
    private static boolean awaitTermination(ExecutorService executor, Duration timeout) {
        try {
            return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
