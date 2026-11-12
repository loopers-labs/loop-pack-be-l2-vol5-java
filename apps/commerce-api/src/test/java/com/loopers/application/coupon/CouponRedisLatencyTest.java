package com.loopers.application.coupon;

import com.loopers.CommerceApiApplication;
import com.loopers.domain.coupon.CouponAdmissionGate;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponQueuePort;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.annotation.DirtiesContext;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = CommerceApiApplication.class)
@Import(RedisTestContainersConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponRedisLatencyTest {

    @MockBean CouponQueuePort queuePort;

    @Autowired CouponFacade couponFacade;
    @Autowired CouponAdmissionGate admissionGate;
    @Autowired CouponCampaignJpaRepository campaignJpaRepository;
    @Autowired DatabaseCleanUp databaseCleanUp;
    @Autowired RedisCleanUp redisCleanUp;

    private Long campaignId;

    @BeforeEach
    void setUp() {
        CouponCampaignModel campaign = campaignJpaRepository.save(
            new CouponCampaignModel("지연 테스트 쿠폰", 10, ZonedDateTime.now(), ZonedDateTime.now().plusDays(1))
        );
        campaignId = campaign.getId();
        admissionGate.open(campaignId);
    }

    @AfterEach
    void tearDown() {
        admissionGate.close(campaignId);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @DisplayName("Redis 명령 타임아웃 시 503을 반환하고 게이트가 닫힌다.")
    @Test
    void latency_timeout_closesGateAndReturns503() {
        when(queuePort.enqueue(anyLong(), anyLong(), anyInt()))
            .thenThrow(new QueryTimeoutException("Redis command timed out"));

        assertThatThrownBy(() -> couponFacade.enqueue(campaignId, 1L))
            .isInstanceOf(CoreException.class)
            .satisfies(e -> assertThat(((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.SERVICE_UNAVAILABLE));

        assertThat(admissionGate.isOpen(campaignId)).isFalse();
    }

    @DisplayName("Redis 타임아웃 후 복구되어도 게이트는 자동으로 열리지 않는다.")
    @Test
    void latency_timeout_gateDoesNotAutoReopen() {
        when(queuePort.enqueue(anyLong(), anyLong(), anyInt()))
            .thenThrow(new QueryTimeoutException("Redis command timed out"))
            .thenReturn(CouponQueuePort.EnqueueResult.ENQUEUED);
        when(queuePort.status(anyLong(), anyLong(), anyLong()))
            .thenReturn(CouponQueuePort.QueueStatusResult.queued(1));

        try {
            couponFacade.enqueue(campaignId, 1L);
        } catch (CoreException ignored) { }

        assertThat(admissionGate.isOpen(campaignId)).isFalse();
    }
}
