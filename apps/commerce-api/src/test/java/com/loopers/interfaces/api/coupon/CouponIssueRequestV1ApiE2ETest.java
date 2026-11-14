package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.CouponIssueAdmissionState;
import com.loopers.application.coupon.CouponIssueQueue;
import com.loopers.application.time.ApplicationTime;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponIssueRequest;
import com.loopers.domain.coupon.CouponIssueRequestReason;
import com.loopers.infrastructure.coupon.RedisCouponIssuePermits;
import com.loopers.infrastructure.coupon.RedisCouponIssueQueue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "coupon.issue.capacity=3",
    "coupon.issue.admission.open-on-startup=false"
})
@AutoConfigureMockMvc
class CouponIssueRequestV1ApiE2ETest {

    private static final String SUBMIT_PATH = "/api/v1/coupon-campaigns/%d/issue-requests";
    private static final String LOOKUP_PATH = "/api/v1/coupon-issue-requests/%s";
    private static final String ISSUE_PATH = "/api/v1/coupon-campaigns/%d/issues";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CouponCampaignRepository campaigns;
    @Autowired private CouponIssueAdmissionState admission;
    @Autowired private CouponIssueQueue queue;
    @Autowired private ApplicationTime applicationTime;
    @Autowired private JdbcTemplate jdbc;
    @Autowired
    @Qualifier("redisTemplateMaster")
    private RedisTemplate<String, String> redisTemplate;
    @PersistenceContext private EntityManager entityManager;

    private final List<Long> campaignIds = new ArrayList<>();
    private Long campaignId;

    @BeforeEach
    void prepareCampaign() {
        cleanUpRedis();
        campaignId = createCampaign(5);
        admission.open();
    }

    @AfterEach
    void cleanUpOwnData() {
        admission.close();
        cleanUpRedis();
        campaignIds.forEach(id -> {
            jdbc.update("DELETE FROM coupons WHERE campaign_id = ?", id);
            jdbc.update("DELETE FROM coupon_campaigns WHERE id = ?", id);
        });
    }

    @DisplayName("새 접수는 202와 대기 상태를 돌려주고, 본인 조회는 같은 작업을 200으로 돌려준다.")
    @Test
    void acceptsNewRequestAndReturnsItOnLookup() throws Exception {
        JsonNode accepted = data(submit(campaignId, 72001L).andExpect(status().isAccepted()));

        assertThat(accepted.path("requestId").asText()).isNotBlank();
        assertThat(accepted.path("campaignId").asLong()).isEqualTo(campaignId);
        assertThat(accepted.path("status").asText()).isEqualTo("WAITING");
        assertThat(accepted.path("requestedAt").asText()).isNotBlank();
        assertAbsent(accepted, "couponId");
        assertAbsent(accepted, "reasonCode");

        JsonNode found = data(lookup(accepted.path("requestId").asText(), 72001L).andExpect(status().isOk()));
        assertThat(found.path("requestId").asText()).isEqualTo(accepted.path("requestId").asText());
        assertThat(found.path("status").asText()).isEqualTo("WAITING");
    }

    @DisplayName("같은 사용자가 다시 접수하면 같은 작업 ID로 202를 돌려주고 대기 항목은 하나다.")
    @Test
    void returnsSameRequestForRepeatedSubmission() throws Exception {
        String requestId = data(submit(campaignId, 72002L).andExpect(status().isAccepted())).path("requestId").asText();

        JsonNode again = data(submit(campaignId, 72002L).andExpect(status().isAccepted()));

        assertThat(again.path("requestId").asText()).isEqualTo(requestId);
        assertThat(waitingIds()).containsExactly(requestId);
    }

