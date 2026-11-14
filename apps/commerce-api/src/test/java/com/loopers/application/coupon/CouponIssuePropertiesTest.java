package com.loopers.application.coupon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CouponIssuePropertiesTest {

    @DisplayName("수용이 기본 닫힘인 프로필은 아직 정하지 않은 수용량 없이 기동할 수 있다.")
    @Test
    void allowsMissingCapacityWhileAdmissionIsClosed() {
        CouponIssueProperties properties = new CouponIssueProperties(null,
            new CouponIssueProperties.Admission(false));

        assertThat(properties.capacity()).isNull();
    }

    @DisplayName("수용을 시작 옵션으로 열 때는 양의 동시 수용량을 요구한다.")
    @Test
    void requiresPositiveCapacityWhenAdmissionOpensOnStartup() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new CouponIssueProperties(null, new CouponIssueProperties.Admission(true)));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new CouponIssueProperties(0, new CouponIssueProperties.Admission(false)));
    }

    @DisplayName("수용이 닫혀 있어도 명시한 유효 수용량은 사용할 수 있다.")
    @Test
    void acceptsPositiveCapacityWhenAdmissionIsClosed() {
        CouponIssueProperties properties = new CouponIssueProperties(3,
            new CouponIssueProperties.Admission(false));

        assertThat(properties.requireCapacity()).isEqualTo(3);
    }
}
