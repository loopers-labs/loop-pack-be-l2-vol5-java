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
import org.springframework.data.redis.RedisConnectionFailureException;
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
class CouponRedisFaultTest {

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
            new CouponCampaignModel("장애 테스트 쿠폰", 10, ZonedDateTime.now(), ZonedDateTime.now().plusDays(1))
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

    @DisplayName("Redis 장애 시 enqueue는 503을 반환하고 게이트가 닫힌다.")
    @Test
    void redisFault_enqueue_closesGate() {
        when(queuePort.enqueue(anyLong(), anyLong(), anyInt()))
            .thenThrow(new RedisConnectionFailureException("Redis down"));

        assertThatThrownBy(() -> couponFacade.enqueue(campaignId, 1L))
            .isInstanceOf(CoreException.class)
            .satisfies(e -> assertThat(((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.SERVICE_UNAVAILABLE));

        assertThat(admissionGate.isOpen(campaignId)).isFalse();
    }

    @DisplayName("Redis 장애 시 상태 조회는 503을 반환하고 게이트가 닫힌다.")
    @Test
    void redisFault_status_closesGate() {
        when(queuePort.status(anyLong(), anyLong(), anyLong()))
            .thenThrow(new RedisConnectionFailureException("Redis down"));

        assertThatThrownBy(() -> couponFacade.getQueueStatus(campaignId, 1L))
            .isInstanceOf(CoreException.class)
            .satisfies(e -> assertThat(((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.SERVICE_UNAVAILABLE));

        assertThat(admissionGate.isOpen(campaignId)).isFalse();
    }

    @DisplayName("Redis 장애 시 발급 시작은 503을 반환하고 게이트가 닫힌다.")
    @Test
    void redisFault_start_closesGate() {
        when(queuePort.start(anyLong(), anyLong(), anyLong()))
            .thenThrow(new RedisConnectionFailureException("Redis down"));

        assertThatThrownBy(() -> couponFacade.issueCoupon(campaignId, 1L))
            .isInstanceOf(CoreException.class)
            .satisfies(e -> assertThat(((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.SERVICE_UNAVAILABLE));

        assertThat(admissionGate.isOpen(campaignId)).isFalse();
    }

    @DisplayName("Redis 장애 후 복구되어도 게이트는 자동으로 열리지 않는다.")
    @Test
    void redisFault_recovery_gateStaysClosed() {
        when(queuePort.enqueue(anyLong(), anyLong(), anyInt()))
            .thenThrow(new RedisConnectionFailureException("Redis down"));

        try {
            couponFacade.enqueue(campaignId, 1L);
        } catch (CoreException ignored) { }

        assertThat(admissionGate.isOpen(campaignId)).isFalse();
        assertThat(admissionGate.isOpen(campaignId)).isFalse();
    }

    @DisplayName("Redis 복구 후 어드민이 직접 게이트를 열어야 한다.")
    @Test
    void redisFault_recovery_adminMustReopenGate() {
        when(queuePort.enqueue(anyLong(), anyLong(), anyInt()))
            .thenThrow(new RedisConnectionFailureException("Redis down"))
            .thenReturn(CouponQueuePort.EnqueueResult.ENQUEUED);
        when(queuePort.status(anyLong(), anyLong(), anyLong()))
            .thenReturn(CouponQueuePort.QueueStatusResult.queued(1));

        try {
            couponFacade.enqueue(campaignId, 1L);
        } catch (CoreException ignored) { }

        assertThat(admissionGate.isOpen(campaignId)).isFalse();

        couponFacade.openGate(campaignId);
        assertThat(admissionGate.isOpen(campaignId)).isTrue();
    }
}
