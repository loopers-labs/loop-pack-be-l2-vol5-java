package com.loopers.application.coupon;

import com.loopers.application.coupon.CouponIssueExecution.AlreadyIssued;
import com.loopers.application.coupon.CouponIssueExecution.Failed;
import com.loopers.application.coupon.CouponIssueExecution.Issued;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.DuplicateCouponIssueException;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * permit을 이미 얻은 발급 실행을 처리한다. 동기 API와 대기열 worker가 함께 사용한다.
 * DB 작업의 종료는 예외 종류가 아니라 트랜잭션 완료 상태로 판단하고(D-24), 종료를 확인했을 때만 token을 반환한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueExecutor {

    private final CouponRepository coupons;
    private final CouponIssuePermits permits;
    private final CouponIssueTransaction transaction;
    private final CouponIssueAdmissionState admission;

    public CouponIssueExecution execute(Long campaignId, Long userId, String token) {
        CouponIssueTransactionCompletion completion = new CouponIssueTransactionCompletion();
        CouponIssueInfo issued;
        try {
            issued = transaction.issue(campaignId, userId, completion);
        } catch (RuntimeException | Error failure) {
            boolean dbWorkDone = returnPermitIfDbWorkDone(campaignId, token, completion, failure);
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof DuplicateCouponIssueException) {
                return existingCouponAfterDuplicate(campaignId, userId, dbWorkDone);
            }
            return new Failed((RuntimeException) failure, dbWorkDone);
        }
        returnPermitSafely(token);
        return new Issued(issued);
    }

    /**
     * 중복 충돌로 rollback한 뒤 새 조회로 기존 쿠폰을 찾는다. 조회도 실패하면 결과를 돌려주는 계약을 지키도록 실패로 감싼다.
     */
    private CouponIssueExecution existingCouponAfterDuplicate(Long campaignId, Long userId, boolean dbWorkDone) {
        try {
            return coupons.findIssuedByCampaignIdAndUserId(campaignId, userId)
                .<CouponIssueExecution>map(AlreadyIssued::new)
                .orElseGet(() -> new Failed(new CoreException(ErrorType.INTERNAL_ERROR,
                    "중복 발급 충돌 뒤 기존 쿠폰을 찾지 못했습니다."), dbWorkDone));
        } catch (RuntimeException lookupFailure) {
            return new Failed(lookupFailure, dbWorkDone);
        }
    }

    /**
     * 끝났는지 확인하지 못한 실행의 token은 자리를 계속 점유하게 두고, 정리할 수 있도록 내부 실행 ID인 token을 기록한다.
     */
    private boolean returnPermitIfDbWorkDone(Long campaignId, String token,
                                             CouponIssueTransactionCompletion completion, Throwable failure) {
        if (completion.isDbWorkDone()) {
            returnPermitSafely(token);
            return true;
        }
        log.error("발급 트랜잭션의 DB 작업 종료를 확인하지 못해 permit을 반환하지 않습니다. "
            + "작업 종료와 발급 수를 확인한 뒤 이 token만 정리해야 합니다. campaignId={}, permitToken={}",
            campaignId, token, failure);
        return false;
    }

    /**
     * 자기 token을 반환한다. 첫 반환 결과가 1이 아니거나 반환 중 오류가 나면 신규 수용을 닫는다.
     * DB 작업이 없거나 끝난 것을 확인한 실행만 호출한다.
     */
    public void returnPermit(String token) {
        returnPermitSafely(token);
    }

    private void returnPermitSafely(String token) {
        try {
            if (permits.release(token) != 1) {
                closeAfterReturnProblem("permit을 반환하지 못해 신규 발급을 닫았습니다.", null);
            }
        } catch (RuntimeException exception) {
            closeAfterReturnProblem("permit 반환 중 오류가 발생해 신규 발급을 닫았습니다.", exception);
        }
    }

    private void closeAfterReturnProblem(String message, RuntimeException exception) {
        admission.close();
        if (exception == null) {
            log.error(message);
        } else {
            log.error(message, exception);
        }
    }
}
