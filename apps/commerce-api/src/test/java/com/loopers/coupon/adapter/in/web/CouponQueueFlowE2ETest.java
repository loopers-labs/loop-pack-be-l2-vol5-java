package com.loopers.coupon.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.coupon.adapter.out.persistence.CouponCampaignJpaRepository;
import com.loopers.coupon.adapter.out.persistence.CouponJpaRepository;
import com.loopers.coupon.adapter.out.persistence.UserCouponJpaRepository;
import com.loopers.coupon.application.QueueAdmissionService;
import com.loopers.coupon.domain.CouponCampaign;
import com.loopers.coupon.domain.UserCoupon;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 대기열 → 입장 → 발급 (D-4, D-2, D-7). 구간을 1시간·구간당 1명으로 두어, "바로 입장"은 테스트마다 첫 사람 한 명만 된다.
 */
@SpringBootTest(properties = {"coupon.queue.admission.per-tick=1", "coupon.queue.admission.tick=1h"})
@AutoConfigureMockMvc
class CouponQueueFlowE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private CouponCampaignJpaRepository campaignJpaRepository;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private UserCouponJpaRepository userCouponJpaRepository;

    @Autowired
    private QueueAdmissionService admissionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @Autowired
    private com.loopers.coupon.application.OpenCampaigns openCampaigns;

    @Autowired
    private com.loopers.coupon.application.SoldOutFlags soldOutFlags;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        // 테이블을 비우면 id가 다시 1부터라, 서버 메모리의 캠페인·품절 표시가 다음 테스트의 같은 id에 남는다
        openCampaigns.refresh();
        soldOutFlags.clear();
    }

    private CouponCampaign openCampaign(int total, Integer capacity) {
        CouponCampaign campaign = CouponTestFixtures.openCampaign(campaignJpaRepository, couponJpaRepository, total, capacity);
        openCampaigns.refresh();
        admissionService.recover(campaign.getId()); // 캠페인 시작 = 상태 키를 쓰는 자동 복구 경로 (D-4-7)
        return campaign;
    }

    private Long user() {
        return userJpaRepository.save(new UserModel("고객")).getId();
    }

    private String base(CouponCampaign campaign) {
        return "/api/v1/coupon-campaigns/" + campaign.getId();
    }

    private MvcResult call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, Long userId, int expected) throws Exception {
        return mockMvc.perform(request.header("X-USER-ID", userId)).andExpect(status().is(expected)).andReturn();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode register(CouponCampaign campaign, Long userId) throws Exception {
        return data(call(post(base(campaign) + "/queue"), userId, 200));
    }

    @DisplayName("줄이 비어 있으면 바로 입장 → 발급 202 → 당첨 기록 1행 → 상태 ISSUED. 다시 발급하면 202, 쿠폰이 생기면 200 + 쿠폰 id.")
    @Test
    void admittedUserIsIssued() throws Exception {
        CouponCampaign campaign = openCampaign(5, null);
        Long me = user();

        assertThat(register(campaign, me).path("status").asText()).isEqualTo("ADMITTED");
        JsonNode issued = data(call(post(base(campaign) + "/issues"), me, 202));
        assertThat(issued.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_issue_outbox WHERE user_id = ?", Long.class, me)).isEqualTo(1);
        assertThat(data(call(get(base(campaign) + "/queue"), me, 200)).path("status").asText()).isEqualTo("ISSUED");

        call(post(base(campaign) + "/issues"), me, 202);
        UserCoupon coupon = userCouponJpaRepository.save(new UserCoupon(
            couponJpaRepository.findByCampaignIdOrderByIdAsc(campaign.getId()).get(0), me, ZonedDateTime.now()));
        JsonNode again = data(call(post(base(campaign) + "/issues"), me, 200));
        assertThat(again.path("couponIds").get(0).asLong()).isEqualTo(coupon.getId());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_issue_outbox WHERE user_id = ?", Long.class, me)).isEqualTo(1);
    }

    @DisplayName("필수 실험 ② 등록 순서 = 줄 순서. 다시 등록하면 원래 자리, 앞 인원과 pollAfterMs(0.5–5초)를 준다.")
    @Test
    void queueKeepsArrivalOrder() throws Exception {
        CouponCampaign campaign = openCampaign(100, null);
        Long first = user();
        assertThat(register(campaign, first).path("status").asText()).isEqualTo("ADMITTED"); // 구간당 1명 바로 입장
        List<Long> waiting = List.of(user(), user(), user(), user());
        for (int i = 0; i < waiting.size(); i++) {
            JsonNode state = register(campaign, waiting.get(i));
            assertThat(state.path("status").asText()).isEqualTo("WAITING");
            assertThat(state.path("ahead").asLong()).isEqualTo(i);
            assertThat(state.path("pollAfterMs").asLong()).isBetween(500L, 5_000L);
        }

        JsonNode again = register(campaign, waiting.get(2));
        JsonNode polled = data(call(get(base(campaign) + "/queue"), waiting.get(3), 200));

        assertThat(again.path("ahead").asLong()).isEqualTo(2);
        assertThat(polled.path("ahead").asLong()).isEqualTo(3);
    }

    @DisplayName("필수 실험 ② 같은 사람이 동시에 여러 번 등록해도 줄에는 한 자리다.")
    @Test
    void concurrentRegisterOfSameUserTakesOneSlot() throws Exception {
        CouponCampaign campaign = openCampaign(100, null);
        register(campaign, user()); // 바로 입장으로 구간 한도를 쓴다
        Long me = user();

        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JsonNode>> futures = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            futures.add(executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return register(campaign, me);
            }));
        }
        start.countDown();
        for (Future<JsonNode> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).path("ahead").asLong()).isZero();
        }
        executor.shutdownNow();
    }

    @DisplayName("입장 전 발급은 409 QUEUE_NOT_ADMITTED이고 기록이 없다.")
    @Test
    void notAdmittedCannotIssue() throws Exception {
        CouponCampaign campaign = openCampaign(100, null);
        register(campaign, user());
        Long waiting = user();
        register(campaign, waiting);

        MvcResult result = call(post(base(campaign) + "/issues"), waiting, 409);

        assertThat(result.getResponse().getContentAsString()).contains("Queue Not Admitted");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_issue_outbox", Long.class)).isZero();
    }

    @DisplayName("품절되면 등록은 SOLD_OUT(200)이고 발급은 409다. 줄이 가득 차면 등록은 429 + Retry-After다.")
    @Test
    void soldOutAndQueueFull() throws Exception {
        CouponCampaign one = openCampaign(1, 1);
        Long winner = user();
        register(one, winner);
        call(post(base(one) + "/issues"), winner, 202);
        assertThat(register(one, user()).path("status").asText()).isEqualTo("SOLD_OUT");

        redisCleanUp.truncateAll();
        CouponCampaign full = openCampaign(1, 1);
        register(full, user());           // 바로 입장 (줄 0명)
        register(full, user());           // 줄 1명 = 가득
        MvcResult rejected = call(post(base(full) + "/queue"), user(), 429);
        assertThat(rejected.getResponse().getHeader("Retry-After")).isEqualTo("1");
    }

    @DisplayName("발급 기간이 아니면 409 CAMPAIGN_NOT_OPEN, 없는 캠페인이면 404다.")
    @Test
    void campaignNotOpenOrMissing() throws Exception {
        ZonedDateTime now = ZonedDateTime.now();
        CouponCampaign later = campaignJpaRepository.save(new CouponCampaign("내일", 10, null, now.plusDays(1), now.plusDays(2)));

        call(post("/api/v1/coupon-campaigns/" + later.getId() + "/queue"), user(), 409);
        call(post("/api/v1/coupon-campaigns/999999/queue"), user(), 404);
    }
}
