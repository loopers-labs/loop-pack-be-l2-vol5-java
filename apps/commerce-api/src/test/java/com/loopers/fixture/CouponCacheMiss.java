package com.loopers.fixture;

import com.loopers.application.coupon.CouponCacheService;
import com.loopers.config.CouponCacheProperties;
import com.loopers.domain.coupon.CouponCacheRepository;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 캐시 도입 전 계약을 검사하는 기존 쿠폰 검사용 대역(설계 §13.12 C1). 조회는 항상 없음, 저장은 아무것도 하지 않으며 Redis 에 접근하지 않는다.
 * 고정 캠페인·DB truncate 뒤 ID 재사용으로 이전 사례의 캐시가 다음 사례를 가로채지 않게 한다.
 *
 * <p>Spring 컨텍스트에는 {@code @Import(CouponCacheMiss.class)} 로만 넣는다. 스테레오타입이 없어 컴포넌트 스캔(W6·O11·일반 앱 기동)에 들어가지 않는다.
 * 실제 캐시를 검사하는 컨텍스트에는 넣지 않는다.
 */
@Primary
public class CouponCacheMiss implements CouponCacheRepository {

    /** 직접 조립 Facade 용. 같은 miss/no-op 계약의 서비스다. */
    public static CouponCacheService service() {
        return new CouponCacheService(new CouponCacheMiss(), new CouponCacheProperties(Duration.ofSeconds(30), Duration.ofSeconds(30)));
    }

    @Override
    public Optional<IssuedCoupon> findIssued(Long userId, Long campaignId) {
        return Optional.empty();
    }

    @Override
    public void putIssuedIfAbsent(Long userId, Long campaignId, IssuedCoupon value, long ttlMillis) {
    }

    @Override
    public OptionalLong findSoldOutTtl(Long campaignId) {
        return OptionalLong.empty();
    }

    @Override
    public void putSoldOutIfAbsent(Long campaignId, long ttlMillis) {
    }
}
