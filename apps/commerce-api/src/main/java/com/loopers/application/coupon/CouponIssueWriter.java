package com.loopers.application.coupon;

import com.loopers.application.coupon.dto.CouponWriteResult;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.ZonedDateTime;

/**
 * 당첨자의 쿠폰을 한 트랜잭션으로 DB 에 저장한다. 쿠폰 insert 와 발급 수 증가는 같이 commit, 같이 rollback 된다.
 *
 * <p>유일 제약 충돌은 rollback 뒤 새 트랜잭션에서 기존 쿠폰을 확인한 경우에만 중복으로 본다. 연결 오류 등 다른 예외는 그대로 던진다.
 */
@Slf4j
@Component
public class CouponIssueWriter {

    private final CouponService couponService;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readOnlyTransaction;
    // 발급 TX 한 건의 시간(T_tx). 수용량 C 와 대기 시간을 정하는 공식의 입력이다.
    private final Timer writeTimer;

    public CouponIssueWriter(
        CouponService couponService, Clock clock, PlatformTransactionManager transactionManager, MeterRegistry meterRegistry
    ) {
        this.couponService = couponService;
        this.clock = clock;
        this.writeTimer = Timer.builder("coupon.issue.write")
            .description("당첨자 쿠폰 저장 TX 시간")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(meterRegistry);
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    public CouponWriteResult write(Long campaignId, Long userId) {
        try {
            Timer.Sample sample = Timer.start();
            Coupon coupon;
            try {
                coupon = writeTransaction.execute(status ->
                    couponService.issue(campaignId, userId, ZonedDateTime.now(clock)));
            } finally {
                sample.stop(writeTimer);
            }
            return CouponWriteResult.issued(coupon.getId());
        } catch (CoreException e) {
            if (e.getErrorType() == ErrorType.COUPON_SOLD_OUT) {
                return CouponWriteResult.quotaExhausted();
            }
            throw e;
        } catch (DataIntegrityViolationException e) {
            Coupon existing = readOnlyTransaction.execute(status -> couponService.findIssued(campaignId, userId).orElse(null));
            if (existing == null) {
                throw e;
            }
            return CouponWriteResult.alreadyIssued(existing.getId());
        }
    }

    /** 202 로 약속하기 전에 당첨 기록을 남긴다. 이미 있으면 같은 약속이므로 성공으로 본다. 다른 실패는 그대로 던진다. */
    public void recordTicket(Long campaignId, Long userId) {
        try {
            writeTransaction.executeWithoutResult(status ->
                couponService.recordTicket(campaignId, userId, ZonedDateTime.now(clock)));
        } catch (DataIntegrityViolationException e) {
            log.info("이미 당첨 기록이 있다. campaignId={}, userId={}", campaignId, userId);
        }
    }
}
