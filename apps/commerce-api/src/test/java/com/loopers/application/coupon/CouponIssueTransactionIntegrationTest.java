package com.loopers.application.coupon;

import com.loopers.application.time.ApplicationTime;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

@SpringBootTest
class CouponIssueTransactionIntegrationTest {

    private static final int TIMEOUT_SECONDS = 20;

    @Autowired private CouponIssueTransaction transaction;
    @Autowired private CouponCampaignRepository campaigns;
    @Autowired private ApplicationTime applicationTime;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager entityManager;
    @MockitoSpyBean private CouponRepository coupons;

    private final List<Long> campaignIds = new ArrayList<>();

    @AfterEach
    void cleanUpOwnCampaigns() {
        campaignIds.forEach(campaignId -> {
            jdbc.update("DELETE FROM coupons WHERE campaign_id = ?", campaignId);
            jdbc.update("DELETE FROM coupon_campaigns WHERE id = ?", campaignId);
        });
    }

    @DisplayName("총량 5의 마지막 수량 경합에서 다섯 건만 commit하고 나머지는 품절로 끝난다.")
    @Test
    void concurrentRequestsCannotExceedCampaignQuota() throws Exception {
        CouponCampaign campaign = prepareCampaign(5);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        List<Future<Attempt>> attempts = new ArrayList<>();
        try {
            for (long userId = 82000L; userId < 82008L; userId++) {
                long requestUserId = userId;
                attempts.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 발급 barrier가 시작되지 않았습니다.");
                    }
                    try {
                        return new Attempt(transaction.issue(campaign.getId(), requestUserId), null);
                    } catch (CoreException exception) {
                        return new Attempt(null, exception.getErrorType());
                    }
                }));
            }
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Attempt> results = attempts.stream().map(this::await).toList();
            assertThat(results.stream().filter(result -> result.info() != null)).hasSize(5);
            assertThat(results.stream().filter(result -> result.error() == ErrorType.CONFLICT)).hasSize(3);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        entityManager.clear();
        CouponCampaign stored = campaigns.findById(campaign.getId()).orElseThrow();
        assertThat(stored.getIssuedCount()).isEqualTo(5);
        assertThat(stored.getTerminatedAt()).isNotNull();
        assertThat(coupons.countIssuedByCampaignId(campaign.getId())).isEqualTo(5);
        assertThat(coupons.countByCampaignId(campaign.getId())).isEqualTo(5);
    }

    @DisplayName("조건부 쿠폰 배정이 실패하면 앞선 카운터 갱신과 조기 종료 기록도 rollback된다.")
    @Test
    void rollsBackCounterWhenCouponAssignmentDoesNotUpdateOneRow() {
        CouponCampaign campaign = prepareCampaign(1);
        Long couponId = coupons.findFirstUnissuedId(campaign.getId()).orElseThrow();
        doReturn(0).when(coupons).assignIfUnissued(eq(couponId), eq(campaign.getId()), eq(83000L), any(Instant.class));

        assertThatThrownBy(() -> transaction.issue(campaign.getId(), 83000L))
            .isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR));

        entityManager.clear();
        CouponCampaign stored = campaigns.findById(campaign.getId()).orElseThrow();
        assertThat(stored.getIssuedCount()).isZero();
        assertThat(stored.getTerminatedAt()).isNull();
        assertThat(coupons.countIssuedByCampaignId(campaign.getId())).isZero();
    }

    private CouponCampaign prepareCampaign(long quota) {
        Instant now = applicationTime.now().toInstant();
        CouponCampaign campaign = campaigns.save(CouponCampaign.create("coupon-transaction-" + System.nanoTime(),
            quota, "테스트 혜택", now.minusSeconds(60), now.plusSeconds(3600)));
        campaignIds.add(campaign.getId());
        coupons.saveStock(IntStream.range(0, (int) quota)
            .mapToObj(index -> Coupon.create(campaign.getId())).toList());
        return campaign;
    }

    private Attempt await(Future<Attempt> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("발급 트랜잭션이 제한 시간 안에 끝나지 않았습니다.", exception);
        }
    }

    private record Attempt(CouponIssueInfo info, ErrorType error) {}
}
