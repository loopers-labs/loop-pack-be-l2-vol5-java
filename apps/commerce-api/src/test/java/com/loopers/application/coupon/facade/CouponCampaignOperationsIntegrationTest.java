package com.loopers.application.coupon.facade;

import com.loopers.domain.coupon.entity.CouponCampaign;
import com.loopers.domain.coupon.enums.CampaignStatus;
import com.loopers.domain.coupon.enums.CouponErrorCode;
import com.loopers.domain.coupon.enums.DiscountType;
import com.loopers.domain.coupon.enums.IssueState;
import com.loopers.domain.coupon.gateway.CouponIssueQueue;
import com.loopers.domain.coupon.repository.CouponCampaignRepository;
import com.loopers.domain.user.repository.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class CouponCampaignOperationsIntegrationTest {

    @Autowired
    private CouponCampaignOperations operations;

    @Autowired
    private CouponIssueFacade couponIssueFacade;

    @MockitoSpyBean
    private CouponIssueQueue couponIssueQueue;

    @Autowired
    private CouponCampaignRepository couponCampaignRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private Long readyCampaign(int totalQuota) {
        return couponCampaignRepository.save(
            new CouponCampaign("행사", totalQuota, DiscountType.FIXED_AMOUNT, 5_000L, "설명")).getId();
    }

    private CampaignStatus statusOf(Long campaignId) {
        return couponCampaignRepository.findById(campaignId).orElseThrow().getStatus();
    }

    @DisplayName("열면 DB 가 OPEN 이 되고 접수를 받는다. 예비 범위는 한도 + 비율이다. (D-122, D-166)")
    @Test
    void opensDatabaseThenRedis() {
        // arrange
        Long campaignId = readyCampaign(2);

        // act
        int reserveLimit = operations.open(campaignId);

        // assert
        assertAll(
            () -> assertThat(reserveLimit).isEqualTo(3),
            () -> assertThat(statusOf(campaignId)).isEqualTo(CampaignStatus.OPEN),
            () -> assertThat(couponIssueFacade.request(campaignId, 7L).state()).isEqualTo(IssueState.WAITING)
        );
    }

    @DisplayName("Redis 쪽에서 멈추면 DB 는 OPEN 이지만 접수는 503 이고, 다시 열면 이어서 열린다. (D-166)")
    @Test
    void staysClosed_whenRedisStepFails_thenReopens() {
        // arrange
        Long campaignId = readyCampaign(2);
        doThrow(new QueryTimeoutException("redis timeout")).doCallRealMethod()
            .when(couponIssueQueue).open(anyLong(), anyInt(), anyInt(), anyString());

        // act
        Throwable failed = catchThrowable(() -> operations.open(campaignId));
        Throwable whileHalfOpen = catchThrowable(() -> couponIssueFacade.request(campaignId, 7L));
        operations.open(campaignId);

        // assert
        assertAll(
            () -> assertThat(failed).isInstanceOf(QueryTimeoutException.class),
            () -> assertThat(whileHalfOpen).isInstanceOfSatisfying(CoreException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_ISSUE_UNAVAILABLE)),
            () -> assertThat(couponIssueFacade.request(campaignId, 7L).state()).isEqualTo(IssueState.WAITING)
        );
    }

    @DisplayName("닫으면 DB 가 CLOSED 가 되고 새 접수는 409 다. (D-124, D-166)")
    @Test
    void closesDatabaseThenRedis() {
        // arrange
        Long campaignId = readyCampaign(2);
        operations.open(campaignId);

        // act
        operations.close(campaignId);

        // assert
        assertAll(
            () -> assertThat(statusOf(campaignId)).isEqualTo(CampaignStatus.CLOSED),
            () -> assertThatThrownBy(() -> couponIssueFacade.request(campaignId, 7L))
                .isInstanceOfSatisfying(CoreException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.CAMPAIGN_NOT_ACTIVE))
        );
    }

    @DisplayName("실험용 사용자를 묶음으로 만든다. (D-167)")
    @Test
    void createsUsersInBatches() {
        // arrange
        long before = userRepository.count();

        // act
        operations.createUsers(2_500);

        // assert
        assertThat(userRepository.count() - before).isEqualTo(2_500);
    }
}
