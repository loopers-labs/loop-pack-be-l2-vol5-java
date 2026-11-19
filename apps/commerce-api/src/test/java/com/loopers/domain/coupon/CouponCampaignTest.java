package com.loopers.domain.coupon;

import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CouponCampaignTest {

    @DisplayName("쿠폰 캠페인을 만들 때,")
    @Nested
    class Create {
        @DisplayName("발급 한도가 1보다 작으면 INVALID_INPUT 으로 거절한다.")
        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        void create_fails_whenQuotaIsNotPositive(int totalQuota) {
            // act
            CoreException exception = assertThrows(CoreException.class,
                () -> CouponCampaign.create("5,000원 할인권", new Money(5_000), totalQuota));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_INPUT);
        }
    }
}
