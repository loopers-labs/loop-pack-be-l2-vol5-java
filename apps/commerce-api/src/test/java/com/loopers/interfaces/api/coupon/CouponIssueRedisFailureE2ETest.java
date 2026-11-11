package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.CouponIssueAdmissionState;
import com.loopers.application.time.ApplicationTime;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class CouponIssueRedisFailureE2ETest {

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
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CouponCampaignRepository campaigns;
    @Autowired private CouponRepository coupons;
    @Autowired private CouponIssueAdmissionState admission;
    @Autowired private ApplicationTime applicationTime;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager entityManager;

    private final List<Long> campaignIds = new ArrayList<>();

    @AfterEach
    void cleanUpOwnData() {
        admission.close();
        campaignIds.forEach(campaignId -> {
            jdbc.update("DELETE FROM coupons WHERE campaign_id = ?", campaignId);
            jdbc.update("DELETE FROM coupon_campaigns WHERE id = ?", campaignId);
        });
    }

    @DisplayName("Redis 중단은 신규 발급을 503으로 닫고 기존 본인 목록과 종료된 결과 조회는 유지한다.")
    @Test
    void failsClosedDuringRedisOutageAndDoesNotReopenAfterRecovery() throws Exception {
        CouponCampaign alreadyIssuedCampaign = prepareCampaign(1);
        long userId = 91001L;
        admission.open();
        JsonNode issued = data(mockMvc.perform(post(issuePath(alreadyIssuedCampaign.getId()))
                .header("X-USER-ID", userId))
            .andExpect(status().isCreated()).andReturn());
        CouponCampaign newCampaign = prepareCampaign(5);

        REDIS.stop();

        mockMvc.perform(post(issuePath(newCampaign.getId())).header("X-USER-ID", 91002L))
            .andExpect(status().isServiceUnavailable());
        assertThat(admission.isOpen()).isFalse();
        assertThat(coupons.countIssuedByCampaignId(newCampaign.getId())).isZero();
        assertThat(campaigns.findById(newCampaign.getId()).orElseThrow().getIssuedCount()).isZero();

        JsonNode list = objectMapper.readTree(mockMvc.perform(get("/api/v1/coupons")
                .header("X-USER-ID", userId))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.path("data").size()).isEqualTo(1);
        assertThat(list.path("data").get(0).path("couponId").asLong()).isEqualTo(issued.path("couponId").asLong());
        JsonNode repeated = data(mockMvc.perform(post(issuePath(alreadyIssuedCampaign.getId()))
                .header("X-USER-ID", userId))
            .andExpect(status().isOk()).andReturn());
        assertThat(repeated.path("couponId").asLong()).isEqualTo(issued.path("couponId").asLong());

        // Redis 응답이 돌아와도 이 컨텍스트의 수용 상태는 자동으로 열리지 않는다.
        REDIS.start();
        mockMvc.perform(post(issuePath(newCampaign.getId())).header("X-USER-ID", 91003L))
            .andExpect(status().isServiceUnavailable());
        assertThat(coupons.countIssuedByCampaignId(newCampaign.getId())).isZero();
    }

    private CouponCampaign prepareCampaign(long quota) {
        Instant now = applicationTime.now().toInstant();
        CouponCampaign campaign = campaigns.save(CouponCampaign.create("coupon-redis-failure-" + System.nanoTime(),
            quota, "5,000원 할인권", now.minusSeconds(60), now.plusSeconds(3600)));
        campaignIds.add(campaign.getId());
        coupons.saveStock(IntStream.range(0, (int) quota).mapToObj(index -> Coupon.create(campaign.getId())).toList());
        entityManager.clear();
        return campaign;
    }

    private JsonNode data(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
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
