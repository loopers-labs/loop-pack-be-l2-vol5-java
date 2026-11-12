package com.loopers.application.coupon;

import com.loopers.application.coupon.SemaphoreGate.Admission;
import com.loopers.domain.coupon.CouponIssueResult;
import com.loopers.domain.coupon.CouponIssueService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 쿠폰 발급 한 요청의 흐름: 기존 쿠폰 확인 → 자리 획득 → 발급 → 자리 반환.
 * 이 클래스는 트랜잭션을 열지 않는다. Redis 호출은 항상 DB 트랜잭션 밖에서 하고, 자리는 발급 트랜잭션이 끝난 뒤에만 반환한다.
 *
 * <ul>
 *   <li>이미 받은 사용자는 자리를 차지하지 않고 DB의 기존 쿠폰을 돌려받는다</li>
 *   <li>Redis 결과 FULL이면 DB 경로에 들어가지 않고 NO_PERMIT으로 거절한다</li>
 *   <li>Redis 오류·timeout(UNCERTAIN)이면 신규 발급을 멈추지 않고 직행한다. 이때 DB 보호는 로컬 상한이 맡는다</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class CouponIssueFacade {

    private final CouponIssueService issueService;
    private final SemaphoreGate semaphoreGate;
    private final LocalLimiter localLimiter;

    public CouponIssueInfo issue(Long campaignId, Long userId) {
        var existing = issueService.findExisting(campaignId, userId);
        if (existing.isPresent()) {
            return CouponIssueInfo.from(existing.get());
        }

        Admission admission = semaphoreGate.acquire();
        switch (admission.status()) {
            case FULL -> throw new CoreException(ErrorType.NO_PERMIT);
            case ACQUIRED -> {
                try {
                    return issueAndMap(campaignId, userId);
                } finally {
                    // 발급 트랜잭션이 끝난 뒤 자기 token만 반환한다. 쿠폰과 발급 수는 되돌리지 않는다.
                    semaphoreGate.release(admission);
                }
            }
            case UNCERTAIN -> {
                // Redis를 믿을 수 없다. 이 token은 게이트가 미반환 목록에 기록해 두었고, 복구 뒤 자기 token만 정리한다.
                if (!localLimiter.tryAcquire()) {
                    throw new CoreException(ErrorType.NO_PERMIT);
                }
                try {
                    return issueAndMap(campaignId, userId);
                } finally {
                    localLimiter.release();
                }
            }
            default -> throw new IllegalStateException("알 수 없는 수용 결과: " + admission.status());
        }
    }

    private CouponIssueInfo issueAndMap(Long campaignId, Long userId) {
        CouponIssueResult result = issueService.issue(campaignId, userId);
        return CouponIssueInfo.from(result);
    }
}