    @DisplayName("요청자 헤더가 없거나 경로 형식이 틀리면 400으로 거절하고 작업을 만들지 않는다.")
    @Test
    void rejectsMissingHeaderOrMalformedPath() throws Exception {
        mockMvc.perform(post(SUBMIT_PATH.formatted(campaignId))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/coupon-campaigns/not-a-number/issue-requests").header("X-USER-ID", 72003L))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get(LOOKUP_PATH.formatted("any-request-id"))).andExpect(status().isBadRequest());

        assertThat(storedJobCount()).isZero();
    }

    @DisplayName("없는 캠페인은 404, 품절 캠페인은 409로 거절하고 작업을 만들지 않는다.")
    @Test
    void rejectsMissingOrSoldOutCampaign() throws Exception {
        Long soldOutCampaignId = createCampaign(1);
        mockMvc.perform(post(ISSUE_PATH.formatted(soldOutCampaignId)).header("X-USER-ID", 72010L))
            .andExpect(status().isCreated());

        assertFail(submit(Long.MAX_VALUE, 72011L).andExpect(status().isNotFound()));
        assertFail(submit(soldOutCampaignId, 72012L).andExpect(status().isConflict()));
        assertThat(storedJobCount()).isZero();
    }

    @DisplayName("신규 수용이 닫혀 있으면 새 접수는 503으로 거절하고, 기존 작업의 재접수는 202를 돌려준다.")
    @Test
    void rejectsNewSubmissionWhileAdmissionIsClosed() throws Exception {
        String requestId = data(submit(campaignId, 72020L).andExpect(status().isAccepted())).path("requestId").asText();
        admission.close();

        assertFail(submit(campaignId, 72021L).andExpect(status().isServiceUnavailable()));
        JsonNode existing = data(submit(campaignId, 72020L).andExpect(status().isAccepted()));

        assertThat(existing.path("requestId").asText()).isEqualTo(requestId);
        assertThat(storedJobCount()).isEqualTo(1L);
    }

    @DisplayName("미완료 작업이 한도 20건에 차면 새 사용자는 429를 받고, 이미 접수한 사용자는 202를 받는다.")
    @Test
    void rejectsNewUserWhenQueueIsFull() throws Exception {
        for (long userId = 72100L; userId < 72120L; userId++) {
            submit(campaignId, userId).andExpect(status().isAccepted());
        }

        assertFail(submit(campaignId, 72120L).andExpect(status().isTooManyRequests()));
        submit(campaignId, 72100L).andExpect(status().isAccepted());
        assertThat(waitingIds()).hasSize(20);
    }

    @DisplayName("작업은 없고 동기 API로 받은 쿠폰이 있으면 작업을 만들지 않고 그 쿠폰을 200으로 돌려준다.")
    @Test
    void returnsExistingCouponWithoutRequestId() throws Exception {
        JsonNode issued = data(mockMvc.perform(post(ISSUE_PATH.formatted(campaignId)).header("X-USER-ID", 72030L))
            .andExpect(status().isCreated()).andReturn());

        JsonNode result = data(submit(campaignId, 72030L).andExpect(status().isOk()));

        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.path("couponId").asLong()).isEqualTo(issued.path("couponId").asLong());
        assertAbsent(result, "requestId");
        assertAbsent(result, "requestedAt");
        assertThat(storedJobCount()).isZero();
    }

    @DisplayName("다른 사용자의 작업이나 없는 작업을 조회하면 같은 404로 응답한다.")
    @Test
    void hidesOtherUsersRequest() throws Exception {
        String requestId = data(submit(campaignId, 72040L).andExpect(status().isAccepted())).path("requestId").asText();

        assertFail(lookup(requestId, 72041L).andExpect(status().isNotFound()));
        assertFail(lookup("unknown-request-id", 72040L).andExpect(status().isNotFound()));
    }

