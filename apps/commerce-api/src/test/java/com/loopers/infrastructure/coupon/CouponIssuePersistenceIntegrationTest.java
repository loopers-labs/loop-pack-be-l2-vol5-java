package com.loopers.infrastructure.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.application.coupon.CouponIssuanceTransactions;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.support.ConcurrentRequests;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootTest(properties = "spring.jpa.show-sql=false")
@Import(CouponIssuePersistenceIntegrationTest.FailureInjectionConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponIssuePersistenceIntegrationTest {
    @Autowired private CouponCampaignJpaRepository campaigns;
    @Autowired private CouponJpaRepository couponRows;
    @Autowired private CouponRepository coupons;
    @Autowired private CouponIssuanceTransactions issuance;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FaultInjectingCouponRepository faultInjection;
    private final List<Long> createdCampaigns = new ArrayList<>();

    @Test
    void 다섯_장의_쿠폰에_여덟_명이_경쟁하면_다섯_장만_발급한다() throws Exception {
        long campaignId = createActiveCampaign(5);
        var initialCampaign = campaigns.findById(campaignId).orElseThrow();
        assertThat(initialCampaign.isActive()).isTrue();
        assertThat(initialCampaign.getTotalQuota()).isEqualTo(5);
        assertThat(initialCampaign.getIssuedCount()).isZero();
        assertThat(couponRows.countByCampaignId(campaignId)).isZero();
        List<Callable<CouponIssuanceTransactions.IssueResult>> requests = new ArrayList<>();
        for (long userId = 1; userId <= 8; userId++) {
            long requestUser = userId;
            requests.add(() -> issuance.issue(campaignId, requestUser, new CouponIssuanceTransactions.Completion()));
        }

        var results = ConcurrentRequests.run(requests);

        assertThat(results.successes()).hasSize(5).allMatch(CouponIssuanceTransactions.IssueResult::created);
        assertThat(results.failures()).hasSize(3).allSatisfy(error -> {
            assertThat(error).isInstanceOf(CoreException.class);
            assertThat(((CoreException) error).getErrorType()).isEqualTo(ErrorType.COUPON_SOLD_OUT);
        });
        assertThat(couponRows.countByCampaignId(campaignId)).isEqualTo(5);
        assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isEqualTo(5);
    }

    @Test
    void 같은_사용자의_동시_요청은_하나의_쿠폰과_발급_수를_남긴다() throws Exception {
        long campaignId = createActiveCampaign(5);
        List<Callable<CouponIssuanceTransactions.IssueResult>> requests = List.of(
                () -> issuance.issue(campaignId, 101, new CouponIssuanceTransactions.Completion()),
                () -> issuance.issue(campaignId, 101, new CouponIssuanceTransactions.Completion()));

        var results = ConcurrentRequests.run(requests);

        assertThat(results.failures()).isEmpty();
        assertThat(results.successes()).hasSize(2);
        assertThat(results.successes()).extracting(result -> result.coupon().couponId()).containsOnly(
                coupons.findByCampaignAndUser(campaignId, 101).orElseThrow().couponId());
        assertThat(results.successes().stream().filter(CouponIssuanceTransactions.IssueResult::created)).hasSize(1);
        assertThat(couponRows.countByCampaignId(campaignId)).isEqualTo(1);
        assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isEqualTo(1);
    }

    @Test
    void 품절되고_비활성인_캠페인도_기존_쿠폰은_반환하고_새_발급은_거절한다() {
        long campaignId = createActiveCampaign(1);
        var firstCompletion = new CouponIssuanceTransactions.Completion();
        var first = issuance.issue(campaignId, 101, firstCompletion);
        assertThat(firstCompletion.outcome()).isEqualTo(CouponIssuanceTransactions.Outcome.COMMITTED);
        jdbc.update("update coupon_campaigns set active = false where id = ?", campaignId);

        var repeatCompletion = new CouponIssuanceTransactions.Completion();
        var repeat = issuance.issue(campaignId, 101, repeatCompletion);
        var deniedCompletion = new CouponIssuanceTransactions.Completion();

        assertThat(repeat.created()).isFalse();
        assertThat(repeat.coupon().couponId()).isEqualTo(first.coupon().couponId());
        assertThat(repeatCompletion.outcome()).isEqualTo(CouponIssuanceTransactions.Outcome.COMMITTED);
        assertThatThrownBy(() -> issuance.issue(campaignId, 102, deniedCompletion))
                .isInstanceOfSatisfying(CoreException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.COUPON_CAMPAIGN_INACTIVE));
        assertThat(deniedCompletion.outcome()).isEqualTo(CouponIssuanceTransactions.Outcome.ROLLED_BACK);
        assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isEqualTo(1);
        assertThat(couponRows.countByCampaignId(campaignId)).isEqualTo(1);
    }

    @Test
    void 발급_수_변경을_실제_db에_보낸_뒤_insert가_실패하면_둘_다_롤백한다() {
        long campaignId = createActiveCampaign(5);
        faultInjection.failInsertsFor(campaignId);
        var completion = new CouponIssuanceTransactions.Completion();

        assertThatThrownBy(() -> issuance.issue(campaignId, 303, completion))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(completion.outcome()).isEqualTo(CouponIssuanceTransactions.Outcome.ROLLED_BACK);
        assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isZero();
        assertThat(couponRows.countByCampaignId(campaignId)).isZero();
    }

    @Test
    void 로컬_준비_sql은_기존_데이터를_지우지_않고_100장_캠페인을_추가한다() {
        long existingId = createActiveCampaign(2);
        Path root = Path.of(System.getProperty("user.dir"));
        Path script = root.resolve("docs/week8/seed-campaign.sql");
        if (!Files.exists(script)) {
            script = root.resolve("../../docs/week8/seed-campaign.sql").normalize();
        }
        var resource = new FileSystemResource(script);
        Long seededId = jdbc.execute((ConnectionCallback<Long>) connection -> {
            ScriptUtils.executeSqlScript(connection, resource);
            try (var statement = connection.createStatement(); var result = statement.executeQuery("select last_insert_id()")) {
                result.next();
                return result.getLong(1);
            }
        });
        assertThat(seededId).isNotNull();
        createdCampaigns.add(seededId);

        assertThat(campaigns.findById(seededId).orElseThrow().getTotalQuota()).isEqualTo(100);
        assertThat(campaigns.findById(existingId)).isPresent();
    }

    private long createActiveCampaign(long quota) {
        CouponCampaignJpaEntity row = campaigns.saveAndFlush(
                new CouponCampaignJpaEntity("실습 캠페인", true, quota, "5,000원 할인권"));
        createdCampaigns.add(row.getId());
        return row.getId();
    }

    @AfterEach
    void removeOnlyCreatedCampaigns() {
        faultInjection.failInsertsFor(-1);
        for (long campaignId : createdCampaigns) {
            couponRows.deleteByCampaignId(campaignId);
            campaigns.deleteById(campaignId);
        }
    }

    @TestConfiguration
    static class FailureInjectionConfiguration {
        @Bean
        @Primary
        FaultInjectingCouponRepository faultInjectingCouponRepository(
                CouponRepositoryImpl delegate, JdbcTemplate jdbc) {
            return new FaultInjectingCouponRepository(delegate, jdbc);
        }
    }

    /** 대상 캠페인에서만 실제 MySQL NOT NULL 오류를 발생시키는 테스트 전용 경계다. */
    static class FaultInjectingCouponRepository implements CouponRepository {
        private final CouponRepository delegate;
        private final JdbcTemplate jdbc;
        private final AtomicLong failedCampaign = new AtomicLong(-1);
        @PersistenceContext private EntityManager entityManager;

        FaultInjectingCouponRepository(CouponRepository delegate, JdbcTemplate jdbc) {
            this.delegate = delegate;
            this.jdbc = jdbc;
        }

        void failInsertsFor(long campaignId) {
            failedCampaign.set(campaignId);
        }

        @Override
        public Optional<Coupon> findByCampaignAndUser(long campaignId, long userId) {
            return delegate.findByCampaignAndUser(campaignId, userId);
        }

        @Override
        public List<Coupon> findByUser(long userId) {
            return delegate.findByUser(userId);
        }

        @Override
        public Coupon insert(long campaignId, long userId, String benefitDescription) {
            if (failedCampaign.get() == campaignId) {
                // 증가 UPDATE를 실제 DB에 내보낸 뒤 같은 트랜잭션에서 insert 오류를 만든다.
                entityManager.flush();
                jdbc.update("insert into issued_coupons "
                                + "(campaign_id, user_id, benefit_description, issued_at, created_at, updated_at) "
                                + "values (?, ?, null, current_timestamp(6), current_timestamp(6), current_timestamp(6))",
                        campaignId, userId);
                throw new AssertionError("NOT NULL 위반이 발생하지 않았습니다.");
            }
            return delegate.insert(campaignId, userId, benefitDescription);
        }
    }
}
