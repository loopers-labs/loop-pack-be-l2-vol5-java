package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.CouponIssueAdmissionState;
import com.loopers.application.coupon.CouponIssuePermits;
import com.loopers.application.coupon.CouponIssueQueue;
import com.loopers.application.coupon.CouponIssueQueueUnavailableException;
import com.loopers.application.coupon.CouponIssueQueueWorkResult;
import com.loopers.application.coupon.CouponIssueQueueWorker;
import com.loopers.application.coupon.CouponIssueTransaction;
import com.loopers.application.coupon.CouponPermitStoreUnavailableException;
import com.loopers.application.time.ApplicationTime;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponIssueRequestStatus;
import com.loopers.domain.coupon.CouponRepository;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 MySQL과 Redis에서 대기열 접수, worker 처리, 결과 조회를 확인한다. worker는 자동 실행 대신 직접 호출한다.
 * 실행 자리는 1개로 두고, 잠금 대기 timeout을 1초로 줄인 이 클래스 전용 연결 풀을 쓴다.
 */
@SpringBootTest(properties = {
    "coupon.issue.capacity=1",
    "coupon.issue.admission.open-on-startup=false",
    "datasource.mysql-jpa.main.connection-init-sql=SET SESSION innodb_lock_wait_timeout = 1",
    "product.list-cache.warm-up.enabled=false",
    "payment.periodic-check.enabled=false",
    "order.confirmed-event.relay.enabled=false",
    "order.confirmed-event.outbox.metrics.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponIssueQueueWorkerE2ETest {

    private static final String SUBMIT_PATH = "/api/v1/coupon-campaigns/%d/issue-requests";
    private static final String LOOKUP_PATH = "/api/v1/coupon-issue-requests/%s";
    private static final String ISSUE_PATH = "/api/v1/coupon-campaigns/%d/issues";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CouponCampaignRepository campaigns;
    @Autowired private CouponRepository coupons;
    @Autowired private CouponIssueAdmissionState admission;
    @Autowired private CouponIssueQueueWorker worker;
    @Autowired private ApplicationTime applicationTime;
    @Autowired private JdbcTemplate jdbc;
    @Autowired
    @Qualifier("redisTemplateMaster")
    private RedisTemplate<String, String> redisTemplate;
    @PersistenceContext private EntityManager entityManager;
    @MockitoSpyBean private CouponIssuePermits permitStore;
    @MockitoSpyBean private CouponIssueQueue queueStore;
    @MockitoSpyBean private CouponIssueTransaction issueTransaction;

    private final List<Long> campaignIds = new ArrayList<>();
    private Long campaignId;

    @BeforeEach
    void prepare() {
        cleanUpRedis();
        campaignId = createCampaign(5);
        admission.open();
    }

    @AfterEach
    void cleanUp() {
        admission.close();
        cleanUpRedis();
        campaignIds.forEach(id -> {
            jdbc.update("DELETE FROM coupons WHERE campaign_id = ?", id);
            jdbc.update("DELETE FROM coupon_campaigns WHERE id = ?", id);
        });
    }

    @DisplayName("접수한 작업을 worker가 처리하면 쿠폰이 발급되고 조회 결과가 성공으로 바뀐다.")
    @Test
    void issuesSubmittedRequestThroughWorker() throws Exception {
        String requestId = submitAccepted(campaignId, 73001L);

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.PROCESSED);

        JsonNode found = lookup(requestId, 73001L);
        assertThat(found.path("status").asText()).isEqualTo("SUCCEEDED");
        Coupon issued = coupons.findIssuedByCampaignIdAndUserId(campaignId, 73001L).orElseThrow();
        assertThat(found.path("couponId").asLong()).isEqualTo(issued.getId());
        assertThat(permitCount()).isZero();
        assertThat(listIds(RedisCouponIssueQueue.WAITING_KEY)).isEmpty();
        assertThat(listIds(RedisCouponIssueQueue.PROCESSING_KEY)).isEmpty();
    }

    @DisplayName("동기 요청이 실행 자리를 차지하고 있으면 worker는 작업을 건드리지 않고, 자리가 비면 처리한다.")
    @Test
    void sharesPermitsWithSynchronousRequests() throws Exception {
        String requestId = submitAccepted(campaignId, 73002L);
        redisTemplate.opsForSet().add(RedisCouponIssuePermits.PERMITS_KEY, "sync-request-in-flight");

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.NO_PERMIT);
        assertThat(listIds(RedisCouponIssueQueue.WAITING_KEY)).containsExactly(requestId);
        assertThat(lookup(requestId, 73002L).path("status").asText()).isEqualTo("WAITING");

        redisTemplate.opsForSet().remove(RedisCouponIssuePermits.PERMITS_KEY, "sync-request-in-flight");
        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.PROCESSED);
        assertThat(lookup(requestId, 73002L).path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @DisplayName("접수 뒤 다른 사용자가 마지막 쿠폰을 받으면 worker는 품절 실패로 기록하고 permit을 반환한다.")
    @Test
    void recordsSoldOutWhenCampaignSellsOutBeforeProcessing() throws Exception {
        Long smallCampaignId = createCampaign(1);
        String requestId = submitAccepted(smallCampaignId, 73003L);
        mockMvc.perform(post(ISSUE_PATH.formatted(smallCampaignId)).header("X-USER-ID", 73004L))
            .andExpect(status().isCreated());

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.PROCESSED);

        JsonNode found = lookup(requestId, 73003L);
        assertThat(found.path("status").asText()).isEqualTo("FAILED");
        assertThat(found.path("reasonCode").asText()).isEqualTo("SOLD_OUT");
        assertThat(permitCount()).isZero();
        assertThat(coupons.findIssuedByCampaignIdAndUserId(smallCampaignId, 73003L)).isEmpty();
    }

    @DisplayName("잠금 대기 timeout으로 끝난 작업은 시스템 실패로 기록되고, 다시 접수하면 같은 ID로 처리돼 발급된다.")
    @Test
    void retriesSystemFailureAfterResubmission() throws Exception {
        String requestId = submitAccepted(campaignId, 73005L);

        try (Connection lockHolder = jdbc.getDataSource().getConnection()) {
            lockHolder.setAutoCommit(false);
            try (PreparedStatement lock = lockHolder.prepareStatement(
                "SELECT id FROM coupon_campaigns WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, campaignId);
                lock.executeQuery().close();
            }

            assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.PROCESSED);
            JsonNode failed = lookup(requestId, 73005L);
            assertThat(failed.path("status").asText()).isEqualTo("FAILED");
            assertThat(failed.path("reasonCode").asText()).isEqualTo("DB_ERROR");
            assertThat(failed.path("message").asText()).contains("다시 접수할 수 있습니다");
            assertThat(permitCount()).isZero();
            assertThat(admission.isOpen()).isTrue();
            lockHolder.rollback();
        }

        JsonNode resubmitted = data(submit(campaignId, 73005L).andExpect(status().isAccepted()));
        assertThat(resubmitted.path("requestId").asText()).isEqualTo(requestId);
        assertThat(resubmitted.path("status").asText()).isEqualTo("WAITING");

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.PROCESSED);
        assertThat(lookup(requestId, 73005L).path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @DisplayName("permit 응답을 받지 못하면 작업은 대기에 남고, 작업과 연결되지 않은 token이 남으며 수용이 닫힌다.")
    @Test
    void stopsWhenPermitResponseIsLost() throws Exception {
        String requestId = submitAccepted(campaignId, 73006L);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new CouponPermitStoreUnavailableException(null);
        }).when(permitStore).tryAcquire(anyString(), anyInt());

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.STOPPED);

        assertThat(admission.isOpen()).isFalse();
        assertThat(lookup(requestId, 73006L).path("status").asText()).isEqualTo("WAITING");
        assertThat(listIds(RedisCouponIssueQueue.WAITING_KEY)).containsExactly(requestId);
        assertThat(permitCount()).isEqualTo(1L);
        verify(issueTransaction, never()).issue(any(), any(), any());
    }

    @DisplayName("작업 가져오기 응답을 받지 못하면 DB 발급 없이 permit을 반환하고, 작업은 처리 중으로 남으며 수용이 닫힌다.")
    @Test
    void stopsWhenClaimResponseIsLost() throws Exception {
        String requestId = submitAccepted(campaignId, 73007L);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new CouponIssueQueueUnavailableException(null);
        }).when(queueStore).claim(anyString());

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.STOPPED);

        assertThat(admission.isOpen()).isFalse();
        assertThat(queueStore.findById(requestId)).hasValueSatisfying(stored -> {
            assertThat(stored.getStatus()).isEqualTo(CouponIssueRequestStatus.PROCESSING);
            assertThat(stored.getExecutionToken()).isNotBlank();
        });
        assertThat(listIds(RedisCouponIssueQueue.PROCESSING_KEY)).containsExactly(requestId);
        assertThat(permitCount()).isZero();
        verify(issueTransaction, never()).issue(any(), any(), any());
        assertThat(coupons.findIssuedByCampaignIdAndUserId(campaignId, 73007L)).isEmpty();
    }

    @DisplayName("완료 기록 응답을 받지 못해도 이미 반영된 성공 결과와 쿠폰을 유지하고, 다시 발급하지 않고 수용을 닫는다.")
    @Test
    void stopsWhenRecordResponseIsLost() throws Exception {
        String requestId = submitAccepted(campaignId, 73008L);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new CouponIssueQueueUnavailableException(null);
        }).when(queueStore).recordSucceeded(anyString(), anyString(), anyLong());

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.STOPPED);

        assertThat(admission.isOpen()).isFalse();
        assertThat(lookup(requestId, 73008L).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(listIds(RedisCouponIssueQueue.PROCESSING_KEY)).isEmpty();
        assertThat(coupons.findIssuedByCampaignIdAndUserId(campaignId, 73008L)).isPresent();
        assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isEqualTo(1);
        assertThat(permitCount()).isZero();
    }

    @DisplayName("commit 직전 연결이 끊겨 DB 결과를 모르면 결과 불명으로 남기고 token도 남기며, 재접수는 같은 작업을 돌려준다.")
    @Test
    void keepsUnknownResultAndPermitWhenConnectionIsLostBeforeCommit() throws Exception {
        String requestId = submitAccepted(campaignId, 73009L);
        AtomicBoolean killOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object issued = invocation.callRealMethod();
            if (killOnce.compareAndSet(true, false)) {
                killCurrentTransactionConnection();
            }
            return issued;
        }).when(issueTransaction).issue(anyLong(), anyLong(), any());

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.PROCESSED);

        JsonNode found = lookup(requestId, 73009L);
        assertThat(found.path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(found.path("reasonCode").asText()).isEqualTo("DB_RESULT_UNKNOWN");
        assertThat(listIds(RedisCouponIssueQueue.PROCESSING_KEY)).containsExactly(requestId);
        assertThat(permitCount()).isEqualTo(1L);
        assertThat(admission.isOpen()).isTrue();
        assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isZero();

        JsonNode resubmitted = data(submit(campaignId, 73009L).andExpect(status().isAccepted()));
        assertThat(resubmitted.path("requestId").asText()).isEqualTo(requestId);
        assertThat(resubmitted.path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(redisTemplate.opsForHash().size(RedisCouponIssueQueue.JOBS_KEY)).isEqualTo(1L);
    }

    /**
     * 발급 트랜잭션이 쓰는 연결을 다른 연결에서 끊는다. 이후 commit은 서버에 닿지 못하고 서버는 작업을 rollback한다.
     */
    private void killCurrentTransactionConnection() {
        Number connectionId = (Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult();
        CompletableFuture.runAsync(() -> jdbc.execute("KILL CONNECTION " + connectionId.longValue())).join();
    }

    private String submitAccepted(Long targetCampaignId, Long userId) throws Exception {
        return data(submit(targetCampaignId, userId).andExpect(status().isAccepted())).path("requestId").asText();
    }

    private ResultActions submit(Long targetCampaignId, Long userId) throws Exception {
        return mockMvc.perform(post(SUBMIT_PATH.formatted(targetCampaignId)).header("X-USER-ID", userId));
    }

    private JsonNode lookup(String requestId, Long userId) throws Exception {
        return data(mockMvc.perform(get(LOOKUP_PATH.formatted(requestId)).header("X-USER-ID", userId))
            .andExpect(status().isOk()));
    }

    private JsonNode data(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString()).path("data");
    }

    private Long createCampaign(int quota) {
        Instant now = applicationTime.now().toInstant();
        CouponCampaign campaign = campaigns.save(CouponCampaign.create("coupon-queue-worker-" + System.nanoTime(),
            quota, "5,000원 할인권", now.minusSeconds(60), now.plusSeconds(3600)));
        campaignIds.add(campaign.getId());
        coupons.saveStock(IntStream.range(0, quota).mapToObj(index -> Coupon.create(campaign.getId())).toList());
        entityManager.clear();
        return campaign.getId();
    }

    private long permitCount() {
        Long size = redisTemplate.opsForSet().size(RedisCouponIssuePermits.PERMITS_KEY);
        return size == null ? 0L : size;
    }

    private List<String> listIds(String key) {
        return redisTemplate.opsForList().range(key, 0, -1);
    }

    private void cleanUpRedis() {
        redisTemplate.delete(List.of(RedisCouponIssuePermits.PERMITS_KEY, RedisCouponIssueQueue.WAITING_KEY,
            RedisCouponIssueQueue.PROCESSING_KEY, RedisCouponIssueQueue.JOBS_KEY, RedisCouponIssueQueue.REQUESTS_KEY));
    }
}