    @DisplayName("업무 거절로 끝난 작업은 다시 접수해도 저장된 실패 결과를 200으로 돌려주고, 조회도 200이다.")
    @Test
    void returnsStoredBusinessFailure() throws Exception {
        CouponIssueRequest failed = failedRequest(72050L, CouponIssueRequestReason.SOLD_OUT);

        JsonNode resubmitted = data(submit(campaignId, 72050L).andExpect(status().isOk()));
        JsonNode found = data(lookup(failed.getRequestId(), 72050L).andExpect(status().isOk()));

        for (JsonNode node : List.of(resubmitted, found)) {
            assertThat(node.path("requestId").asText()).isEqualTo(failed.getRequestId());
            assertThat(node.path("status").asText()).isEqualTo("FAILED");
            assertThat(node.path("reasonCode").asText()).isEqualTo("SOLD_OUT");
            assertThat(node.path("message").asText()).isEqualTo("쿠폰이 모두 발급된 캠페인입니다.");
        }
        assertThat(waitingIds()).isEmpty();
    }

    @DisplayName("시스템 실패로 끝난 작업을 다시 접수하면 같은 작업 ID로 다시 대기시켜 202를 돌려준다.")
    @Test
    void requeuesSystemFailureOnResubmission() throws Exception {
        CouponIssueRequest failed = failedRequest(72060L, CouponIssueRequestReason.DB_ERROR);

        JsonNode resubmitted = data(submit(campaignId, 72060L).andExpect(status().isAccepted()));

        assertThat(resubmitted.path("requestId").asText()).isEqualTo(failed.getRequestId());
        assertThat(resubmitted.path("status").asText()).isEqualTo("WAITING");
        assertAbsent(resubmitted, "reasonCode");
        assertThat(waitingIds()).containsExactly(failed.getRequestId());
    }

    private CouponIssueRequest failedRequest(Long userId, CouponIssueRequestReason reason) {
        CouponIssueRequest request = CouponIssueRequest.create(campaignId, userId, applicationTime.now().toInstant());
        queue.submit(request, 20);
        queue.claim("e2e-token-" + userId);
        queue.recordFailed(request.getRequestId(), "e2e-token-" + userId, reason);
        return request;
    }

    private Long createCampaign(int quota) {
        Instant now = applicationTime.now().toInstant();
        CouponCampaign campaign = campaigns.save(CouponCampaign.create("coupon-queue-api-" + System.nanoTime(),
            quota, "5,000원 할인권", now.minusSeconds(60), now.plusSeconds(3600)));
        campaignIds.add(campaign.getId());
        entityManager.clear();
        return campaign.getId();
    }

    private ResultActions submit(Long targetCampaignId, Long userId) throws Exception {
        return mockMvc.perform(post(SUBMIT_PATH.formatted(targetCampaignId)).header("X-USER-ID", userId));
    }

    private ResultActions lookup(String requestId, Long userId) throws Exception {
        return mockMvc.perform(get(LOOKUP_PATH.formatted(requestId)).header("X-USER-ID", userId));
    }

    private JsonNode data(ResultActions actions) throws Exception {
        return data(actions.andReturn());
    }

    private JsonNode data(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("meta").path("result").asText()).isEqualTo("SUCCESS");
        return body.path("data");
    }

    /**
     * 공통 Jackson 설정(NON_NULL)에 따라 값이 없는 필드는 응답에서 빠진다.
     */
    private static void assertAbsent(JsonNode node, String field) {
        assertThat(node.has(field)).as("%s 필드는 응답에 없어야 한다.", field).isFalse();
    }

    private void assertFail(ResultActions actions) throws Exception {
        JsonNode body = objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
        assertThat(body.path("meta").path("result").asText()).isEqualTo("FAIL");
    }

    private long storedJobCount() {
        return redisTemplate.opsForHash().size(RedisCouponIssueQueue.JOBS_KEY);
    }

    private List<String> waitingIds() {
        return redisTemplate.opsForList().range(RedisCouponIssueQueue.WAITING_KEY, 0, -1);
    }

    private void cleanUpRedis() {
        redisTemplate.delete(List.of(RedisCouponIssuePermits.PERMITS_KEY, RedisCouponIssueQueue.WAITING_KEY,
            RedisCouponIssueQueue.PROCESSING_KEY, RedisCouponIssueQueue.JOBS_KEY, RedisCouponIssueQueue.REQUESTS_KEY));
    }
}
