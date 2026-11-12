package com.loopers.coupon.adapter.in.web;

import com.loopers.coupon.adapter.out.persistence.CouponCampaignJpaRepository;
import com.loopers.coupon.adapter.out.persistence.CouponJpaRepository;
import com.loopers.coupon.application.OpenCampaigns;
import com.loopers.coupon.application.QueueAdmissionService;
import com.loopers.coupon.application.SoldOutFlags;
import com.loopers.coupon.application.port.out.CouponGatePort;
import com.loopers.coupon.domain.CouponCampaign;
import com.loopers.coupon.support.CouponTestFixtures;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 격벽 (D-6). 발급 Bulkhead 동시 1개·대기 0, 대기열 RateLimiter 10초에 2건으로 줄여 막히는 모습을 본다.
 */
// 이 클래스만 쓰는 설정(속성·mock)이라 컨텍스트를 다른 테스트와 공유하지 않는다. 끝나면 닫아 커넥션 풀(최소 30개)을 돌려준다 —
// 닫지 않으면 캐시된 컨텍스트들의 커넥션이 MySQL max_connections(151)를 넘어 다른 테스트가 뜨지 못했다(11-12).
@DirtiesContext
@SpringBootTest(properties = {
    "resilience4j.bulkhead.instances.couponIssue.max-concurrent-calls=1",
    "resilience4j.bulkhead.instances.couponIssue.max-wait-duration=0",
    "resilience4j.ratelimiter.instances.couponQueue.limit-for-period=2",
    "resilience4j.ratelimiter.instances.couponQueue.limit-refresh-period=10s"
})
@AutoConfigureMockMvc
class CouponIsolationE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private CouponGatePort gate;

    @Autowired
    private QueueAdmissionService admissionService;

    @Autowired
    private OpenCampaigns openCampaigns;

    @Autowired
    private SoldOutFlags soldOutFlags;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private CouponCampaignJpaRepository campaignJpaRepository;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        reset(gate);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        openCampaigns.refresh();
        soldOutFlags.clear();
    }

    private CouponCampaign open() {
        CouponCampaign campaign = CouponTestFixtures.openCampaign(campaignJpaRepository, couponJpaRepository, 10, null);
        openCampaigns.refresh();
        admissionService.recover(campaign.getId());
        return campaign;
    }

    @DisplayName("발급이 처리 중일 때 같은 서버로 온 두 번째 발급은 Bulkhead에 막혀 503이고, 판정(Lua)까지 가지 않는다.")
    @Test
    void bulkheadRejectsSecondConcurrentIssue() throws Exception {
        CouponCampaign campaign = open();
        Long first = userJpaRepository.save(new UserModel("고객1")).getId();
        Long second = userJpaRepository.save(new UserModel("고객2")).getId();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS); // DB가 느려진 상황: 첫 발급이 오래 걸린다
            return invocation.callRealMethod();
        }).when(gate).issue(any(), any(), anyInt());
        String base = "/api/v1/coupon-campaigns/" + campaign.getId();

        CompletableFuture<Integer> slow = CompletableFuture.supplyAsync(() -> {
            try {
                return mockMvc.perform(post(base + "/issues").header("X-USER-ID", first)).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        int rejected = mockMvc.perform(post(base + "/issues").header("X-USER-ID", second)).andReturn().getResponse().getStatus();
        release.countDown();

        assertThat(rejected).isEqualTo(503);
        assertThat(slow.get(10, TimeUnit.SECONDS)).isEqualTo(409); // 입장 전이라 판정은 QUEUE_NOT_ADMITTED
        org.mockito.Mockito.verify(gate, org.mockito.Mockito.times(1)).issue(any(), any(), anyInt());
    }

    @DisplayName("대기열 등록·조회가 서버당 한도(테스트: 10초에 2건)를 넘으면 429 + Retry-After다.")
    @Test
    void rateLimiterRejectsQueueBurst() throws Exception {
        CouponCampaign campaign = open();
        Long me = userJpaRepository.save(new UserModel("고객")).getId();
        String base = "/api/v1/coupon-campaigns/" + campaign.getId();

        mockMvc.perform(post(base + "/queue").header("X-USER-ID", me)).andExpect(status().isOk());
        mockMvc.perform(get(base + "/queue").header("X-USER-ID", me)).andExpect(status().isOk());
        String retryAfter = mockMvc.perform(get(base + "/queue").header("X-USER-ID", me))
            .andExpect(status().isTooManyRequests()).andReturn().getResponse().getHeader("Retry-After");

        assertThat(retryAfter).isEqualTo("1");
    }
}
