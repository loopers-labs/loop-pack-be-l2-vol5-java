package com.loopers.application.coupon;

import com.loopers.config.CouponCacheProperties;
import com.loopers.domain.coupon.CouponCacheRepository;
import com.loopers.domain.coupon.CouponCacheRepository.IssuedCoupon;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 캐시 오류를 miss·무시로 바꾼다. 캐시 오류만으로 수용을 닫거나 업무 결과를 바꾸지 않는다.
 * isSoldOut 의 false 는 '품절을 확인하지 못함'이지 재고 있음이 아니다.
 * 로컬 품절 수명은 min(로컬 TTL, Redis 남은 수명 − 조회 경과)이며 조회 hit 으로 연장하지 않는다.
 */
@Slf4j
@Component
public class CouponCacheService {

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final CouponCacheRepository repository;
    private final long issuedTtlMillis;
    private final long soldOutTtlMillis;
    private final long localSoldOutTtlNanos;
    private final AtomicReference<LocalSoldOut> localSoldOut = new AtomicReference<>();

    public CouponCacheService(CouponCacheRepository repository, CouponCacheProperties properties) {
        this.repository = repository;
        this.issuedTtlMillis = properties.issuedTtl().toMillis();
        this.soldOutTtlMillis = properties.soldOutTtl().toMillis();
        this.localSoldOutTtlNanos = properties.localSoldOutTtl().toNanos();
    }

    public Optional<IssuedCoupon> findIssued(Long userId, Long campaignId) {
        try {
            return repository.findIssued(userId, campaignId);
        } catch (RuntimeException e) {
            log.warn("기발급 캐시를 읽지 못해 DB 로 확인합니다. [userId = {}, campaignId = {}]", userId, campaignId, e);
            return Optional.empty();
        }
    }

    public void rememberIssued(Long userId, Long campaignId, CouponIssueResult result) {
        try {
            IssuedCoupon value = new IssuedCoupon(result.couponId(), result.campaignId(), result.campaignName(),
                result.benefitType(), result.benefitValue());
            repository.putIssuedIfAbsent(userId, campaignId, value, issuedTtlMillis);
        } catch (RuntimeException e) {
            log.warn("기발급 캐시를 저장하지 못했습니다. 확보한 결과는 유지합니다. [userId = {}, campaignId = {}]", userId, campaignId, e);
        }
    }

    public boolean isSoldOut(Long campaignId) {
        try {
            if (campaignId != null) {
                LocalSoldOut local = localSoldOut.get();
                if (local != null && local.validFor(campaignId, System.nanoTime())) {
                    return true;
                }
            }
            long started = System.nanoTime();
            OptionalLong remainingMillis = repository.findSoldOutTtl(campaignId);
            if (remainingMillis.isEmpty() || remainingMillis.getAsLong() <= 0) {
                return false;
            }
            long observedAt = System.nanoTime();
            long elapsed = observedAt - started;
            if (elapsed < 0) {
                return false;
            }
            long budget = Math.multiplyExact(remainingMillis.getAsLong(), NANOS_PER_MILLI) - elapsed;
            if (budget <= 0) {
                return false; // Redis 응답·처리 중 남은 수명을 다 썼다. 품절로 쓰지 않는다.
            }
            localSoldOut.set(new LocalSoldOut(campaignId, observedAt, Math.min(localSoldOutTtlNanos, budget)));
            return true;
        } catch (RuntimeException e) {
            log.warn("품절 캐시 조회 또는 로컬 수명 판정에 실패해 DB 로 확인합니다. [campaignId = {}]", campaignId, e);
            return false;
        }
    }

    public void rememberSoldOut(Long campaignId) {
        try {
            repository.putSoldOutIfAbsent(campaignId, soldOutTtlMillis);
        } catch (RuntimeException e) {
            log.warn("품절 캐시를 저장하지 못했습니다. 품절 응답은 유지합니다. [campaignId = {}]", campaignId, e);
        }
    }

    private record LocalSoldOut(long campaignId, long observedAtNanos, long lifetimeNanos) {

        boolean validFor(long requested, long nowNanos) {
            long elapsed = nowNanos - observedAtNanos;
            return campaignId == requested && elapsed >= 0 && elapsed < lifetimeNanos;
        }
    }
}
