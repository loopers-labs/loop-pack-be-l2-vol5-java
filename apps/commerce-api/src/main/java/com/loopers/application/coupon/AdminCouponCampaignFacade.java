package com.loopers.application.coupon;

import com.loopers.application.coupon.admission.AcquireOutcome;
import com.loopers.application.coupon.admission.CouponAdmissionAdmin;
import com.loopers.application.coupon.admission.CouponAdmissionException;
import com.loopers.application.coupon.admission.CouponAdmissionSwitch;
import com.loopers.application.coupon.admission.CouponIssueAdmission;
import com.loopers.application.coupon.admission.CouponIssueExecutions;
import com.loopers.application.coupon.dto.CouponCampaignInfo;
import com.loopers.application.coupon.dto.CouponReconcileInfo;
import com.loopers.application.coupon.dto.CouponWriteResult;
import com.loopers.domain.common.Money;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 관리자용 쿠폰 캠페인 유스케이스. 수용 상태는 DB 를 원본으로 다시 만들고, 확인이 끝난 뒤에만 연다.
 * Redis 호출 동안 DB 연결을 쥐지 않도록 필요한 구간만 트랜잭션으로 감싼다.
 */
@Slf4j
@Component
public class AdminCouponCampaignFacade {

    private final CouponService couponService;
    private final CouponAdmissionAdmin admissionAdmin;
    private final CouponIssueAdmission admission;
    private final CouponAdmissionSwitch admissionSwitch;
    private final CouponIssueExecutions executions;
    private final CouponIssueWriter writer;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readOnlyTransaction;

