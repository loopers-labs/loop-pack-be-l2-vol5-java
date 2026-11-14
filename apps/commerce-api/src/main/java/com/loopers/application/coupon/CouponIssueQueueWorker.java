package com.loopers.application.coupon;

import com.loopers.application.coupon.CouponIssueExecution.AlreadyIssued;
import com.loopers.application.coupon.CouponIssueExecution.Failed;
import com.loopers.application.coupon.CouponIssueExecution.Issued;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponIssueRequest;
import com.loopers.domain.coupon.CouponIssueRequestReason;
import com.loopers.domain.coupon.CouponNotIssuableException;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 대기열 작업 하나를 처리한다. 실행 자리(permit)를 먼저 얻은 뒤 작업을 가져오고(Q-03),
 * 기존 발급 실행으로 처리한 결과를 같은 실행 token으로 기록한다.
 * 결과를 확인할 수 없는 오류에서는 신규 수용을 닫고 작업과 token을 그대로 남겨 수동으로 확인한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueQueueWorker {

    private final CouponIssueQueue queue;
    private final CouponIssuePermits permits;
    private final CouponIssueExecutor executor;
    private final CouponRepository coupons;
    private final CouponIssueAdmissionState admission;
    private final CouponIssueProperties properties;

    public CouponIssueQueueWorkResult runOnce() {
        if (!admission.isOpen()) {
            return CouponIssueQueueWorkResult.ADMISSION_CLOSED;
        }
        try {
            if (!queue.hasWaiting()) {
                return CouponIssueQueueWorkResult.IDLE;
            }
        } catch (CouponIssueQueueUnavailableException | CouponIssueQueueScriptException exception) {
            // 읽기만 실패했으므로 바뀐 상태가 없다. 다음 주기에 다시 확인한다.
            log.warn("대기 작업을 확인하지 못했습니다. 다음 주기에 다시 확인합니다.", exception);
            return CouponIssueQueueWorkResult.IDLE;
        }

        String token = UUID.randomUUID().toString();
        boolean acquired;
        try {
            acquired = permits.tryAcquire(token, properties.requireCapacity());
        } catch (RuntimeException exception) {
            // Redis가 token을 추가하고 응답만 잃었을 수 있으므로 0 응답처럼 다루지 않는다. 작업은 가져오지 않았다.
            return stop("worker의 permit 획득 결과를 확인하지 못했습니다. 작업은 대기 상태로 남습니다.", token, exception);
        }
        if (!acquired) {
            return CouponIssueQueueWorkResult.NO_PERMIT;
        }

        CouponIssueQueueClaim claim;
        try {
            claim = queue.claim(token);
        } catch (CouponIssueQueueUnavailableException | CouponIssueQueueScriptException exception) {
            // 작업을 가져갔는지 모르므로 DB 발급은 시작하지 않는다. DB 작업이 없으니 자기 permit은 반환한다.
            executor.returnPermit(token);
            return stop("작업 가져오기 결과를 확인하지 못했습니다. 저장된 작업 상태와 실행 token을 확인해야 합니다.",
                token, exception);
        }
        return switch (claim) {
            case CouponIssueQueueClaim.Empty empty -> {
                executor.returnPermit(token);
                yield CouponIssueQueueWorkResult.IDLE;
            }
            case CouponIssueQueueClaim.Claimed claimed -> process(claimed.request(), token);
        };
    }

    private CouponIssueQueueWorkResult process(CouponIssueRequest request, String token) {
        CouponIssueExecution execution = executor.execute(request.getCampaignId(), request.getUserId(), token);
        return switch (execution) {
            case Issued issued -> record(request, token, "SUCCEEDED",
                () -> queue.recordSucceeded(request.getRequestId(), token, issued.coupon().couponId()));
            case AlreadyIssued already -> record(request, token, "SUCCEEDED",
                () -> queue.recordSucceeded(request.getRequestId(), token, already.coupon().getId()));
            case Failed failed when !failed.dbWorkDone() -> record(request, token, "UNKNOWN",
                () -> queue.recordUnknown(request.getRequestId(), token));
            case Failed failed -> recordFinishedFailure(request, token, failed.failure());
        };
    }

    /**
     * DB 작업은 끝났다. commit 뒤 다른 처리에서 예외가 났을 수 있으므로 실제 쿠폰을 먼저 확인하고,
     * 쿠폰이 없을 때만 예외로 실패 사유를 정한다. 쿠폰을 확인하지 못하면 실제 결과를 모르므로 결과 불명으로 남긴다.
     */
    private CouponIssueQueueWorkResult recordFinishedFailure(CouponIssueRequest request, String token,
                                                            RuntimeException failure) {
        Optional<Coupon> coupon;
        try {
            coupon = coupons.findIssuedByCampaignIdAndUserId(request.getCampaignId(), request.getUserId());
        } catch (RuntimeException exception) {
            log.error("발급 실패 뒤 쿠폰을 확인하지 못해 결과 불명으로 기록합니다. requestId={}", request.getRequestId(),
                exception);
            return record(request, token, "UNKNOWN", () -> queue.recordUnknown(request.getRequestId(), token));
        }
        if (coupon.isPresent()) {
            return record(request, token, "SUCCEEDED",
                () -> queue.recordSucceeded(request.getRequestId(), token, coupon.get().getId()));
        }
        CouponIssueRequestReason reason = failureReason(failure);
        if (reason == CouponIssueRequestReason.DB_ERROR || reason == CouponIssueRequestReason.INTERNAL_ERROR) {
            log.warn("대기열 작업이 시스템 실패로 끝났습니다. 사용자가 다시 접수하면 다시 대기합니다. requestId={}, reason={}",
                request.getRequestId(), reason, failure);
        }
        return record(request, token, "FAILED", () -> queue.recordFailed(request.getRequestId(), token, reason));
    }

    private static CouponIssueRequestReason failureReason(RuntimeException failure) {
        if (failure instanceof CouponNotIssuableException notIssuable) {
            return CouponIssueRequestReason.from(notIssuable.getReason());
        }
        if (failure instanceof CoreException core && core.getErrorType() == ErrorType.NOT_FOUND) {
            return CouponIssueRequestReason.CAMPAIGN_NOT_FOUND;
        }
        if (failure instanceof DataAccessException || failure instanceof TransactionException) {
            return CouponIssueRequestReason.DB_ERROR;
        }
        return CouponIssueRequestReason.INTERNAL_ERROR;
    }

    private CouponIssueQueueWorkResult record(CouponIssueRequest request, String token, String expected,
                                              Supplier<CouponIssueQueueRecord> command) {
        CouponIssueQueueRecord result;
        try {
            result = command.get();
        } catch (CouponIssueQueueUnavailableException | CouponIssueQueueScriptException exception) {
            CouponIssueQueueWorkResult stopped = stop("작업 결과 기록을 확인하지 못했습니다. 기록 결과를 추측하지 않고 수동으로 확인해야 "
                + "합니다. requestId=" + request.getRequestId() + ", expected=" + expected, token, exception);
            logStoredState(request.getRequestId());
            return stopped;
        }
        return switch (result) {
            case CouponIssueQueueRecord.Applied applied -> CouponIssueQueueWorkResult.PROCESSED;
            case CouponIssueQueueRecord.AlreadyApplied already -> CouponIssueQueueWorkResult.PROCESSED;
            case CouponIssueQueueRecord.Rejected rejected -> stop("다른 실행이 작업을 소유하거나 상태가 맞지 않아 결과를 기록하지 "
                + "못했습니다. requestId=" + request.getRequestId() + ", currentStatus="
                + rejected.request().getStatus() + ", expected=" + expected, token, null);
        };
    }

    /**
     * 기록 응답을 받지 못했을 때 쓰기 없이 저장된 상태를 한 번 읽어 남긴다. 결과를 바꾸거나 다시 쓰지 않는다.
     */
    private void logStoredState(String requestId) {
        try {
            queue.findById(requestId).ifPresentOrElse(
                stored -> log.error("기록 응답을 받지 못한 작업의 저장 상태입니다. requestId={}, status={}, executionToken={}",
                    requestId, stored.getStatus(), stored.getExecutionToken()),
                () -> log.error("기록 응답을 받지 못한 작업의 저장 정보가 없습니다. requestId={}", requestId));
        } catch (RuntimeException exception) {
            log.error("기록 응답을 받지 못한 작업의 저장 상태도 읽지 못했습니다. requestId={}", requestId, exception);
        }
    }

    private CouponIssueQueueWorkResult stop(String message, String token, Throwable cause) {
        admission.close();
        log.error("{} 신규 수용을 닫았습니다. executionToken={}", message, token, cause);
        return CouponIssueQueueWorkResult.STOPPED;
    }
}
