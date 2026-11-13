package com.loopers.application.coupon.service;

import com.loopers.application.coupon.command.CouponIssueCommand;
import com.loopers.application.coupon.result.CouponIssueResult;
import com.loopers.application.coupon.usecase.IssueCouponUseCase;
import com.loopers.domain.coupon.repository.CouponIssueCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 원본 DB commit 및 permit 반환이 확인된 뒤만 집계한다. 영속 전달 보장은 재집계로 보완한다. */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class CountingIssueCouponUseCase implements IssueCouponUseCase {
    private final IssueCouponFacade issuance;
    private final CouponIssueCounter counter;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CouponIssueResult execute(CouponIssueCommand command) {
        CouponIssueResult result = issuance.execute(command);
        try {
            counter.recordIssued(result.campaignId(), result.couponId());
        } catch (RuntimeException failure) {
            log.warn("coupon campaign={} coupon={} stage=COUNTER_REBUILD_REQUIRED",
                result.campaignId(), result.couponId(), failure);
        }
        return result;
    }
}
