package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponIssueAdmissionState;
import com.loopers.application.coupon.CouponIssuePermits;
import com.loopers.application.coupon.CouponIssueProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponIssueAdmissionStartupRunnerTest {

    @DisplayName("명시적 시작 옵션이 false면 Redis를 확인하지 않고 기본 닫힘을 유지한다.")
    @Test
    void staysClosedWithoutExplicitOpenOption() {
        CouponIssuePermits permits = mock(CouponIssuePermits.class);
        CouponIssueAdmissionState admission = new CouponIssueAdmissionState();
        CouponIssueAdmissionStartupRunner runner = new CouponIssueAdmissionStartupRunner(
            new CouponIssueProperties(3, new CouponIssueProperties.Admission(false)), permits, admission);

        runner.run(mock(ApplicationArguments.class));

        assertThat(admission.isOpen()).isFalse();
        verify(permits, never()).isEmpty();
    }

    @DisplayName("명시적 시작 옵션과 빈 primary Set을 모두 확인한 경우에만 수용을 연다.")
    @Test
    void opensOnlyWhenExplicitlyRequestedAndSetIsEmpty() {
        CouponIssuePermits permits = mock(CouponIssuePermits.class);
        when(permits.isEmpty()).thenReturn(true);
        CouponIssueAdmissionState admission = new CouponIssueAdmissionState();
        CouponIssueAdmissionStartupRunner runner = new CouponIssueAdmissionStartupRunner(
            new CouponIssueProperties(3, new CouponIssueProperties.Admission(true)), permits, admission);

        runner.run(mock(ApplicationArguments.class));

        assertThat(admission.isOpen()).isTrue();
    }

    @DisplayName("primary Set에 활성 token이 있으면 명시적 시작 옵션이 있어도 수용하지 않는다.")
    @Test
    void staysClosedWhenSetHasTokens() {
        CouponIssuePermits permits = mock(CouponIssuePermits.class);
        when(permits.isEmpty()).thenReturn(false);
        CouponIssueAdmissionState admission = new CouponIssueAdmissionState();
        CouponIssueAdmissionStartupRunner runner = new CouponIssueAdmissionStartupRunner(
            new CouponIssueProperties(3, new CouponIssueProperties.Admission(true)), permits, admission);

        runner.run(mock(ApplicationArguments.class));

        assertThat(admission.isOpen()).isFalse();
    }

    @DisplayName("primary 점유를 확인할 수 없으면 시작 요청이 있어도 기본 닫힘을 유지한다.")
    @Test
    void staysClosedWhenRedisCannotBeChecked() {
        CouponIssuePermits permits = mock(CouponIssuePermits.class);
        when(permits.isEmpty()).thenThrow(new RuntimeException("Redis unavailable"));
        CouponIssueAdmissionState admission = new CouponIssueAdmissionState();
        CouponIssueAdmissionStartupRunner runner = new CouponIssueAdmissionStartupRunner(
            new CouponIssueProperties(3, new CouponIssueProperties.Admission(true)), permits, admission);

        runner.run(mock(ApplicationArguments.class));

        assertThat(admission.isOpen()).isFalse();
    }
}
