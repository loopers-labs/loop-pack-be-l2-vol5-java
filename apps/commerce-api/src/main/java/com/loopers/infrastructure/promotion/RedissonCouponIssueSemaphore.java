package com.loopers.infrastructure.promotion;

import com.loopers.application.promotion.CouponIssueLimiterProperties;
import com.loopers.application.promotion.CouponIssueSemaphore;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link CouponIssueSemaphore} 의 Redisson 구현 (DR-61). 프로모션마다 RPermitExpirableSemaphore 하나.
 * 획득마다 trySetPermits 로 permit 수를 넣는다 (키가 없을 때만 설정된다). 그래서 키가 사라져도 다음 획득 때 다시 만든다.
 * 허가가 없으면 waitTime 까지 기다린다. 반납이나 lease 만료로 허가가 돌아오면 pub/sub 알림을 받고 바로 다시 시도한다.
 * lease 만료 시각은 앱 서버 시계(System.currentTimeMillis)로 정해진다.
 * Redis 오류·인터럽트는 획득 실패로 돌린다 (fail-closed). 반납 오류는 로그만 남긴다. 그 허가는 lease 가 지나면 회수된다.
 */
@Slf4j
@Component
public class RedissonCouponIssueSemaphore implements CouponIssueSemaphore {
    private final RedissonClient redissonClient;
    private final int permits;
    private final long leaseMillis;
    private final long waitMillis;

    public RedissonCouponIssueSemaphore(RedissonClient redissonClient, CouponIssueLimiterProperties properties) {
        this.redissonClient = redissonClient;
        this.permits = properties.permits();
        this.leaseMillis = properties.lease().toMillis();
        this.waitMillis = properties.waitTime().toMillis();
    }

    /** coupon-issue:semaphore:{promotionId} */
    static String key(Long promotionId) {
        return "coupon-issue:semaphore:" + promotionId;
    }

    private RPermitExpirableSemaphore semaphore(Long promotionId) {
        return redissonClient.getPermitExpirableSemaphore(key(promotionId));
    }

    @Override
    public Optional<String> tryAcquire(Long promotionId) {
        RPermitExpirableSemaphore semaphore = semaphore(promotionId);
        try {
            semaphore.trySetPermits(permits);
            return Optional.ofNullable(semaphore.tryAcquire(waitMillis, leaseMillis, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("쿠폰 발급 허가 획득 실패로 처리 (Redis 오류). [promotionId = {}]", promotionId, e);
            return Optional.empty();
        }
    }

    @Override
    public void release(Long promotionId, String token) {
        try {
            semaphore(promotionId).tryRelease(token);
        } catch (RuntimeException e) {
            log.warn("쿠폰 발급 허가 반납 실패. lease 가 지나면 회수된다. [promotionId = {}]", promotionId, e);
        }
    }
}
