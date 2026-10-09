package com.loopers.application.point;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * 충전이 같은 사용자의 결제와 겹쳐 버전 충돌로 실패하면 충전을 새 트랜잭션으로 다시 실행함.
 * 트랜잭션을 열지 않으며, 시도마다 PointService 가 트랜잭션을 새로 엶. 업무 거절(CoreException)은 다시 실행하지 않음.
 * 총 3회 모두 충돌하면 충돌 예외가 그대로 나가고 interfaces 가 응답으로 바꿈 (3주차 설계 4.4)
 */
@RequiredArgsConstructor
@Component
public class PointChargeRetrier {
    private final PointFacade pointFacade;

    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 200, random = true)
    )
    public PointInfo charge(Long userId, long amount) {
        return pointFacade.charge(userId, amount);
    }
}
