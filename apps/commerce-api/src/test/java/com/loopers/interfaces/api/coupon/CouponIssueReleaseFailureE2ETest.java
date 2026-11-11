package com.loopers.interfaces.api.coupon;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.loopers.application.coupon.CouponIssueAdmissionState;
import com.loopers.application.coupon.CouponIssuePermits;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "coupon.issue.capacity=3",
    "coupon.issue.admission.open-on-startup=false",
    "product.list-cache.warm-up.enabled=false",
    "payment.periodic-check.enabled=false",
    "order.confirmed-event.relay.enabled=false",
    "order.confirmed-event.outbox.metrics.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponIssueReleaseFailureE2ETest {

    private static final int REDIS_PORT = freePort();
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
        .withExposedPorts(6379)
        .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
            new PortBinding(Ports.Binding.bindPort(REDIS_PORT), new ExposedPort(6379))
        ));

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS_PORT);
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS_PORT);
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
    @MockitoSpyBean private CouponIssuePermits permitStore;

    private final List<Long> campaignIds = new ArrayList<>();

    @AfterEach
    void cleanUpOwnData() {
        admission.close();
        campaignIds.forEach(campaignId -> {
            jdbc.update("DELETE FROM coupons WHERE campaign_id = ?", campaignId);
            jdbc.update("DELETE FROM coupon_campaigns WHERE id = ?", campaignId);
        });
    }

    @DisplayName("첫 SREM이 0이면 commit된 결과를 응답하고 신규 수용을 닫는다.")
    @Test
    void preservesCommittedCouponWhenPermitWasLostBeforeRelease() throws Exception {
        CouponCampaign first = prepareCampaign(1);
        CouponCampaign second = prepareCampaign(5);
        admission.open();
        doAnswer(invocation -> {
            String token = invocation.getArgument(0);
            redisTemplate.opsForSet().remove(RedisCouponIssuePermits.PERMITS_KEY, token);
            return invocation.callRealMethod();
        }).when(permitStore).release(anyString());

        mockMvc.perform(post(issuePath(first.getId())).header("X-USER-ID", 93001L))
            .andExpect(status().isCreated());

        assertThat(coupons.countIssuedByCampaignId(first.getId())).isEqualTo(1);
        assertThat(campaigns.findById(first.getId()).orElseThrow().getIssuedCount()).isEqualTo(1);
        assertThat(admission.isOpen()).isFalse();
        assertThat(redisTemplate.opsForSet().size(RedisCouponIssuePermits.PERMITS_KEY)).isZero();

        mockMvc.perform(post(issuePath(second.getId())).header("X-USER-ID", 93002L))
            .andExpect(status().isServiceUnavailable());
        assertThat(coupons.countIssuedByCampaignId(second.getId())).isZero();
    }

    private CouponCampaign prepareCampaign(long quota) {
        Instant now = applicationTime.now().toInstant();
        CouponCampaign campaign = campaigns.save(CouponCampaign.create("coupon-release-failure-" + System.nanoTime(),
            quota, "5,000원 할인권", now.minusSeconds(60), now.plusSeconds(3600)));
        campaignIds.add(campaign.getId());
        coupons.saveStock(IntStream.range(0, (int) quota).mapToObj(index -> Coupon.create(campaign.getId())).toList());
        entityManager.clear();
        return campaign;
    }

    private String issuePath(Long campaignId) {
        return "/api/v1/coupon-campaigns/%d/issues".formatted(campaignId);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
