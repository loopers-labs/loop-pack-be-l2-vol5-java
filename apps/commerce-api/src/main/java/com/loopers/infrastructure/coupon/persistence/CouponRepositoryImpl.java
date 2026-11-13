package com.loopers.infrastructure.coupon.persistence;

import com.loopers.domain.coupon.entity.Coupon;
import com.loopers.domain.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class CouponRepositoryImpl implements CouponRepository {

    private final CouponJpaRepository couponJpaRepository;

    /** 정렬은 이 메서드가 정하므로 요청의 정렬은 쓰지 않음 */
    @Override
    public Page<Coupon> findMyCoupons(Long userId, Pageable pageable) {
        return couponJpaRepository.findByUserIdOrderByIssuedAtDescIdDesc(
            userId, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
    }
}
