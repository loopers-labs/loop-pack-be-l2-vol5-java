package com.loopers.application.coupon;

import com.loopers.application.coupon.admission.AcquireOutcome;
import com.loopers.application.coupon.admission.CouponAdmissionException;
import com.loopers.application.coupon.admission.CouponAdmissionSwitch;
import com.loopers.application.coupon.admission.CouponIssueAdmission;
import com.loopers.application.coupon.admission.CouponIssueExecutions;
import com.loopers.application.coupon.admission.ReserveOutcome;
import com.loopers.application.coupon.admission.ReserveResult;
import com.loopers.application.coupon.dto.CouponIssueInfo;
import com.loopers.application.coupon.dto.CouponWriteResult;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 선착순 쿠폰 발급. Redis 가 당첨을 정하고, 당첨자만 실행 자리(C개)를 얻어 DB 에 저장한다.
 *
 * <p>고객에게 약속한 결과(201·202)는 응답 전에 DB 에 있다. 수용 상태를 확인할 수 없으면 신규 발급을 시작하지 않고 스위치를 닫는다.
 * Redis 호출 동안 DB 연결을 쥐지 않도록 메소드에 트랜잭션을 걸지 않는다.
 */
@Slf4j
@Component
public class CouponIssueFacade {

    private static final long MIN_RETRY_MILLIS = 10;
    private static final long MAX_RETRY_MILLIS = 50;

    private final CouponIssueAdmission admission;
    private final CouponAdmissionSwitch admissionSwitch;
    private final CouponIssueExecutions executions;
    private final CouponIssueWriter writer;
    private final CouponService couponService;
    private final Duration waitTimeout;
    private final TransactionTemplate readOnlyTransaction;

