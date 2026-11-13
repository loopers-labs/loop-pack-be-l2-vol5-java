package com.loopers.couponissue.application;

import com.loopers.couponissue.application.port.in.CouponIssueUseCase;
import com.loopers.couponissue.application.port.out.CouponIssuePort;
import com.loopers.couponissue.domain.CouponIssueMessage;
import com.loopers.couponissue.domain.IssuableCoupon;
import com.loopers.couponissue.domain.UserCoupon;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 쿠폰팩 발급 (CPN-08, D-5).
 * <ol>
 *   <li>빠른 길 — 이미 발급된 사용자면 아무것도 바꾸지 않고 끝낸다. 평소 재전달은 여기서 예외 없이 끝난다.</li>
 *   <li>한 트랜잭션: 사용자 쿠폰을 쿠폰 수만큼 INSERT → 발급 수 조건부 +1. INSERT를 먼저 해 캠페인 행 잠금을 가장 짧게 쥔다.
 *       조건부 UPDATE가 0행이면(당첨자 수에 닿음) rollback한다.</li>
 *   <li>UNIQUE(coupon_id, user_id) 위반은 동시 처리(rebalance)로 보고 "이미 발급"이다. 다른 DB 오류는 그대로 던져 재시도·DLT로 보낸다.</li>
 * </ol>
 */
@Slf4j
@Service
public class CouponIssueService implements CouponIssueUseCase {

    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    private final CouponIssuePort couponIssuePort;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CouponIssueService(CouponIssuePort couponIssuePort, PlatformTransactionManager transactionManager, Clock clock) {
        this.couponIssuePort = couponIssuePort;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public Result issue(CouponIssueMessage message) {
        Long campaignId = message.campaignId();
        Long userId = message.userId();
        if (Boolean.TRUE.equals(transaction.execute(status -> couponIssuePort.hasIssued(campaignId, userId)))) {
            log.info("이미 발급된 사용자 — 변경 없이 완료. campaignId={}, userId={}", campaignId, userId);
            return Result.ALREADY_ISSUED;
        }
        try {
            Result result = transaction.execute(status -> issueInTransaction(campaignId, userId, status));
            if (result == Result.SOLD_OUT) {
                log.warn("[COUPON_SOLD_OUT_AT_DB] 수락됐지만 발급 수가 당첨자 수에 닿아 발급하지 못했습니다 (D-2 한계). campaignId={}, userId={}",
                    campaignId, userId);
            }
            return result;
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
            log.info("동시 처리 중 다른 쪽이 먼저 발급 — 변경 없이 완료. campaignId={}, userId={}", campaignId, userId);
            return Result.ALREADY_ISSUED;
        }
    }

    private Result issueInTransaction(Long campaignId, Long userId, org.springframework.transaction.TransactionStatus status) {
        List<IssuableCoupon> coupons = couponIssuePort.findCoupons(campaignId);
        if (coupons.isEmpty()) {
            throw new IllegalStateException("캠페인에 쿠폰이 없습니다 — 없는 캠페인이거나 데이터가 깨졌습니다. campaignId=" + campaignId);
        }
        ZonedDateTime now = ZonedDateTime.now(clock);
        couponIssuePort.saveAll(coupons.stream().map(coupon -> UserCoupon.issue(coupon, campaignId, userId, now)).toList());
        if (!couponIssuePort.increaseIssuedCountIfAvailable(campaignId)) {
            status.setRollbackOnly();
            return Result.SOLD_OUT;
        }
        return Result.ISSUED;
    }

    private static boolean isDuplicateKey(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
                return true;
            }
        }
        return false;
    }
}
