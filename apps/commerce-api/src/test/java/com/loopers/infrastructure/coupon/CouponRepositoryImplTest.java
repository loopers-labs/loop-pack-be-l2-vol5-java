package com.loopers.infrastructure.coupon;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.loopers.domain.coupon.CouponAlreadyIssuedException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

@ExtendWith(MockitoExtension.class)
class CouponRepositoryImplTest {
    @Mock private CouponJpaRepository rows;
    private CouponRepositoryImpl coupons;

    @BeforeEach
    void setUp() {
        coupons = new CouponRepositoryImpl(rows);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "uk_coupon_campaign_user", "issued_coupons.uk_coupon_campaign_user",
        "`loopers`.`issued_coupons`.`uk_coupon_campaign_user`", "'issued_coupons.uk_coupon_campaign_user'"
    })
    void 해당_사용자_캠페인_유일_제약만_중복으로_변환한다(String constraint) {
        DataIntegrityViolationException failure = failure(1062, constraint);
        when(rows.saveAndFlush(any(CouponJpaEntity.class))).thenThrow(failure);

        assertThatThrownBy(() -> coupons.insert(4, 2, "쿠폰"))
                .isInstanceOf(CouponAlreadyIssuedException.class).hasCause(failure);
    }

    @Test
    void 다른_유일_제약_오류는_중복_발급으로_감추지_않는다() {
        DataIntegrityViolationException failure = failure(1062, "uk_another_constraint");
        when(rows.saveAndFlush(any(CouponJpaEntity.class))).thenThrow(failure);

        assertThatThrownBy(() -> coupons.insert(4, 2, "쿠폰")).isSameAs(failure);
    }

    @Test
    void 하이버네이트가_이름을_제공하지_않으면_mysql_중복키_이름을_확인한다() {
        SQLException sql = new SQLException(
                "Duplicate entry '4-2' for key 'issued_coupons.uk_coupon_campaign_user'", "23000", 1062);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("duplicate",
                new ConstraintViolationException("constraint failure", sql, null));
        when(rows.saveAndFlush(any(CouponJpaEntity.class))).thenThrow(failure);

        assertThatThrownBy(() -> coupons.insert(4, 2, "쿠폰"))
                .isInstanceOf(CouponAlreadyIssuedException.class).hasCause(failure);
    }

    @Test
    void 같은_제약명이라도_mysql_중복키_오류가_아니면_감추지_않는다() {
        DataIntegrityViolationException failure = failure(1452, "uk_coupon_campaign_user");
        when(rows.saveAndFlush(any(CouponJpaEntity.class))).thenThrow(failure);

        assertThatThrownBy(() -> coupons.insert(4, 2, "쿠폰")).isSameAs(failure);
    }

    private DataIntegrityViolationException failure(int vendorCode, String constraintName) {
        SQLException sql = new SQLException("integrity error", "23000", vendorCode);
        return new DataIntegrityViolationException("database integrity failure",
                new ConstraintViolationException("constraint failure", sql, constraintName));
    }
}
