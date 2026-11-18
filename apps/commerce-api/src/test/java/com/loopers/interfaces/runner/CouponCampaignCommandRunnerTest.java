package com.loopers.interfaces.runner;

import com.loopers.application.coupon.facade.CouponCampaignOperations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 실행 인자를 운영 동작으로 옮기고 종료 코드를 정함. 동작 자체는 CouponCampaignOperationsIntegrationTest */
class CouponCampaignCommandRunnerTest {

    private final CouponCampaignOperations operations = mock(CouponCampaignOperations.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final CouponCampaignCommandRunner runner =
        new CouponCampaignCommandRunner(operations, environment, mock(ConfigurableApplicationContext.class));

    @DisplayName("open · close 는 지정한 캠페인을 열고 닫는다.")
    @Test
    void opensAndClosesGivenCampaign() {
        environment.setProperty("coupon-campaign.campaign-id", "3");

        assertThat(runner.execute("open")).isEqualTo(CouponCampaignCommandRunner.SUCCESS);
        assertThat(runner.execute("close")).isEqualTo(CouponCampaignCommandRunner.SUCCESS);
        verify(operations).open(3L);
        verify(operations).close(3L);
    }

    @DisplayName("create-users 는 지정한 수만큼 사용자를 만든다.")
    @Test
    void createsGivenNumberOfUsers() {
        environment.setProperty("coupon-campaign.user-count", "1000");

        assertThat(runner.execute("create-users")).isEqualTo(CouponCampaignCommandRunner.SUCCESS);
        verify(operations).createUsers(1000);
    }

    @DisplayName("대상이 없거나 모르는 명령이면 아무 동작도 하지 않고 사용법 코드로 끝낸다.")
    @Test
    void doesNothing_whenTargetMissingOrUnknown() {
        assertThat(runner.execute("open")).isEqualTo(CouponCampaignCommandRunner.USAGE);
        assertThat(runner.execute("create-users")).isEqualTo(CouponCampaignCommandRunner.USAGE);
        assertThat(runner.execute("drop")).isEqualTo(CouponCampaignCommandRunner.USAGE);
        verifyNoInteractions(operations);
    }
}
