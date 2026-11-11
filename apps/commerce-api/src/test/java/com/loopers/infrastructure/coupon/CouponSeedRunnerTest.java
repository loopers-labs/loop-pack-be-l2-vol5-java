package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponSeedService;
import com.loopers.domain.coupon.CouponCampaign;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CouponSeedRunnerTest {

    private final CouponSeedService seed = mock(CouponSeedService.class);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(CouponSeedService.class, () -> seed)
        .withUserConfiguration(CouponSeedRunner.class);

    @DisplayName("로컬 runner의 기본값은 기존 seed를 보존한다.")
    @Test
    void preservesByDefaultInLocalProfile() {
        when(seed.prepare(false)).thenReturn(preparedCampaign());
        contextRunner.withPropertyValues("spring.profiles.active=local").run(context -> {
            context.getBean(CouponSeedRunner.class).run(new DefaultApplicationArguments());
            verify(seed).prepare(false);
        });
    }

    @DisplayName("로컬에서 명시한 reset 옵션만 seed 서비스에 전달한다.")
    @Test
    void passesExplicitResetOption() {
        when(seed.prepare(true)).thenReturn(preparedCampaign());
        contextRunner.withPropertyValues("spring.profiles.active=local", "coupon.seed.reset=true").run(context -> {
            context.getBean(CouponSeedRunner.class).run(new DefaultApplicationArguments());
            verify(seed).prepare(true);
        });
    }

    @DisplayName("test 프로필에서는 로컬 seed runner가 등록되지 않는다.")
    @Test
    void doesNotRegisterOutsideLocalProfile() {
        contextRunner.withPropertyValues("spring.profiles.active=test").run(context -> {
            assertThat(context).doesNotHaveBean(CouponSeedRunner.class);
            verifyNoInteractions(seed);
        });
    }

    private CouponCampaign preparedCampaign() {
        return CouponCampaign.reconstitute(1L, "local-coupon-seed", 100, 0, "5,000원 할인권",
            Instant.parse("2026-11-11T00:00:00Z"), Instant.parse("2026-11-12T00:00:00Z"), null);
    }
}
