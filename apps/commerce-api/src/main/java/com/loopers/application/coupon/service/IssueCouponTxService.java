package com.loopers.application.coupon.service;

import com.loopers.application.coupon.command.CouponIssueCommand;
import com.loopers.application.coupon.port.CouponDatabaseClock;
import com.loopers.application.coupon.port.CouponTransactionObserver;
import com.loopers.application.coupon.support.CouponExecution;
import com.loopers.application.coupon.result.CouponIssueResult;
import com.loopers.application.coupon.support.CouponSlotBusyException;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.coupon.model.Coupon;
import com.loopers.domain.coupon.model.CouponCampaign;
import com.loopers.domain.coupon.model.CouponSlot;
import com.loopers.domain.coupon.repository.CouponCampaignRepository;
import com.loopers.domain.coupon.repository.CouponRepository;
import com.loopers.domain.coupon.repository.CouponSlotRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IssueCouponTxService {
    private final CouponRepository couponRepository;
    private final CouponCampaignRepository campaignRepository;
    private final CouponSlotRepository slotRepository;
    private final CouponDatabaseClock databaseClock;
    private final CouponTransactionObserver observer;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public CouponIssueResult execute(CouponIssueCommand command) {
        return execute(command, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public CouponIssueResult execute(CouponIssueCommand command, CouponExecution execution) {
        observer.observe(execution);
        Optional<Coupon> existing = couponRepository.findByCampaignIdAndUserId(command.campaignId(), command.userId());
        if (existing.isPresent()) {
            return CouponIssueResult.from(existing.get(), false);
        }
        CouponCampaign snapshot = campaignRepository.findById(command.campaignId())
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.COUPON_CAMPAIGN_NOT_FOUND));
        snapshot.ensureIssuable(databaseClock.now());
        CouponSlot slot = slotRepository.findAvailableForUpdateSkipLocked(command.campaignId())
            .orElseThrow(CouponSlotBusyException::new);

        Coupon saved = couponRepository.save(Coupon.create(command.campaignId(), slot.getId(), command.userId(),
            snapshot.getBenefit(), databaseClock.now()));
        slot.markIssued();

        CouponCampaign current = campaignRepository.findByIdForIssue(command.campaignId())
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.COUPON_CAMPAIGN_NOT_FOUND));
        // 대기 전 SQL 시각을 재사용하지 않고 현재 행 잠금 뒤 같은 연결에서 새로 조회한다.
        Instant decisionTime = databaseClock.now();
        current.recordIssue(decisionTime);
        // 슬롯의 FK 지원 인덱스 변경이 캠페인 공유 잠금을 선취하지 않도록 실제 UPDATE는 현재 행 잠금 뒤 수행한다.
        slotRepository.save(slot);
        campaignRepository.save(current);
        return CouponIssueResult.from(saved, true);
    }
}
