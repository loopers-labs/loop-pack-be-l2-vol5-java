package com.loopers.infrastructure;

import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CouponCampaignRepositoryIntegrationTest {

    private static final Instant START_AT = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant END_AT = Instant.parse("2026-12-01T00:00:00Z");
    private static final Instant ISSUE_NOW = Instant.parse("2026-11-11T00:00:00.123456Z");
    private static final long TIMEOUT_SECONDS = 20;

    @Autowired private CouponCampaignRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager entityManager;

    private final List<Long> campaignIds = new ArrayList<>();

    @AfterEach
    void cleanUpOwnCampaigns() {
        for (Long campaignId : campaignIds) {
            jdbc.update("DELETE FROM coupon_campaigns WHERE id = ?", campaignId);
        }
    }

    @DisplayName("캠페인을 저장한 뒤 flush/clear하고 발급 기간과 혜택을 다시 읽는다.")
    @Test
    void persistsAndRestoresCampaign() {
        CouponCampaign campaign = createCampaign(100);

        CouponCampaign stored = reload(campaign.getId());

        assertThat(stored.getName()).isEqualTo("로컬 쿠폰 행사");
        assertThat(stored.getBenefitDescription()).isEqualTo("5,000원 할인권");
        assertThat(stored.getTotalQuota()).isEqualTo(100);
        assertThat(stored.getIssuedCount()).isZero();
        assertThat(stored.getStartAt()).isEqualTo(START_AT);
        assertThat(stored.getEndAt()).isEqualTo(END_AT);
        assertThat(stored.getTerminatedAt()).isNull();
    }

    @DisplayName("기존 DB용 캠페인 생성 SQL을 재적용해도 기존 데이터와 시각 정밀도를 보존한다.")
    @Test
    void preparesSchemaWithoutResettingExistingCampaign() throws Exception {
        String migration = Files.readString(Path.of(System.getProperty("coupon.migration")))
            .replace("loopers.coupon_campaigns", "loopers.review_coupon_campaigns")
            .replace("loopers.coupons", "loopers.review_coupons")
            .replace("table_name = 'coupons'", "table_name = 'review_coupons'")
            .replace("fk_coupons_", "fk_review_coupons_");
        ByteArrayResource script = new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8));

        try {
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, script);
            }
            jdbc.update("""
                INSERT INTO review_coupon_campaigns
                    (id, created_at, updated_at, name, total_quota, issued_count, benefit_description, start_at, end_at)
                VALUES (1, '2026-11-01 00:00:00', '2026-11-01 00:00:00', '기존 캠페인', 100, 0,
                    '5,000원 할인권', '2026-11-01 00:00:00', '2026-12-01 00:00:00')
                """);
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, script);
            }

            assertThat(jdbc.queryForList("SELECT name FROM review_coupon_campaigns", String.class))
                .containsExactly("기존 캠페인");
            assertThat(jdbc.queryForObject("SELECT issued_count FROM review_coupon_campaigns WHERE id = 1", Long.class))
                .isZero();
            assertThat(jdbc.queryForObject("""
                SELECT DATETIME_PRECISION FROM information_schema.columns
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_coupon_campaigns' AND COLUMN_NAME = 'terminated_at'
                """, Integer.class)).isEqualTo(6);
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS review_coupons");
            jdbc.execute("DROP TABLE IF EXISTS review_coupon_campaigns");
        }
    }

    @DisplayName("첫 발급은 조기 종료하지 않고 마지막 발급만 같은 issueNow로 종료한다.")
    @Test
    void matchesDomainTransitionForFirstAndLastIssues() {
        CouponCampaign campaign = createCampaign(2);
        CouponCampaign expected = CouponCampaign.create("로컬 쿠폰 행사", 2, "5,000원 할인권", START_AT, END_AT);

        assertThat(transact(() -> repository.incrementIssuedCountIfIssuable(campaign.getId(), ISSUE_NOW))).isEqualTo(1);
        expected.recordIssue(ISSUE_NOW);
        assertSameIssuanceState(reload(campaign.getId()), expected);

        Instant lastIssueAt = ISSUE_NOW.plusSeconds(1);
        assertThat(transact(() -> repository.incrementIssuedCountIfIssuable(campaign.getId(), lastIssueAt))).isEqualTo(1);
        expected.recordIssue(lastIssueAt);
        assertSameIssuanceState(reload(campaign.getId()), expected);

        assertThat(transact(() -> repository.incrementIssuedCountIfIssuable(campaign.getId(), lastIssueAt.plusSeconds(1))))
            .isZero();
        assertSameIssuanceState(reload(campaign.getId()), expected);
    }

    @DisplayName("총량 한 장의 첫 발급이 마지막 발급이며 마이크로초 정밀도로 종료 시각을 저장한다.")
    @Test
    void terminatesSingleCouponCampaign() {
        CouponCampaign campaign = createCampaign(1);

        assertThat(transact(() -> repository.incrementIssuedCountIfIssuable(campaign.getId(), ISSUE_NOW))).isEqualTo(1);

        CouponCampaign stored = reload(campaign.getId());
        assertThat(stored.getIssuedCount()).isEqualTo(1);
        assertThat(stored.getTerminatedAt()).isEqualTo(ISSUE_NOW);
    }

    @DisplayName("발급 시작은 포함하고 종료는 제외하며 거절 때 수량과 종료 시각을 보존한다.")
    @Test
    void checksPeriodInConditionalUpdate() {
        CouponCampaign campaign = createCampaign(2);

        assertThat(transact(() -> repository.incrementIssuedCountIfIssuable(campaign.getId(), START_AT.minusSeconds(1))))
            .isZero();
        assertThat(transact(() -> repository.incrementIssuedCountIfIssuable(campaign.getId(), END_AT))).isZero();
        assertThat(reload(campaign.getId()).getIssuedCount()).isZero();
        assertThat(reload(campaign.getId()).getTerminatedAt()).isNull();
        assertThat(transact(() -> repository.incrementIssuedCountIfIssuable(campaign.getId(), START_AT))).isEqualTo(1);
    }

    @DisplayName("조건부 UPDATE 뒤 실패하면 마지막 발급 수와 종료 시각이 함께 rollback된다.")
    @Test
    void rollsBackCounterAndTerminationTogether() {
        CouponCampaign campaign = createCampaign(1);

        assertThatThrownBy(() -> transact(() -> {
            assertThat(repository.incrementIssuedCountIfIssuable(campaign.getId(), ISSUE_NOW)).isEqualTo(1);
            assertThat(repository.findById(campaign.getId()).orElseThrow().getTerminatedAt()).isEqualTo(ISSUE_NOW);
            throw new IllegalStateException("쿠폰 배정 실패 재현");
        })).isInstanceOf(IllegalStateException.class).hasMessage("쿠폰 배정 실패 재현");

        CouponCampaign stored = reload(campaign.getId());
        assertThat(stored.getIssuedCount()).isZero();
        assertThat(stored.getTerminatedAt()).isNull();
        assertThat(transact(() -> repository.incrementIssuedCountIfIssuable(campaign.getId(), ISSUE_NOW))).isEqualTo(1);
    }

    @DisplayName("없는 캠페인의 조건부 갱신은 0행이다.")
    @Test
    void returnsZeroWhenCampaignDoesNotExist() {
        assertThat(transact(() -> repository.incrementIssuedCountIfIssuable(-1L, ISSUE_NOW))).isZero();
    }

    @DisplayName("일반 저장으로 기존 캠페인이나 메모리에서 증가시킨 발급 수를 덮어쓰지 않는다.")
    @Test
    void rejectsSavingIssuedOrExistingCampaignThroughPreparationPath() {
        CouponCampaign existing = createCampaign(2);
        existing.recordIssue(ISSUE_NOW);
        CouponCampaign unsaved = CouponCampaign.create("새 캠페인", 1, "5,000원 할인권", START_AT, END_AT);
        unsaved.recordIssue(ISSUE_NOW);

        assertThatThrownBy(() -> transact(() -> repository.save(existing))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transact(() -> repository.save(unsaved))).isInstanceOf(IllegalArgumentException.class);
        assertThat(reload(existing.getId()).getIssuedCount()).isZero();
        assertThat(reload(existing.getId()).getTerminatedAt()).isNull();
    }

    @DisplayName("동일한 발급 수 0을 읽은 8개 트랜잭션도 총량 5개만 증가시키고 조기 종료한다.")
    @Test
    void updatesOnlyFiveOfEightConcurrentTransactions() throws Exception {
        CouponCampaign campaign = createCampaign(5);
        CountDownLatch allReads = new CountDownLatch(8);
        CountDownLatch allowUpdates = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(8);

        try {
            List<Future<Integer>> attempts = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                attempts.add(workers.submit(() -> transact(() -> {
                    assertThat(repository.findById(campaign.getId()).orElseThrow().getIssuedCount()).isZero();
                    allReads.countDown();
                    await(allowUpdates);
                    return repository.incrementIssuedCountIfIssuable(campaign.getId(), ISSUE_NOW);
                })));
            }
            assertThat(allReads.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            allowUpdates.countDown();

            List<Integer> results = new ArrayList<>();
            for (Future<Integer> attempt : attempts) {
                results.add(attempt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            assertThat(results).containsOnly(0, 1);
            assertThat(results.stream().filter(result -> result == 1).count()).isEqualTo(5);
            assertThat(results.stream().filter(result -> result == 0).count()).isEqualTo(3);
            assertThat(reload(campaign.getId()).getIssuedCount()).isEqualTo(5);
            assertThat(reload(campaign.getId()).getTerminatedAt()).isEqualTo(ISSUE_NOW);
        } finally {
            allowUpdates.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private CouponCampaign createCampaign(long totalQuota) {
        CouponCampaign campaign = transact(() -> repository.save(
            CouponCampaign.create("로컬 쿠폰 행사", totalQuota, "5,000원 할인권", START_AT, END_AT)
        ));
        campaignIds.add(campaign.getId());
        return campaign;
    }

    private CouponCampaign reload(Long campaignId) {
        return transact(() -> repository.findById(campaignId).orElseThrow());
    }

    private <T> T transact(Supplier<T> action) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> {
            T result = action.get();
            entityManager.flush();
            entityManager.clear();
            return result;
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("캠페인 카운터 갱신 대기 시간이 초과됐습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("캠페인 카운터 갱신 대기가 중단됐습니다.", exception);
        }
    }

    private static void assertSameIssuanceState(CouponCampaign actual, CouponCampaign expected) {
        assertThat(actual.getIssuedCount()).isEqualTo(expected.getIssuedCount());
        assertThat(actual.getTerminatedAt()).isEqualTo(expected.getTerminatedAt());
    }
}
