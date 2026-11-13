package com.loopers.application.coupon.service;

import com.loopers.application.coupon.command.CouponIssueCommand;
import com.loopers.application.coupon.result.CouponIssueResult;
import com.loopers.application.coupon.support.CouponSlotBusyException;
import com.loopers.application.coupon.usecase.IssueCouponUseCase;
import com.loopers.domain.coupon.exception.DuplicateCouponIssueException;
import com.loopers.domain.support.error.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IssueCouponService implements IssueCouponUseCase {
    private final IssueCouponTxService txService;
    private final CouponIssueLookupService lookupService;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CouponIssueResult execute(CouponIssueCommand command) {
        try {
            return issueAndResolveSlot(command);
        } catch (DomainException refusal) {
            // 최초 조회 직후 경쟁 요청이 완료된 경우도 저장된 결과가 업무 거절보다 우선한다.
            return switch (refusal.getErrorCode()) {
                case COUPON_SOLD_OUT, COUPON_CAMPAIGN_INACTIVE, COUPON_CAMPAIGN_NOT_STARTED, COUPON_CAMPAIGN_ENDED ->
                    lookupService.findExisting(command).orElseThrow(() -> refusal);
                default -> throw refusal;
            };
        }
    }

    private CouponIssueResult issueAndResolveSlot(CouponIssueCommand command) {
        try {
            return txService.execute(command);
        } catch (DuplicateCouponIssueException duplicate) {
            // TX 프록시의 rollback이 끝난 뒤 조회한다. 다른 제약/DB 장애는 중복으로 바꾸지 않는다.
            return lookupService.findExisting(command).orElseThrow(() -> duplicate);
        } catch (CouponSlotBusyException busy) {
            return lookupService.resolveUnavailableSlot(command);
        }
    }
}
