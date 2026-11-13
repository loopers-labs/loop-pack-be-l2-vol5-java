package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponIssueCancellationModel;
import com.loopers.domain.coupon.CouponIssueCancellationRepository;
import com.loopers.domain.coupon.CouponIssueGate;
import com.loopers.domain.coupon.CouponIssueJob;
import com.loopers.domain.coupon.CouponIssueJobs;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 작업 큐 모드의 Worker. 접수된 발급 작업을 꺼내 DB에 저장하고, Redis 예약을 확정한 뒤 처리 완료로 표시한다.
 *
 * <ul>
 *   <li>확인 순서는 취소 기록 → 기존 쿠폰 → 사용자다. 같은 작업을 다시 처리할 때 이미 확정한 결과(취소·발급)를
 *       지금 조건으로 다시 판단해 뒤집지 않는다.</li>
 *   <li>기존 쿠폰이 이 작업의 예약 값으로 만든 것이면 이 작업의 앞선 시도가 만든 쿠폰이라 수량을 돌려주지 않는다.
 *       다른 쿠폰이면 접수 전부터 있던 쿠폰이라 이번 예약의 수량을 돌려준다.</li>
 *   <li>확정 거절(없는 사용자, DB 매진·비활성·종료·없는 캠페인)은 취소 기록 + 예약 확정 취소(C) + 처리 완료다.</li>
 *   <li>연결 장애(DB·Redis)는 처리 완료하지 않고 Worker 전체가 간격을 늘려 가며 쉬었다가 다시 시도한다. 횟수로 보류하지 않는다.</li>
 *   <li>그 밖의 실패가 한 작업에서 max-attempts번 쌓이면 보류함으로 옮겨 뒤 작업을 막지 않는다. 예약(권리)은 지우지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class CouponIssueWorker {
    private final CouponIssueJobs jobs;
    private final CouponIssueGate gate;
    private final CouponIssueTransaction transaction;
    private final CouponRepository coupons;
    private final CouponIssueCancellationRepository cancellations;
    private final UserService users;
    private final int batchSize;
    private final boolean saveInBatch;
    private final int maxAttempts;
    private final long backoffInitialNanos;
    private final long backoffMaxNanos;

    /** 작업별 실패 횟수(연결 장애 제외). 이 Worker가 재시작하면 다시 센다. */
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();
    private volatile long pausedUntilNanos;
    private volatile int consecutiveInfraFailures;

    public CouponIssueWorker(CouponIssueJobs jobs, CouponIssueGate gate, CouponIssueTransaction transaction,
        CouponRepository coupons, CouponIssueCancellationRepository cancellations, UserService users,
        @Value("${commerce.coupon.issue-queue.worker.batch-size:10}") int batchSize,
        @Value("${commerce.coupon.issue-queue.worker.save-in-batch:true}") boolean saveInBatch,
        @Value("${commerce.coupon.issue-queue.worker.max-attempts:5}") int maxAttempts,
        @Value("${commerce.coupon.issue-queue.worker.backoff-initial:100ms}") Duration backoffInitial,
        @Value("${commerce.coupon.issue-queue.worker.backoff-max:30s}") Duration backoffMax) {
        if (maxAttempts < 1) throw new IllegalArgumentException("Coupon worker max attempts must be positive");
        this.jobs = jobs;
        this.gate = gate;
        this.transaction = transaction;
        this.coupons = coupons;
        this.cancellations = cancellations;
        this.users = users;
        this.batchSize = batchSize;
        this.saveInBatch = saveInBatch;
        this.maxAttempts = maxAttempts;
        this.backoffInitialNanos = backoffInitial.toNanos();
        this.backoffMaxNanos = backoffMax.toNanos();
    }

    /** 작업을 한 번 가져와 처리한다. 처리 완료로 표시한 작업 수를 돌려준다. 연결 장애로 쉬는 중이면 0이다. */
    public int processOnce() {
        if (System.nanoTime() < pausedUntilNanos) return 0;
        List<CouponIssueJob> polled;
        try {
            polled = jobs.poll(batchSize);
        } catch (RuntimeException failure) {
            pauseAfterInfraFailure(failure);
            return 0;
        }
        if (polled.isEmpty()) return 0;
        if (saveInBatch) return processBatch(polled);
        return processEach(polled);
    }

    /**
     * 가져온 작업들을 한 DB TX로 저장한다. 연결 장애나 결과 불명이면 모두 다음에 다시 처리한다.
     * TX가 그 밖의 이유로 실패하면 한 건씩 처리로 넘겨, 문제 작업 하나가 나머지를 막지 않게 한다.
     */
    private int processBatch(List<CouponIssueJob> polled) {
        CouponIssueExecution execution = new CouponIssueExecution();
        List<CouponIssueOutcome> outcomes;
        try {
            outcomes = transaction.issueAcceptedBatch(polled, execution);
        } catch (RuntimeException failure) {
            if (isInfrastructure(failure) || (execution.isStarted() && !execution.isCompleted())) {
                pauseAfterInfraFailure(failure);
                return 0;
            }
            log.warn("Coupon issue batch failed; processing {} jobs one by one", polled.size(), failure);
            return processEach(polled);
        }
        int completed = 0;
        for (CouponIssueOutcome outcome : outcomes) {
            boolean done = switch (outcome.kind()) {
                case REJECTED -> cancel(outcome.job(), outcome.reason());
                case CREATED -> confirmAndAcknowledge(outcome.job(), true);
                case EXISTING -> confirmAndAcknowledge(outcome.job(), issuedBy(outcome.job(), outcome.coupon()));
            };
            if (done) completed++;
        }
        return completed;
    }

    private int processEach(List<CouponIssueJob> polled) {
        int completed = 0;
        for (CouponIssueJob job : polled) {
            if (System.nanoTime() < pausedUntilNanos) break;
            if (process(job)) completed++;
        }
        return completed;
    }

    private boolean process(CouponIssueJob job) {
        try {
            var cancelled = cancellations.find(job.campaignId(), job.userId());
            if (cancelled.isPresent()) return cancel(job, cancelled.get().getReason());
            var existing = coupons.findByCampaignIdAndUserId(job.campaignId(), job.userId());
            if (existing.isPresent()) return confirmAndAcknowledge(job, issuedBy(job, CouponInfo.from(existing.get())));
            users.get(job.userId());
        } catch (CoreException missing) {
            if (missing.getErrorType() == ErrorType.NOT_FOUND) return cancel(job, "USER_NOT_FOUND");
            return retryLater(job, missing);
        } catch (RuntimeException failure) {
            return retryLater(job, failure);
        }

        CouponIssueExecution execution = new CouponIssueExecution();
        CouponIssueResult result;
        try {
            result = transaction.issueAccepted(job.userId(), job.campaignId(), job.acceptedAt(), job.reservation(), execution);
        } catch (CoreException rejected) {
            boolean settled = execution.isCompleted() || !execution.isStarted();
            if (settled && (rejected.getErrorType() == ErrorType.CONFLICT || rejected.getErrorType() == ErrorType.NOT_FOUND)) {
                return cancel(job, reasonOf(rejected));
            }
            return retryLater(job, rejected);
        } catch (RuntimeException failure) {
            if (!(execution.isCompleted() && isCouponDuplicate(failure))) return retryLater(job, failure);
            // 같은 사용자의 쿠폰이 유일 제약에서 만났다. rollback 뒤 먼저 저장된 쿠폰을 읽는다.
            var committed = coupons.findByCampaignIdAndUserId(job.campaignId(), job.userId());
            if (committed.isEmpty()) return retryLater(job, failure);
            result = CouponIssueResult.existing(CouponInfo.from(committed.get()));
        }
        return confirmAndAcknowledge(job, result.created() || issuedBy(job, result.coupon()));
    }

    /** 쿠폰이 이 작업의 예약 값으로 저장됐으면 이 작업(의 앞선 시도)이 만든 쿠폰이다. */
    private static boolean issuedBy(CouponIssueJob job, CouponInfo coupon) {
        return Objects.equals(coupon.reservationId(), job.reservation());
    }

    private boolean confirmAndAcknowledge(CouponIssueJob job, boolean issuedByThisJob) {
        try {
            if (!gate.confirm(job.campaignId(), job.userId(), job.reservation(), issuedByThisJob)
                && gate.state(job.campaignId(), job.userId()) != CouponIssueGate.UserState.ACCEPTED) {
                // 앞선 시도가 이미 확정한 경우(A)가 아니다. 판단 정보가 사라졌거나 예약이 바뀌었다. DB 결과는 그대로 두고 대사 대상으로 남긴다.
                log.warn("Coupon reservation did not match on confirm, campaignId={}, userId={}, jobId={}",
                    job.campaignId(), job.userId(), job.id());
            }
            jobs.acknowledge(job);
            done(job);
            return true;
        } catch (RuntimeException failure) {
            return retryLater(job, failure);
        }
    }

    /** 확정 취소. 취소 기록은 한 건만 남고, 예약 확정 취소(C)도 한 번만 반영돼 여러 번 불려도 결과가 같다. */
    private boolean cancel(CouponIssueJob job, String reason) {
        try {
            cancellations.saveIfAbsent(new CouponIssueCancellationModel(job.campaignId(), job.userId(), reason));
            gate.cancelFinally(job.campaignId(), job.userId(), job.reservation());
            jobs.acknowledge(job);
            done(job);
            log.info("Coupon issue job cancelled, campaignId={}, userId={}, reason={}", job.campaignId(), job.userId(), reason);
            return true;
        } catch (RuntimeException failure) {
            return retryLater(job, failure);
        }
    }

    private boolean retryLater(CouponIssueJob job, RuntimeException failure) {
        if (isInfrastructure(failure)) {
            pauseAfterInfraFailure(failure);
            return false;
        }
        int count = attempts.merge(job.id(), 1, Integer::sum);
        if (count < maxAttempts) {
            log.warn("Coupon issue job failed ({}/{}), id={}, campaignId={}, userId={}",
                count, maxAttempts, job.id(), job.campaignId(), job.userId(), failure);
            return false;
        }
        try {
            jobs.hold(job, failure.getClass().getSimpleName());
            attempts.remove(job.id());
            log.error("Coupon issue job held after {} failures, id={}, campaignId={}, userId={}",
                count, job.id(), job.campaignId(), job.userId(), failure);
        } catch (RuntimeException holdFailure) {
            log.error("Coupon issue job could not be held, id={}", job.id(), holdFailure);
        }
        return false;
    }

    private void done(CouponIssueJob job) {
        attempts.remove(job.id());
        consecutiveInfraFailures = 0;
    }

    /** 연결 장애면 Worker 전체를 쉬게 한다. 이어질 때마다 간격을 두 배로 늘리고 최대값에서 멈춘다. */
    private void pauseAfterInfraFailure(RuntimeException failure) {
        int failures = ++consecutiveInfraFailures;
        long delay = backoffInitialNanos <= 0 ? 0 : Math.min(backoffMaxNanos, backoffInitialNanos << Math.min(failures - 1, 20));
        pausedUntilNanos = System.nanoTime() + delay;
        log.warn("Coupon issue worker paused {}ms after infrastructure failure #{}", delay / 1_000_000, failures, failure);
    }

    /** DB·Redis 연결·시간 초과처럼 모든 작업에 같이 생기는 실패. 작업 하나의 문제로 세지 않는다. */
    private static boolean isInfrastructure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException || cause instanceof TransientDataAccessException
                || cause instanceof CannotCreateTransactionException) {
                return true;
            }
        }
        return false;
    }

    private static String reasonOf(CoreException rejected) {
        if (rejected.getErrorType() == ErrorType.NOT_FOUND) return "CAMPAIGN_NOT_FOUND";
        if (CouponRejections.SOLD_OUT.equals(rejected.getMessage())) return "SOLD_OUT";
        if (CouponRejections.INACTIVE.equals(rejected.getMessage())) return "INACTIVE";
        if (CouponRejections.ENDED.equals(rejected.getMessage())) return "ENDED";
        return "REJECTED";
    }

    private static boolean isCouponDuplicate(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("uk_coupon_campaign_user")) return true;
        }
        return false;
    }
}
