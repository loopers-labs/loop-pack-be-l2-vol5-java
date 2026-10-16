package com.loopers.application.like;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * 같은 사용자의 좋아요가 동시에 들어와 늦은 쪽이 관계의 유니크 키 위반으로 실패하면 등록 유스케이스 전체를 새 트랜잭션으로 다시 실행함.
 * 다시 실행하면 앞선 요청이 만든 관계가 보여 아무것도 바꾸지 않고 성공한다 (LIK-02 멱등, 4주차 설계 3.3).
 * 트랜잭션을 열지 않으며, 시도마다 LikeFacade 가 트랜잭션을 새로 엶. 정책은 OrderConfirmRetrier 와 같음 (3주차 설계 4.4)
 */
@RequiredArgsConstructor
@Component
public class LikeRetrier {
    private final LikeFacade likeFacade;

    @Retryable(
        retryFor = DuplicateKeyException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 200, random = true)
    )
    public LikeInfo like(Long userId, Long productId) {
        return likeFacade.like(userId, productId);
    }
}