    public AdminCouponCampaignFacade(
        CouponService couponService,
        CouponAdmissionAdmin admissionAdmin,
        CouponIssueAdmission admission,
        CouponAdmissionSwitch admissionSwitch,
        CouponIssueExecutions executions,
        CouponIssueWriter writer,
        PlatformTransactionManager transactionManager
    ) {
        this.couponService = couponService;
        this.admissionAdmin = admissionAdmin;
        this.admission = admission;
        this.admissionSwitch = admissionSwitch;
        this.executions = executions;
        this.writer = writer;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    /** 캠페인을 만들고 수용 상태를 닫힌 채로 준비한다. 발급은 {@link #open} 뒤에 시작된다. */
    public CouponCampaignInfo prepare(String name, long discountAmount, int totalQuota) {
        CouponCampaign campaign = writeTransaction.execute(status ->
            couponService.prepare(name, new Money(discountAmount), totalQuota));
        runAdmission(() -> admissionAdmin.prepare(campaign.getId(), totalQuota));
        return CouponCampaignInfo.from(campaign);
    }

    /**
     * 수용을 연다. 처음 열 때와 장애 뒤 재개할 때 같은 절차다. 이 인스턴스에 진행 중인 발급이 있으면 끝났는지 알 수 없으므로
     * 거절하고 닫힌 상태를 유지한다. 당첨자 명단은 DB 에 남은 약속(쿠폰·당첨 기록)으로만 다시 만든다.
     */
    public void open(Long campaignId) {
        if (executions.inFlight() > 0) {
            throw new CoreException(ErrorType.CONFLICT, "진행 중인 쿠폰 발급이 끝난 뒤 다시 열어 주세요.");
        }
        CouponCampaign campaign = readOnlyTransaction.execute(status -> couponService.getCampaign(campaignId));
        Map<Long, String> winners = readOnlyTransaction.execute(status -> couponService.promisedWinners(campaignId));
        List<String> ownTokens = executions.unreleasedTokens(campaignId);
        runAdmission(() -> {
            admissionAdmin.suspend(campaignId);
            admissionAdmin.replaceWinners(campaignId, winners);
            admissionAdmin.releaseTokens(campaignId, ownTokens);
            admissionAdmin.open(campaignId, campaign.getTotalQuota(), campaign.isActive());
        });
        ownTokens.forEach(executions::release);
        admissionSwitch.open();
        log.info("쿠폰 수용을 열었다. campaignId={}, 당첨자={}, 정리한 token={}", campaignId, winners.size(), ownTokens.size());
    }

    /** 신규 발급을 끝낸다. Redis 를 먼저 막아, DB 만 종료되고 신규 판정이 계속되는 일이 없게 한다. */
    public void close(Long campaignId) {
        runAdmission(() -> admissionAdmin.deactivate(campaignId));
        writeTransaction.executeWithoutResult(status -> couponService.close(campaignId));
    }

    /**
     * 쿠폰이 기록되지 않은 당첨자(PENDING·TICKETED)를 마무리한다. 고객 요청과 같은 실행 자리를 받아야 저장하므로
     * 수용량 C 를 넘지 않는다. 자리가 없으면 멈추고 남은 수를 돌려준다.
     */
    public CouponReconcileInfo reconcile(Long campaignId) {
        List<Long> userIds = callAdmission(() -> admissionAdmin.findUnissuedWinners(campaignId));
        int issued = 0;
        int recorded = 0;
        int remaining = 0;
        for (int i = 0; i < userIds.size(); i++) {
            Long userId = userIds.get(i);
            Optional<Coupon> existing = readOnlyTransaction.execute(status -> couponService.findIssued(campaignId, userId));
            if (existing.isPresent()) {
                runAdmission(() -> admission.recordIssued(campaignId, userId, existing.get().getId()));
                recorded++;
                continue;
            }
            ReconcileStep step = issueOne(campaignId, userId);
            if (step == ReconcileStep.NO_SLOT) {
                remaining += userIds.size() - i;
                break;
            }
            if (step == ReconcileStep.ISSUED) {
                issued++;
            } else if (step == ReconcileStep.RECORDED) {
                recorded++;
            } else {
                remaining++;
            }
        }
        log.info("쿠폰 마감 보정. campaignId={}, 저장={}, 기록={}, 남음={}", campaignId, issued, recorded, remaining);
        return new CouponReconcileInfo(issued, recorded, remaining);
    }

    private enum ReconcileStep { ISSUED, RECORDED, NO_SLOT, FAILED }

    private ReconcileStep issueOne(Long campaignId, Long userId) {
        String token = UUID.randomUUID().toString();
        executions.start();
        executions.hold(campaignId, token);
        try {
            AcquireOutcome outcome = callAdmission(() -> admission.acquire(campaignId, userId, token));
            if (outcome != AcquireOutcome.ACQUIRED) {
                executions.release(token);
                return outcome == AcquireOutcome.NOT_PENDING ? ReconcileStep.RECORDED : ReconcileStep.NO_SLOT;
            }
            CouponWriteResult result = null;
            try {
                result = writer.write(campaignId, userId);
            } catch (RuntimeException e) {
                log.warn("마감 보정 저장 실패. 다음 보정으로 넘긴다. campaignId={}, userId={}", campaignId, userId, e);
            } finally {
                Long couponId = result == null ? null : result.couponId();
                runAdmission(() -> admission.finish(campaignId, userId, token, couponId));
                executions.release(token);
            }
            if (result == null || result.status() == CouponWriteResult.Status.QUOTA_EXHAUSTED) {
                return ReconcileStep.FAILED;
            }
            return result.status() == CouponWriteResult.Status.ISSUED ? ReconcileStep.ISSUED : ReconcileStep.RECORDED;
        } finally {
            executions.end();
        }
    }

    private void runAdmission(Runnable command) {
        callAdmission(() -> {
            command.run();
            return null;
        });
    }

    // 관리 작업 중 Redis 를 바꾸지 못하면 수용을 닫은 채 오류로 알린다. 관리자는 확인 뒤 다시 시도한다.
    private <T> T callAdmission(Supplier<T> command) {
        try {
            return command.get();
        } catch (CouponAdmissionException e) {
            admissionSwitch.close();
            log.error("쿠폰 관리 작업 중 Redis 상태를 바꾸지 못했다. 수용을 닫는다.", e);
            throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        }
    }
}
