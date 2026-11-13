package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponIssueAdmissionState;
import com.loopers.application.coupon.CouponIssueTransaction;
import com.loopers.application.coupon.CouponIssueTransactionCompletion;
import com.loopers.application.time.ApplicationTime;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.infrastructure.coupon.RedisCouponIssuePermits;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "coupon.issue.capacity=1",
    "coupon.issue.admission.open-on-startup=false",
    // 이 테스트 전용 연결 풀에서만 잠금 대기 timeout을 1초로 줄인다.
    "datasource.mysql-jpa.main.connection-init-sql=SET SESSION innodb_lock_wait_timeout = 1",
    "product.list-cache.warm-up.enabled=false",
    "payment.periodic-check.enabled=false",
    "order.confirmed-event.relay.enabled=false",
    "order.confirmed-event.outbox.metrics.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponIssueTransactionFailureE2ETest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
        .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS.getMappedPort(6379));
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private CouponCampaignRepository campaigns;
    @Autowired private CouponRepository coupons;
    @Autowired private CouponIssueAdmissionState admission;
    @Autowired private ApplicationTime applicationTime;
    @Autowired private JdbcTemplate jdbc;
    @Autowired
    @Qualifier("redisTemplateMaster")
    private RedisTemplate<String, String> redisTemplate;
    @PersistenceContext private EntityManager entityManager;
    @MockitoSpyBean private CouponIssueTransaction issueTransaction;

    private final List<Long> campaignIds = new ArrayList<>();

    @AfterEach
    void cleanUpOwnData() {
        admission.close();
        redisTemplate.delete(RedisCouponIssuePermits.PERMITS_KEY);
        campaignIds.forEach(campaignId -> {
            jdbc.update("DELETE FROM coupons WHERE campaign_id = ?", campaignId);
            jdbc.update("DELETE FROM coupon_campaigns WHERE id = ?", campaignId);
        });
    }

    @DisplayName("캠페인 행 잠금 대기 timeout으로 rollback이 끝나면 permit을 반환해, 잠금이 풀린 뒤 바로 다시 발급한다.")
    @Test
    void returnsPermitAfterLockWaitTimeoutRollback() throws Exception {
        CouponCampaign campaign = prepareCampaign();
        long userId = 95101L;
        admission.open();

        try (Connection lockHolder = jdbc.getDataSource().getConnection()) {
            lockHolder.setAutoCommit(false);
            try (PreparedStatement lock = lockHolder.prepareStatement(
                "SELECT id FROM coupon_campaigns WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, campaign.getId());
                lock.executeQuery().close();
            }

            mockMvc.perform(post(issuePath(campaign.getId())).header("X-USER-ID", userId))
                .andExpect(status().isInternalServerError());

            assertThat(redisTemplate.opsForSet().size(RedisCouponIssuePermits.PERMITS_KEY)).isZero();
            assertThat(admission.isOpen()).isTrue();
            lockHolder.rollback();
        }

        assertThat(coupons.findIssuedByCampaignIdAndUserId(campaign.getId(), userId)).isEmpty();
        assertThat(campaigns.findById(campaign.getId()).orElseThrow().getIssuedCount()).isZero();
        mockMvc.perform(post(issuePath(campaign.getId())).header("X-USER-ID", userId))
            .andExpect(status().isCreated());
        assertThat(campaigns.findById(campaign.getId()).orElseThrow().getIssuedCount()).isEqualTo(1);
    }

    @DisplayName("commit 직전에 DB 연결이 끊겨 결과를 확인하지 못하면 permit을 남겨 자리를 점유하고, 신규 수용은 열어 둔다.")
    @Test
    void keepsPermitWhenConnectionIsLostBeforeCommit() throws Exception {
        CouponCampaign campaign = prepareCampaign();
        long userId = 95201L;
        admission.open();
        AtomicBoolean killOnce = new AtomicBoolean(true);
        AtomicReference<CouponIssueTransactionCompletion> watched = new AtomicReference<>();
        doAnswer(invocation -> {
            Object issued = invocation.callRealMethod();
            if (killOnce.compareAndSet(true, false)) {
                watched.set(invocation.getArgument(2));
                killCurrentTransactionConnection();
            }
            return issued;
        }).when(issueTransaction).issue(anyLong(), anyLong(), any());

        mockMvc.perform(post(issuePath(campaign.getId())).header("X-USER-ID", userId))
            .andExpect(status().isInternalServerError());

        assertThat(watched.get().isDbWorkDone()).isFalse();
        Set<String> leftTokens = redisTemplate.opsForSet().members(RedisCouponIssuePermits.PERMITS_KEY);
        assertThat(leftTokens).hasSize(1);
        assertThat(admission.isOpen()).isTrue();
        assertThat(coupons.findIssuedByCampaignIdAndUserId(campaign.getId(), userId)).isEmpty();
        assertThat(campaigns.findById(campaign.getId()).orElseThrow().getIssuedCount()).isZero();

        // 남은 token이 유일한 자리를 차지하므로, commit되지 않은 같은 사용자도 정리 전에는 다시 들어오지 못한다.
        mockMvc.perform(post(issuePath(campaign.getId())).header("X-USER-ID", userId))
            .andExpect(status().isTooManyRequests());

        // 운영자가 앞선 DB 작업의 종료와 발급 수를 확인한 뒤 로그에 남은 token만 지우는 절차를 재현한다.
        redisTemplate.opsForSet().remove(RedisCouponIssuePermits.PERMITS_KEY, leftTokens.toArray());
        mockMvc.perform(post(issuePath(campaign.getId())).header("X-USER-ID", userId))
            .andExpect(status().isCreated());
        assertThat(campaigns.findById(campaign.getId()).orElseThrow().getIssuedCount()).isEqualTo(1);
        assertThat(redisTemplate.opsForSet().size(RedisCouponIssuePermits.PERMITS_KEY)).isZero();
    }

    /**
     * 발급 트랜잭션이 쓰는 연결을 다른 연결에서 끊는다. 이후 commit은 서버에 닿지 못하고 서버는 작업을 rollback한다.
     */
    private void killCurrentTransactionConnection() {
        Number connectionId = (Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult();
        CompletableFuture.runAsync(() -> jdbc.execute("KILL CONNECTION " + connectionId.longValue())).join();
    }

    private CouponCampaign prepareCampaign() {
        Instant now = applicationTime.now().toInstant();
        CouponCampaign campaign = campaigns.save(CouponCampaign.create("coupon-transaction-failure-"
            + System.nanoTime(), 3, "5,000원 할인권", now.minusSeconds(60), now.plusSeconds(3600)));
        campaignIds.add(campaign.getId());
        coupons.saveStock(IntStream.range(0, 3).mapToObj(index -> Coupon.create(campaign.getId())).toList());
        entityManager.clear();
        return campaign;
    }

    private String issuePath(Long campaignId) {
        return "/api/v1/coupon-campaigns/%d/issues".formatted(campaignId);
    }
}
