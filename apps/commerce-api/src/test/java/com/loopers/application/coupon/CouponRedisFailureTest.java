package com.loopers.application.coupon;

import com.github.dockerjava.api.DockerClient;
import com.loopers.application.coupon.admission.CouponAdmissionException;
import com.loopers.application.coupon.admission.CouponAdmissionSwitch;
import com.loopers.application.coupon.admission.CouponIssueAdmission;
import com.loopers.application.coupon.dto.CouponInfo;
import com.loopers.application.coupon.dto.CouponIssueInfo;
import com.loopers.application.coupon.dto.CouponIssueInfo.Status;
import com.loopers.application.coupon.dto.CouponReconcileInfo;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.CouponAdmissionState;
import com.loopers.support.fixture.CouponCampaigns;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * Redis 장애·상태 유실에서 신규 발급은 닫고(fail-closed) 기존 결과는 DB 로 지키며, 관리자 확인 뒤에만 다시 여는지 확인한다(과제 6장).
 *
 * <p>애플리케이션이 실제로 연결한 Redis 컨테이너를 pause 한다. 실패해도 항상 unpause 한다. stop/start 는 매핑 포트가 바뀌어 쓰지 않는다.
 */
@SpringBootTest
class CouponRedisFailureTest {

    private static final Duration BOUNDED_RESPONSE = Duration.ofSeconds(2);
    private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private CouponIssueFacade couponIssueFacade;

    @Autowired
    private AdminCouponCampaignFacade adminCouponCampaignFacade;

    @Autowired
    private CouponFacade couponFacade;

    @MockitoSpyBean
    private CouponIssueWriter couponIssueWriter;

    @MockitoSpyBean
    private CouponIssueAdmission couponIssueAdmission;

    @Autowired
    private CouponAdmissionSwitch admissionSwitch;

    @Autowired
    private CouponCampaigns couponCampaigns;

    @Autowired
    private CouponAdmissionState admissionState;

    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private RedisProperties redisProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final GenericContainer<?> redis = RedisTestContainersConfig.container();

    @BeforeEach
    void verifyTargetRedis() {
        // 엉뚱한 Redis 를 멈추고 장애를 만들었다고 하지 않도록, 애플리케이션이 연결한 주소가 이 컨테이너인지 먼저 확인한다.
        assertThat(redisProperties.master().port()).isEqualTo(redis.getFirstMappedPort());
    }

    @AfterEach
    void tearDown() {
        resumeRedis();
        awaitRedisPong();
        couponCampaigns.clear();
        databaseCleanUp.truncateAllTables();
    }

    private void pauseRedis() {
        docker().pauseContainerCmd(redis.getContainerId()).exec();
    }

    private void resumeRedis() {
        Boolean paused = docker().inspectContainerCmd(redis.getContainerId()).exec().getState().getPaused();
        if (Boolean.TRUE.equals(paused)) {
            docker().unpauseContainerCmd(redis.getContainerId()).exec();
        }
    }

    private DockerClient docker() {
        return redis.getDockerClient();
    }

    private void awaitRedisPong() {
        await().atMost(RECOVERY_TIMEOUT).ignoreExceptions()
            .until(() -> "PONG".equals(redisTemplate.execute((RedisCallback<String>) RedisConnection::ping)));
    }

    private void assertAdmissionUnavailable(Long campaignId, long userId) {
        CoreException exception = assertThrows(CoreException.class, () -> couponIssueFacade.issue(campaignId, userId));
        assertThat(exception.getErrorType()).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
    }

    private long couponRowsOf(Long campaignId, long userId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from coupons where campaign_id = ? and user_id = ?", Long.class, campaignId, userId);
    }

    @DisplayName("Redis 가 멈추면 신규 발급은 제한된 시간 안에 503 으로 거절하고 DB 효과가 없으며, 이미 받은 사람의 결과와 목록은 DB 로 응답한다.")
    @Test
    void rejectsNewIssueButKeepsExistingResults_whenRedisIsDown() {
        // arrange
        Long campaignId = couponCampaigns.open(5);
        CouponIssueInfo issued = couponIssueFacade.issue(campaignId, 1L);
        pauseRedis();

        // act
        long startedAt = System.nanoTime();
        assertAdmissionUnavailable(campaignId, 2L);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // assert
        assertThat(elapsed).isLessThan(BOUNDED_RESPONSE);
        assertThat(couponRowsOf(campaignId, 2L)).isZero();
        CouponIssueInfo repeat = couponIssueFacade.issue(campaignId, 1L);
        assertThat(repeat.status()).isEqualTo(Status.EXISTING);
        assertThat(repeat.couponId()).isEqualTo(issued.couponId());
        assertThat(couponFacade.getMyList(1L, 0, 20).getContent())
            .extracting(CouponInfo::couponId).containsExactly(issued.couponId());
    }

