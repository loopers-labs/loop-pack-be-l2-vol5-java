package com.loopers.application.coupon;

import com.loopers.application.coupon.CouponIssueQueueResult.Accepted;
import com.loopers.application.coupon.CouponIssueQueueResult.Completed;
import com.loopers.application.coupon.CouponIssueQueueResult.CouponAlreadyIssued;
import com.loopers.application.time.ApplicationTime;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponIssueRequest;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 쿠폰 발급 대기열 접수와 본인 작업 조회. 접수는 작업 저장만 뜻하며 발급은 worker가 처리한다.
 * 대기열 접수 오류는 발급 실행의 신규 수용 상태를 닫지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueQueueFacade {

    private final CouponIssueQueue queue;
    private final CouponRepository coupons;
    private final CouponCampaignRepository campaigns;
    private final CouponIssueAdmissionState admission;
    private final CouponIssueQueueProperties properties;
    private final ApplicationTime applicationTime;

    public CouponIssueQueueResult submit(Long campaignId, Long userId) {
        Optional<CouponIssueRequest> existing;
        try {
            existing = queue.findByCampaignIdAndUserId(campaignId, userId);
        } catch (CouponIssueQueueUnavailableException unavailable) {
            // 이미 발급된 쿠폰은 결과가 확정됐으므로 대기열을 읽지 못해도 알려 줄 수 있다.
            return issuedCoupon(campaignId, userId).orElseThrow(() -> queueUnavailable(unavailable));
        } catch (CouponIssueQueueScriptException inconsistent) {
            throw queueInconsistent(inconsistent);
        }
        if (existing.isPresent()) {
            return respondToExisting(existing.get());
        }

        Optional<CouponIssueQueueResult> issued = issuedCoupon(campaignId, userId);
        if (issued.isPresent()) {
            return issued.get();
        }

        try {
            requireNewIssuable(campaignId);
        } catch (CoreException rejection) {
            return recheckBeforeRejecting(campaignId, userId, rejection);
        }
        CouponIssueRequest request = CouponIssueRequest.create(campaignId, userId, applicationTime.now().toInstant());
        CouponIssueQueueSubmission submission = queueCall(() -> queue.submit(request, properties.requireCapacity()));
        return switch (submission) {
            case CouponIssueQueueSubmission.Created created -> new Accepted(created.request());
            case CouponIssueQueueSubmission.Existing other -> currentState(other.request());
            case CouponIssueQueueSubmission.Full full -> recheckBeforeRejecting(campaignId, userId,
                new CoreException(ErrorType.TOO_MANY_REQUESTS, "쿠폰 발급 대기열이 가득 찼습니다."));
        };
    }

    public CouponIssueRequest getOwn(String requestId, Long userId) {
        return queueCall(() -> queue.findById(requestId))
            .filter(request -> request.isOwnedBy(userId))
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰 발급 요청을 찾을 수 없습니다."));
    }

    private CouponIssueQueueResult respondToExisting(CouponIssueRequest existing) {
        return switch (existing.resubmission()) {
            case RETURN_IN_PROGRESS -> new Accepted(existing);
            case RETURN_RESULT -> new Completed(existing);
            case REQUEUE -> resubmitSystemFailure(existing);
        };
    }

    /**
     * 시스템 실패로 끝난 작업을 사용자가 다시 접수했다. 그사이 발급된 쿠폰이 있으면 성공으로 바꾸고,
     * 없으면 새 접수와 같은 확인을 거쳐 같은 작업 ID로 다시 대기시킨다. 거절되면 작업은 실패 그대로 둔다.
     */
    private CouponIssueQueueResult resubmitSystemFailure(CouponIssueRequest failed) {
        Optional<Coupon> coupon = coupons.findIssuedByCampaignIdAndUserId(failed.getCampaignId(), failed.getUserId());
        if (coupon.isPresent()) {
            return afterResubmission(queueCall(() ->
                queue.markFailedSucceeded(failed.getRequestId(), failed.getReason(), coupon.get().getId())));
        }
        requireNewIssuable(failed.getCampaignId());
        CouponIssueQueueResubmission result = queueCall(() ->
            queue.requeueFailed(failed.getRequestId(), failed.getReason(), properties.requireCapacity()));
        if (result instanceof CouponIssueQueueResubmission.Requeued) {
            log.info("시스템 실패로 끝난 대기열 작업을 사용자 재접수로 다시 대기시킵니다. requestId={}, previousReason={}, "
                + "previousExecutionToken={}", failed.getRequestId(), failed.getReason(), failed.getExecutionToken());
        }
        return afterResubmission(result);
    }

    private CouponIssueQueueResult afterResubmission(CouponIssueQueueResubmission result) {
        return switch (result) {
            case CouponIssueQueueResubmission.Requeued requeued -> new Accepted(requeued.request());
            case CouponIssueQueueResubmission.Succeeded succeeded -> new Completed(succeeded.request());
            case CouponIssueQueueResubmission.Changed changed -> currentState(changed.request());
            case CouponIssueQueueResubmission.Full full ->
                throw new CoreException(ErrorType.TOO_MANY_REQUESTS, "쿠폰 발급 대기열이 가득 찼습니다.");
        };
    }

    /**
     * 사전 확인 중 다른 요청이 먼저 접수하거나 발급했을 수 있으므로, 거절하기 전에 작업과 쿠폰을 한 번 더 확인한다.
     */
    private CouponIssueQueueResult recheckBeforeRejecting(Long campaignId, Long userId, CoreException rejection) {
        Optional<CouponIssueRequest> request = queueCall(() -> queue.findByCampaignIdAndUserId(campaignId, userId));
        if (request.isPresent()) {
            return currentState(request.get());
        }
        return issuedCoupon(campaignId, userId).orElseThrow(() -> rejection);
    }

    /**
     * 다시 대기시키지 않고 현재 상태 그대로 응답한다.
     */
    private static CouponIssueQueueResult currentState(CouponIssueRequest request) {
        return request.getStatus().isUnfinished() ? new Accepted(request) : new Completed(request);
    }

    private void requireNewIssuable(Long campaignId) {
        CouponCampaign campaign = campaigns.findById(campaignId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰 캠페인을 찾을 수 없습니다."));
        campaign.validateIssuable(applicationTime.now().toInstant());
        admission.requireOpen();
    }

    private Optional<CouponIssueQueueResult> issuedCoupon(Long campaignId, Long userId) {
        return coupons.findIssuedByCampaignIdAndUserId(campaignId, userId)
            .map(coupon -> new CouponAlreadyIssued(toInfo(coupon)));
    }

    private CouponIssueInfo toInfo(Coupon coupon) {
        CouponCampaign campaign = campaigns.findById(coupon.getCampaignId())
            .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "쿠폰의 캠페인 정보를 찾지 못했습니다."));
        return CouponIssueInfo.from(coupon, campaign);
    }

    private static <T> T queueCall(Supplier<T> call) {
        try {
            return call.get();
        } catch (CouponIssueQueueUnavailableException unavailable) {
            throw queueUnavailable(unavailable);
        } catch (CouponIssueQueueScriptException inconsistent) {
            throw queueInconsistent(inconsistent);
        }
    }

    private static CoreException queueUnavailable(CouponIssueQueueUnavailableException cause) {
        log.warn("쿠폰 발급 대기열 상태를 확인할 수 없습니다.", cause);
        return new CoreException(ErrorType.SERVICE_UNAVAILABLE, "쿠폰 발급 대기열 상태를 확인할 수 없습니다.");
    }

    private static CoreException queueInconsistent(CouponIssueQueueScriptException cause) {
        log.error("쿠폰 발급 대기열 저장 정보가 올바르지 않습니다.", cause);
        return new CoreException(ErrorType.INTERNAL_ERROR, "쿠폰 발급 대기열 저장 정보가 올바르지 않습니다.");
    }
}
