package com.loopers.support.coupon;

import com.loopers.domain.coupon.CouponIssueResult;
import com.loopers.domain.coupon.CouponIssueService;
import com.loopers.domain.coupon.CouponIssueTransactions;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 지정한 사용자의 실행만 "자리를 얻은 직후, DB 발급 트랜잭션 시작 전"에 붙잡는 테스트용 서비스.
 * 붙잡는 대기에는 deadline이 있고, 시간을 넘기면 실패시켜 테스트가 멈춰 있지 않게 한다.
 * entered에는 DB 발급 경로에 들어온 사용자가 남는다(들어오지 않았다는 증거로 쓴다).
 */
public class BarrierIssueService extends CouponIssueService {

    private static final Duration DEADLINE = Duration.ofSeconds(30);

    public final Map<Long, CompletableFuture<Void>> holds = new ConcurrentHashMap<>();
    public final Set<Long> entered = ConcurrentHashMap.newKeySet();

    public BarrierIssueService(CouponIssueTransactions transactions) {
        super(transactions);
    }

    @Override
    public CouponIssueResult issue(Long campaignId, Long userId) {
        entered.add(userId);
        CompletableFuture<Void> hold = holds.get(userId);
        if (hold != null) {
            try {
                hold.get(DEADLINE.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("barrier deadline 초과 또는 중단: userId=" + userId, e);
            }
        }
        return super.issue(campaignId, userId);
    }

    /** 붙잡힌 실행을 모두 풀고 기록을 비운다. */
    public void reset() {
        holds.values().forEach(hold -> hold.complete(null));
        holds.clear();
        entered.clear();
    }
}