    @DisplayName("Redis 가 돌아와 PONG 을 받아도 수용은 닫힌 채이고, 관리자가 다시 연 뒤에만 신규 발급된다.")
    @Test
    void staysClosedAfterRedisRecovers_untilAdminOpens() {
        // arrange
        Long campaignId = couponCampaigns.open(5);
        pauseRedis();
        assertAdmissionUnavailable(campaignId, 1L);

        // act 1: Redis 복구
        resumeRedis();
        awaitRedisPong();

        // assert 1: 건강 확인만으로는 열리지 않는다
        assertThat(admissionSwitch.isOpen()).isFalse();
        assertAdmissionUnavailable(campaignId, 2L);

        // act 2: 관리자 재개
        adminCouponCampaignFacade.open(campaignId);

        // assert 2
        assertThat(couponIssueFacade.issue(campaignId, 2L).status()).isEqualTo(Status.ISSUED);
    }

    @DisplayName("자리 반환이 실패하면 발급한 쿠폰은 유지하고 수용을 닫으며, 관리자가 다시 열 때 자기 token 만 정리한다.")
    @Test
    void keepsCouponAndClosesAdmission_whenFinishFails() {
        // arrange
        Long campaignId = couponCampaigns.open(5);
        doThrow(new CouponAdmissionException("반환 실패"))
            .when(couponIssueAdmission).finish(anyLong(), anyLong(), anyString(), any());

        // act 1
        CouponIssueInfo issued = couponIssueFacade.issue(campaignId, 1L);

        // assert 1: 쿠폰은 그대로, 자리는 남고, 수용은 닫힌다
        assertThat(issued.status()).isEqualTo(Status.ISSUED);
        assertThat(couponRowsOf(campaignId, 1L)).isEqualTo(1);
        assertThat(admissionState.runningTokens(campaignId)).hasSize(1);
        assertThat(admissionSwitch.isOpen()).isFalse();
        assertAdmissionUnavailable(campaignId, 2L);

        // act 2: 반환이 다시 되는 상태에서 관리자가 연다
        doCallRealMethod().when(couponIssueAdmission).finish(anyLong(), anyLong(), anyString(), any());
        adminCouponCampaignFacade.open(campaignId);

        // assert 2: 남은 자기 token 이 정리되고, 결과는 DB 기준으로 기록되며, 신규 발급이 된다
        assertThat(admissionState.runningTokens(campaignId)).isEmpty();
        assertThat(admissionState.winnerOf(campaignId, 1L)).isEqualTo(String.valueOf(issued.couponId()));
        assertThat(couponIssueFacade.issue(campaignId, 2L).status()).isEqualTo(Status.ISSUED);
    }

    @DisplayName("202 로 약속한 뒤 Redis 상태를 잃어도, 재구성하면 약속이 되살아나고 마감 보정에서 쿠폰이 발급된다.")
    @Test
    void keepsPromisedWinner_whenRedisStateIsLostAfterAccepted() {
        // arrange: DB 저장 실패로 202 를 받은 당첨자
        Long campaignId = couponCampaigns.open(5);
        doThrow(new CannotCreateTransactionException("연결 획득 실패"))
            .when(couponIssueWriter).write(eq(campaignId), eq(1L));
        assertThat(couponIssueFacade.issue(campaignId, 1L).status()).isEqualTo(Status.PENDING);

        // act 1: Redis 상태 유실
        admissionState.clear(campaignId);

        // assert 1: 상태를 믿을 수 없으므로 신규 발급은 막힌다
        assertAdmissionUnavailable(campaignId, 2L);

        // act 2: DB 가 정상인 상태에서 관리자가 재구성하고 마감 보정한다
        doCallRealMethod().when(couponIssueWriter).write(anyLong(), anyLong());
        adminCouponCampaignFacade.open(campaignId);
        CouponReconcileInfo reconciled = adminCouponCampaignFacade.reconcile(campaignId);

        // assert 2
        assertThat(reconciled.issued()).isEqualTo(1);
        assertThat(couponRowsOf(campaignId, 1L)).isEqualTo(1);
        assertThat(admissionState.winnerOf(campaignId, 1L)).matches("\\d+");
    }
}
