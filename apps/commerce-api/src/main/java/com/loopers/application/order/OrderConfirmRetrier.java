package com.loopers.application.order;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * 주문 확정이 포인트 · 주문의 버전 충돌로 실패하면 확정 유스케이스 전체를 새 트랜잭션으로 다시 실행함.
 * 트랜잭션을 열지 않으며, 시도마다 OrderFacade 가 트랜잭션을 새로 엶. 업무 거절(CoreException)은 다시 실행하지 않음.
 * 총 3회 모두 충돌하면 충돌 예외가 그대로 나가고 interfaces 가 응답으로 바꿈 (3주차 설계 4.4)
 */
@RequiredArgsConstructor
@Component
public class OrderConfirmRetrier {
    private final OrderFacade orderFacade;

    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 200, random = true)
    )
    public OrderInfo confirmOrder(Long userId, Long orderId) {
        return orderFacade.confirmOrder(userId, orderId);
    }
}
