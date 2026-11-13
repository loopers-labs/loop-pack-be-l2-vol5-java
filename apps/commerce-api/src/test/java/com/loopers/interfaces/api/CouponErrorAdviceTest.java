package com.loopers.interfaces.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import org.junit.jupiter.api.Test;

class CouponErrorAdviceTest {
    private final ApiControllerAdvice advice = new ApiControllerAdvice();

    @Test
    void 처리_자리가_없으면_429와_구분된_오류를_반환한다() {
        var response = advice.handle(new CoreException(ErrorType.COUPON_ADMISSION_FULL));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody().meta().errorCode()).isEqualTo("COUPON_ADMISSION_FULL");
    }

    @Test
    void 수용_상태를_확인할_수_없으면_503을_반환한다() {
        var response = advice.handle(new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().meta().errorCode()).isEqualTo("COUPON_ADMISSION_UNAVAILABLE");
    }
}
