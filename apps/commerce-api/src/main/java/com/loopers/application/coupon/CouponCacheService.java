package com.loopers.application.coupon;

import com.loopers.config.CouponCacheProperties;
import com.loopers.domain.coupon.CouponCacheRepository;
import com.loopers.domain.coupon.CouponCacheRepository.IssuedCoupon;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 쿠폰 캐시 조회·저장 실패를 miss·무시로 바꾼다(설계 §13.4·13.9). 캐시 오류만으로 수용을 닫거나 업무 결과를 바꾸지 않는다.
 * DB·TX 는 다루지 않는다. 실패는 RuntimeException 범위에서 로그로 남기며 재시도하지 않는다. Error 는 삼키지 않는다.
 * isSoldOut 의 false 는 '캐시에서 품절을 확인하지 못함'이지 재고 있음이 아니다.
 */
@Slf4j
@Component
public class CouponCacheService {

    private final CouponCacheRepository repository;
    private final long issuedTtlMillis;
    private final long soldOutTtlMillis;

    public CouponCacheService(CouponCacheRepository repository, CouponCacheProperties properties) {
        this.repository = repository;
        this.issuedTtlMillis = properties.issuedTtl().toMillis();
        this.soldOutTtlMillis = properties.soldOutTtl().toMillis();
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

    public boolean isSoldOut(Long campaignId) {
        try {
            return repository.findSoldOutTtl(campaignId).isPresent();
        } catch (RuntimeException e) {
            log.warn("품절 캐시를 읽지 못해 DB 로 확인합니다. [campaignId = {}]", campaignId, e);
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
}
