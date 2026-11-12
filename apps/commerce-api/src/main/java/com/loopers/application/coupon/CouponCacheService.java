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
 * 쿠폰 캐시 조회·저장 실패를 miss·무시로 바꾼다(설계 §13.4·13.9). 캐시 오류만으로 수용을 닫거나 업무 결과를 바꾸지 않는다.
 * DB·TX 는 다루지 않는다. 실패는 RuntimeException 범위에서 로그로 남기며 재시도하지 않는다. Error 는 삼키지 않는다.
 * isSoldOut 의 false 는 '캐시에서 품절을 확인하지 못함'이지 재고 있음이 아니다.
 *
 * <p>로컬 품절(설계 §13.11): 등록은 단일 운영 캠페인만 이 판단에 도달하므로 슬롯 하나를 원자 교체한다. 실제 Redis 에서 품절 true 와
 * 남은 수명을 읽었을 때만 채우고, 수명은 min(로컬 TTL, Redis 남은 수명 − 조회 시작부터의 경과)다. 조회 hit 으로 연장하지 않는다.
 * 동시 miss 의 중복 Redis 조회는 허용한다. rememberSoldOut 은 Redis 에만 저장하며 로컬을 직접 채우지 않는다.
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

    /** 확보한 발급 결과의 다섯 값만 저장한다. created 는 두지 않는다. 값 변환 실패도 저장 실패와 같다. */
    public void rememberIssued(Long userId, Long campaignId, CouponIssueResult result) {
        try {
            IssuedCoupon value = new IssuedCoupon(result.couponId(), result.campaignId(), result.campaignName(),
                result.benefitType(), result.benefitValue());
            repository.putIssuedIfAbsent(userId, campaignId, value, issuedTtlMillis);
        } catch (RuntimeException e) {
            log.warn("기발급 캐시를 저장하지 못했습니다. 확보한 결과는 유지합니다. [userId = {}, campaignId = {}]", userId, campaignId, e);
        }
    }

    /** 유효한 로컬 품절이면 Redis 를 부르지 않는다. 아니면 Redis 를 읽고, 남은 수명이 있으면 로컬을 채운다. */
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

    /** 로컬 품절 한 칸. 관찰 시각부터 수명 동안만 유효하며 ID 는 숫자 값으로 비교한다. */
    private record LocalSoldOut(long campaignId, long observedAtNanos, long lifetimeNanos) {

        boolean validFor(long requested, long nowNanos) {
            long elapsed = nowNanos - observedAtNanos;
            return campaignId == requested && elapsed >= 0 && elapsed < lifetimeNanos;
        }
    }
}
