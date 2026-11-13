package com.loopers.config.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.infrastructure.concurrency.coupon.SemaphoreCouponAdmission;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CouponAdmissionPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(CouponAdmissionConfig.class, SemaphoreCouponAdmission.class);

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid"})
    void invalidConfigurationFailsAtStartup(String value) {
        runner.withPropertyValues("coupon.admission.max-concurrency=" + value)
            .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 5})
    void bindsCapacityOnceAndReturnsOriginalPermitOnlyOnce(int capacity) {
        runner.withPropertyValues("coupon.admission.max-concurrency=" + capacity).run(context -> {
            assertThat(context).hasNotFailed();
            SemaphoreCouponAdmission admission = context.getBean(SemaphoreCouponAdmission.class);
            assertThat(context.getBean(SemaphoreCouponAdmission.class)).isSameAs(admission);
            assertThat(admission.capacity()).isEqualTo(capacity);
            var permit = admission.tryAcquire().orElseThrow();
            assertThat(admission.available()).isEqualTo(capacity - 1);
            assertThat(permit.release()).isTrue();
            assertThat(permit.release()).isFalse();
            assertThat(admission.available()).isEqualTo(capacity);
        });
    }
}
