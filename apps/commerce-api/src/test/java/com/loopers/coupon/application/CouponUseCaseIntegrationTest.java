package com.loopers.coupon.application;

import com.loopers.coupon.domain.CouponCampaign;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.support.fixture.CouponTestFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
class CouponUseCaseIntegrationTest {

    @Autowired
    private CouponUseCase useCase;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private CommerceFixture commerce;
    private CouponTestFixture coupons;

    @BeforeEach
    void setUp() {
        commerce = new CommerceFixture(entityManager, transactionManager);
        coupons = new CouponTestFixture(entityManager, transactionManager);
    }

    @AfterEach
    void tearDown() {
        commerce.truncateRemainingTables();
    }

    @DisplayName("[INV-COUPON-53] 한 사용자와 한 캠페인의 조합에 발급된 쿠폰은 최대 한 장이다.")
    @Nested
    class OneCouponPerUserAndCampaign {

        @DisplayName("[동시성] 같은 사용자·캠페인에 동시에 요청해도 DB에는 쿠폰이 한 장만 저장된다.")
        @Test
        void storesAtMostOneCouponForConcurrentDuplicateRequests() throws Exception {
            // arrange
            User user = commerce.user();
            CouponCampaign campaign = coupons.campaign("출시 캠페인", "5,000원 할인권", true, 10);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);

            try {
                Future<?> first = executor.submit(() -> issueAfterStart(ready, start, campaign, user));
                Future<?> second = executor.submit(() -> issueAfterStart(ready, start, campaign, user));
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();

                // act
                start.countDown();

                // assert
                first.get(30, TimeUnit.SECONDS);
                second.get(30, TimeUnit.SECONDS);
                assertThat(coupons.couponCount(campaign.getId())).isEqualTo(1L);
                assertThat(coupons.issuedCount(campaign.getId())).isEqualTo(1L);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private void issueAfterStart(
        CountDownLatch ready,
        CountDownLatch start,
        CouponCampaign campaign,
        User user
    ) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("issuance start was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("issuance request was interrupted", exception);
        }
        useCase.issue(campaign.getId(), user.getId());
    }
}