    public CouponIssueFacade(
        CouponIssueAdmission admission,
        CouponAdmissionSwitch admissionSwitch,
        CouponIssueExecutions executions,
        CouponIssueWriter writer,
        CouponService couponService,
        PlatformTransactionManager transactionManager,
        @Value("${coupon.issue.wait-timeout}") Duration waitTimeout
    ) {
        this.admission = admission;
        this.admissionSwitch = admissionSwitch;
        this.executions = executions;
        this.writer = writer;
        this.couponService = couponService;
        this.waitTimeout = waitTimeout;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    public CouponIssueInfo issue(Long campaignId, Long userId) {
        if (!admissionSwitch.isOpen()) {
            return closed(campaignId, userId);
        }
        // 재개할 때 진행 중 실행이 없는지 확인할 수 있게 이 인스턴스의 실행을 센다.
        executions.start();
        try {
            return admit(campaignId, userId);
        } finally {
            executions.end();
        }
    }

    private CouponIssueInfo admit(Long campaignId, Long userId) {
        // 실행마다 새 token 을 만든다. 다른 실행과 공유하지 않는다.
        String token = UUID.randomUUID().toString();
        // 응답이 불확실해도 Redis 에 들어갔을 수 있으므로 호출 전에 기록해 두고, 확실히 아닐 때만 지운다.
        executions.hold(campaignId, token);
        ReserveResult reserved;
        try {
            reserved = admission.reserve(campaignId, userId, token);
        } catch (CouponAdmissionException e) {
            closeAdmission("당첨 판정 실패", e);
            return closed(campaignId, userId);
        }
        if (reserved.outcome() != ReserveOutcome.WON_RUNNING && reserved.outcome() != ReserveOutcome.WON_WAITING) {
            executions.release(token);
        }
        return switch (reserved.outcome()) {
            case CLOSED -> closed(campaignId, userId);
            case ISSUED -> CouponIssueInfo.existing(reserved.couponId());
            case TICKETED -> CouponIssueInfo.pending();
            case PENDING -> throw new CoreException(ErrorType.COUPON_ISSUE_IN_PROGRESS);
            case INACTIVE -> throw new CoreException(ErrorType.COUPON_CAMPAIGN_UNAVAILABLE);
            case SOLD_OUT -> throw new CoreException(ErrorType.COUPON_SOLD_OUT);
            case WON_RUNNING -> runAndFinish(campaignId, userId, token);
            case WON_WAITING -> waitThenRun(campaignId, userId, token);
        };
    }

    // 자리를 기다리는 사람은 모두 이미 당첨자라 순서를 두지 않는다. 바뀌는 것은 응답 시간뿐이다.
    private CouponIssueInfo waitThenRun(Long campaignId, Long userId, String token) {
        long deadline = System.nanoTime() + waitTimeout.toNanos();
        try {
            while (System.nanoTime() < deadline) {
                AcquireOutcome outcome = admission.acquire(campaignId, userId, token);
                switch (outcome) {
                    case ACQUIRED -> {
                        return runAndFinish(campaignId, userId, token);
                    }
                    case NOT_PENDING -> {
                        executions.release(token);
                        return existingAfterWait(campaignId, userId);
                    }
                    case CLOSED -> {
                        executions.release(token);
                        return promise(campaignId, userId);
                    }
                    case FULL -> sleepBeforeRetry();
                }
            }
            // FULL 로 끝났으면 token 은 자리에 들어가지 않았다.
            executions.release(token);
        } catch (CouponAdmissionException e) {
            // 자리를 얻었는지 모르므로 DB 에 쓰지 않는다. 당첨은 이미 확정이라 약속만 남긴다.
            closeAdmission("자리 재요청 실패", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return promise(campaignId, userId);
    }

    private CouponIssueInfo runAndFinish(Long campaignId, Long userId, String token) {
        CouponWriteResult result = null;
        try {
            result = writer.write(campaignId, userId);
        } catch (TransientDataAccessException | CannotCreateTransactionException | DataAccessResourceFailureException e) {
            log.warn("쿠폰 저장 일시 실패. 당첨을 약속으로 남긴다. campaignId={}, userId={}", campaignId, userId, e);
        } finally {
            // DB 트랜잭션이 끝난(commit 또는 rollback) 뒤에만 자리를 반환한다.
            finish(campaignId, userId, token, result == null ? null : result.couponId());
        }
        if (result == null) {
            return promise(campaignId, userId);
        }
        return switch (result.status()) {
            case ISSUED -> CouponIssueInfo.issued(result.couponId());
            case ALREADY_ISSUED -> CouponIssueInfo.existing(result.couponId());
            case QUOTA_EXHAUSTED -> {
                // Redis 가 당첨시켰는데 DB 한도가 막았다. 두 상태가 어긋났으므로 재구성 전까지 수용을 닫는다.
                closeAdmission("Redis 당첨과 DB 한도 불일치", null);
                throw new CoreException(ErrorType.INTERNAL_ERROR, "쿠폰 발급 상태를 확인하고 있습니다. 잠시 후 다시 시도해 주세요.");
            }
        };
    }

    private void finish(Long campaignId, Long userId, String token, Long couponId) {
        try {
            admission.finish(campaignId, userId, token, couponId);
            executions.release(token);
        } catch (CouponAdmissionException e) {
            // 쿠폰·발급 수는 되돌리지 않는다. token 은 남겨 두고, 관리자가 확인 뒤 재개할 때 정리한다.
            closeAdmission("자리 반환 실패", e);
        }
    }

    // 쿠폰을 바로 저장하지 못한 당첨자에게 202 를 주기 전에 DB 에 당첨 기록을 남긴다. 남기지 못하면 약속하지 않는다.
    private CouponIssueInfo promise(Long campaignId, Long userId) {
        try {
            writer.recordTicket(campaignId, userId);
        } catch (RuntimeException e) {
            log.error("당첨 기록 실패. 약속하지 않는다. campaignId={}, userId={}", campaignId, userId, e);
            throw new CoreException(ErrorType.INTERNAL_ERROR, "쿠폰 발급을 완료하지 못했습니다. 잠시 후 다시 확인해 주세요.");
        }
        try {
            admission.markTicketed(campaignId, userId);
        } catch (CouponAdmissionException e) {
            // 기록은 DB 에 있으므로 약속은 유효하다. 재구성 때 DB 에서 되살린다.
            closeAdmission("약속 표시 실패", e);
        }
        return CouponIssueInfo.pending();
    }

    // 기다리는 사이 다른 경로(마감 보정 등)가 쿠폰을 저장했다.
    private CouponIssueInfo existingAfterWait(Long campaignId, Long userId) {
        return findIssued(campaignId, userId)
            .map(coupon -> CouponIssueInfo.existing(coupon.getId()))
            .orElseGet(() -> promise(campaignId, userId));
    }

    // 수용 상태를 믿을 수 없을 때. 이미 저장된 결과만 DB 에서 돌려주고 신규 발급은 하지 않는다.
    private CouponIssueInfo closed(Long campaignId, Long userId) {
        return findIssued(campaignId, userId)
            .map(coupon -> CouponIssueInfo.existing(coupon.getId()))
            .orElseThrow(() -> {
                Boolean exists = readOnlyTransaction.execute(status -> couponService.campaignExists(campaignId));
                if (!Boolean.TRUE.equals(exists)) {
                    return new CoreException(ErrorType.NOT_FOUND, "쿠폰 캠페인을 찾을 수 없습니다.");
                }
                return new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
            });
    }

    private Optional<Coupon> findIssued(Long campaignId, Long userId) {
        return readOnlyTransaction.execute(status -> couponService.findIssued(campaignId, userId));
    }

    private void closeAdmission(String reason, Exception cause) {
        admissionSwitch.close();
        log.error("쿠폰 신규 수용을 닫는다. 관리자가 확인 뒤 다시 연다. 이유={}", reason, cause);
    }

    private static void sleepBeforeRetry() throws InterruptedException {
        Thread.sleep(ThreadLocalRandom.current().nextLong(MIN_RETRY_MILLIS, MAX_RETRY_MILLIS + 1));
    }
}
