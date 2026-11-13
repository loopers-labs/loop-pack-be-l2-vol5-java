package com.loopers.infrastructure;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.DuplicateCouponIssueException;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CouponRepositoryIntegrationTest {

    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-12-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00.123456Z");
    private static final long TIMEOUT_SECONDS = 20;

    @Autowired private CouponRepository coupons;
    @Autowired private CouponCampaignRepository campaigns;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager entityManager;

    private final List<Long> campaignIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void applyCouponSchemaMigrationToTestDatabase() throws Exception {
        ByteArrayResource script = new ByteArrayResource(
            Files.readAllBytes(Path.of(System.getProperty("coupon.migration"))));
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, script);
        }
    }

    @AfterEach
    void cleanUpOwnData() {
        for (Long campaignId : campaignIds) {
            jdbc.update("DELETE FROM coupons WHERE campaign_id = ?", campaignId);
            jdbc.update("DELETE FROM coupon_campaigns WHERE id = ?", campaignId);
        }
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @DisplayName("발급한 쿠폰을 insert하면 ID가 생기고, flush·clear 뒤에도 같은 캠페인·사용자·발급 시각으로 읽힌다.")
    @Test
    void insertsIssuedCouponAndReadsItBack() {
        Long campaignId = campaign(2);
        long userId = createUser();

        Coupon inserted = transact(() -> coupons.insert(Coupon.create(campaignId, userId, NOW)));

        assertThat(inserted.getId()).isNotNull();
        Coupon stored = transact(() -> coupons.findById(inserted.getId()).orElseThrow());
        assertThat(stored.getCampaignId()).isEqualTo(campaignId);
        assertThat(stored.getUserId()).isEqualTo(userId);
        assertThat(stored.getIssuedAt()).isEqualTo(NOW);
        assertThat(transact(() -> coupons.countIssuedByCampaignId(campaignId)).longValue()).isEqualTo(1);
        assertThat(jdbc.queryForList("""
            SELECT COLUMN_NAME FROM information_schema.statistics
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'coupons' AND INDEX_NAME = 'uk_coupon_campaign_user'
            ORDER BY SEQ_IN_INDEX
            """, String.class)).containsExactly("campaign_id", "user_id");
        assertThat(jdbc.queryForList("""
            SELECT COLUMN_NAME FROM information_schema.columns
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'coupons'
              AND COLUMN_NAME IN ('user_id', 'issued_at') AND IS_NULLABLE = 'NO'
            """, String.class)).containsExactlyInAnyOrder("user_id", "issued_at");
    }

    @DisplayName("예전 모델로 만든 DB에 쿠폰 스키마 SQL을 적용하면 발급 기록은 남고 미발급 행은 지워지며, 다시 적용해도 같다.")
    @Test
    void migratesPreparedRowModelWithoutLosingIssuedCoupons() throws Exception {
        Long campaignId = campaign(2);
        long userId = createUser();
        String migration = Files.readString(Path.of(System.getProperty("coupon.migration")))
            .replace("loopers.coupons", "loopers.review_coupons")
            .replace("table_name = 'coupons'", "table_name = 'review_coupons'")
            .replace("fk_coupons_", "fk_review_coupons_");
        ByteArrayResource script = new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8));
        try {
            // 예전 모델: 미리 만든 쿠폰 행은 사용자와 발급 시각이 비어 있고, 사용자 FK가 남아 있을 수 있다.
            jdbc.execute("""
                CREATE TABLE review_coupons (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    created_at DATETIME(6) NOT NULL,
                    updated_at DATETIME(6) NOT NULL,
                    deleted_at DATETIME(6) DEFAULT NULL,
                    campaign_id BIGINT NOT NULL,
                    user_id BIGINT DEFAULT NULL,
                    issued_at DATETIME(6) DEFAULT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_review_coupon_campaign_user (campaign_id, user_id),
                    CONSTRAINT fk_review_coupons_campaign FOREIGN KEY (campaign_id) REFERENCES coupon_campaigns (id),
                    CONSTRAINT fk_review_coupons_user FOREIGN KEY (user_id) REFERENCES users (id)
                )
                """);
            jdbc.update("""
                INSERT INTO review_coupons (id, created_at, updated_at, campaign_id, user_id, issued_at)
                VALUES (1, '2026-11-11 00:00:00', '2026-11-11 00:00:00', ?, ?, '2026-11-11 00:00:00.123456'),
                       (2, '2026-11-11 00:00:00', '2026-11-11 00:00:00', ?, NULL, NULL)
                """, campaignId, userId, campaignId);

            executeWithDifferentDefaultSchema(script);
            executeWithDifferentDefaultSchema(script);

            assertThat(jdbc.queryForList("SELECT id FROM review_coupons", Long.class)).containsExactly(1L);
            assertThat(jdbc.queryForObject("SELECT user_id FROM review_coupons WHERE id = 1", Long.class)).isEqualTo(userId);
            assertThat(jdbc.queryForObject("SELECT issued_at FROM review_coupons WHERE id = 1", java.sql.Timestamp.class)
                .getNanos()).isEqualTo(123456000);
            assertThat(jdbc.queryForList("""
                SELECT COLUMN_NAME FROM information_schema.columns
                WHERE TABLE_SCHEMA = 'loopers' AND TABLE_NAME = 'review_coupons'
                  AND COLUMN_NAME IN ('user_id', 'issued_at') AND IS_NULLABLE = 'NO'
                """, String.class)).containsExactlyInAnyOrder("user_id", "issued_at");
            assertThat(jdbc.queryForList("""
                SELECT REFERENCED_TABLE_NAME FROM information_schema.key_column_usage
                WHERE TABLE_SCHEMA = 'loopers' AND TABLE_NAME = 'review_coupons'
                  AND COLUMN_NAME = 'user_id' AND REFERENCED_TABLE_NAME IS NOT NULL
                """, String.class)).isEmpty();
            assertThat(jdbc.queryForList("""
                SELECT REFERENCED_TABLE_NAME FROM information_schema.key_column_usage
                WHERE TABLE_SCHEMA = 'loopers' AND TABLE_NAME = 'review_coupons'
                  AND COLUMN_NAME = 'campaign_id' AND REFERENCED_TABLE_NAME IS NOT NULL
                """, String.class)).containsExactly("coupon_campaigns");
            assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO review_coupons (created_at, updated_at, campaign_id, user_id, issued_at)
                VALUES (NOW(6), NOW(6), ?, ?, NOW(6))
                """, campaignId, userId)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS review_coupons");
        }
    }

    @DisplayName("쿠폰 스키마 SQL을 테이블이 없는 DB에 적용하면 NOT NULL 컬럼, 같은 사용자 유일 키, 캠페인 FK를 가진 테이블을 만든다.")
    @Test
    void createsCouponTableWhenMissing() throws Exception {
        String migration = Files.readString(Path.of(System.getProperty("coupon.migration")))
            .replace("loopers.coupons", "loopers.fresh_coupons")
            .replace("table_name = 'coupons'", "table_name = 'fresh_coupons'")
            .replace("fk_coupons_", "fk_fresh_coupons_");
        ByteArrayResource script = new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8));
        try {
            jdbc.execute("DROP TABLE IF EXISTS fresh_coupons");

            executeWithDifferentDefaultSchema(script);

            assertThat(jdbc.queryForList("""
                SELECT COLUMN_NAME FROM information_schema.columns
                WHERE TABLE_SCHEMA = 'loopers' AND TABLE_NAME = 'fresh_coupons'
                  AND COLUMN_NAME IN ('campaign_id', 'user_id', 'issued_at') AND IS_NULLABLE = 'NO'
                """, String.class)).containsExactlyInAnyOrder("campaign_id", "user_id", "issued_at");
            assertThat(jdbc.queryForList("""
                SELECT COLUMN_NAME FROM information_schema.statistics
                WHERE TABLE_SCHEMA = 'loopers' AND TABLE_NAME = 'fresh_coupons' AND INDEX_NAME = 'uk_coupon_campaign_user'
                ORDER BY SEQ_IN_INDEX
                """, String.class)).containsExactly("campaign_id", "user_id");
            assertThat(jdbc.queryForList("""
                SELECT REFERENCED_TABLE_NAME FROM information_schema.key_column_usage
                WHERE TABLE_SCHEMA = 'loopers' AND TABLE_NAME = 'fresh_coupons'
                  AND COLUMN_NAME = 'campaign_id' AND REFERENCED_TABLE_NAME IS NOT NULL
                """, String.class)).containsExactly("coupon_campaigns");
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS fresh_coupons");
        }
    }

    @DisplayName("중복이 아닌 제약 오류는 중복 예외로 바꾸지 않고, 이미 저장된 쿠폰은 다시 insert하지 않는다.")
    @Test
    void distinguishesNonDuplicateFailuresAndRejectsStoredCoupon() {
        long userId = createUser();
        Long missingCampaignId = Long.MAX_VALUE;

        assertThatThrownBy(() -> transact(() -> coupons.insert(Coupon.create(missingCampaignId, userId, NOW))))
            .isInstanceOf(DataIntegrityViolationException.class)
            .isNotInstanceOf(DuplicateCouponIssueException.class);
        assertThatThrownBy(() -> transact(() -> coupons.insert(Coupon.reconstitute(1L, campaign(1), userId, NOW))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("동일 사용자 제약 충돌은 별도로 구분하고 마지막 카운터와 조기 종료 기록도 rollback한다.")
    @Test
    void rollsBackCounterAndTerminationOnDuplicateUserConstraint() {
        Long campaignId = campaign(2);
        long userId = createUser();
        Coupon first = tryIssue(campaignId, userId, NOW).orElseThrow();

        assertThatThrownBy(() -> tryIssue(campaignId, userId, NOW.plusSeconds(1)))
            .isInstanceOf(DuplicateCouponIssueException.class);

        CouponCampaign campaign = transact(() -> campaigns.findById(campaignId).orElseThrow());
        assertThat(campaign.getIssuedCount()).isEqualTo(1);
        assertThat(campaign.getTerminatedAt()).isNull();
        assertThat(transact(() -> coupons.countIssuedByCampaignId(campaignId)).longValue()).isEqualTo(1);
        assertThat(transact(() -> coupons.findIssuedByCampaignIdAndUserId(campaignId, userId)))
            .get().extracting(Coupon::getId).isEqualTo(first.getId());
    }

    @DisplayName("로컬 식별자에 대응하는 users 행이 없어도 발급할 수 있다.")
    @Test
    void allowsLocalIdentifierWithoutUserRow() {
        Long campaignId = campaign(1);
        long localIdentifier = Long.MAX_VALUE;

        Coupon issued = tryIssue(campaignId, localIdentifier, NOW).orElseThrow();

        CouponCampaign campaign = transact(() -> campaigns.findById(campaignId).orElseThrow());
        Coupon coupon = transact(() -> coupons.findById(issued.getId()).orElseThrow());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Long.class, localIdentifier)).isZero();
        assertThat(campaign.getIssuedCount()).isEqualTo(1);
        assertThat(campaign.getTerminatedAt()).isEqualTo(NOW);
        assertThat(coupon.getUserId()).isEqualTo(localIdentifier);
        assertThat(coupon.getIssuedAt()).isEqualTo(NOW);
    }

    @DisplayName("보유 목록은 해당 사용자만 발급 시각·쿠폰 ID 내림차순으로 반환한다.")
    @Test
    void listsOnlyOwnerCouponsInStableOrderAcrossCampaigns() {
        Long first = campaign(2);
        Long second = campaign(1);
        Long third = campaign(1);
        long owner = createUser();
        long other = createUser();
        Coupon old = tryIssue(first, owner, NOW).orElseThrow();
        Coupon tiedEarlierId = tryIssue(second, owner, NOW.plusSeconds(1)).orElseThrow();
        Coupon tiedLaterId = tryIssue(third, owner, NOW.plusSeconds(1)).orElseThrow();
        Coupon others = tryIssue(first, other, NOW.plusSeconds(2)).orElseThrow();

        List<Coupon> owned = transact(() -> coupons.findAllIssuedByUserId(owner));

        assertThat(owned).extracting(Coupon::getId)
            .containsExactly(tiedLaterId.getId(), tiedEarlierId.getId(), old.getId());
        assertThat(owned).allSatisfy(coupon -> assertThat(coupon.getUserId()).isEqualTo(owner));
        assertThat(transact(() -> coupons.findAllIssuedByUserId(other)))
            .extracting(Coupon::getId).containsExactly(others.getId());
    }

    @DisplayName("캠페인 쿠폰 삭제는 그 캠페인의 쿠폰만 지운다.")
    @Test
    void deletesOnlyTargetCampaignCoupons() {
        Long target = campaign(2);
        Long other = campaign(2);
        long userId = createUser();
        tryIssue(target, userId, NOW).orElseThrow();
        Coupon kept = tryIssue(other, userId, NOW).orElseThrow();

        int deleted = transact(() -> coupons.deleteByCampaignId(target));

        assertThat(deleted).isEqualTo(1);
        assertThat(transact(() -> coupons.countIssuedByCampaignId(target)).longValue()).isZero();
        assertThat(transact(() -> coupons.findById(kept.getId()))).isPresent();
    }

    @DisplayName("서로 다른 사용자 8명이 총량 5개를 경쟁하면 쿠폰 5행만 insert된다.")
    @Test
    void insertsOnlyFiveRowsForEightConcurrentUsers() throws Exception {
        Long campaignId = campaign(5);
        List<Long> competitors = IntStream.range(0, 8).mapToObj(index -> createUser()).toList();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<Optional<Coupon>>> attempts = competitors.stream().map(userId -> workers.submit(() -> {
                ready.countDown();
                if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new AssertionError("쿠폰 발급 시작 대기 시간 초과");
                }
                return tryIssue(campaignId, userId, NOW);
            })).toList();
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Coupon> issued = new ArrayList<>();
            for (Future<Optional<Coupon>> attempt : attempts) {
                attempt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).ifPresent(issued::add);
            }

            assertThat(issued).hasSize(5);
            assertThat(issued).extracting(Coupon::getId).doesNotHaveDuplicates();
            assertThat(issued).extracting(Coupon::getUserId).doesNotHaveDuplicates();
            assertThat(transact(() -> coupons.countIssuedByCampaignId(campaignId)).longValue()).isEqualTo(5);
            CouponCampaign campaign = transact(() -> campaigns.findById(campaignId).orElseThrow());
            assertThat(campaign.getIssuedCount()).isEqualTo(5);
            assertThat(campaign.getTerminatedAt()).isEqualTo(NOW);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Long campaign(int quota) {
        CouponCampaign campaign = transact(() -> campaigns.save(
            CouponCampaign.create("쿠폰 저장 테스트", quota, "5,000원 할인권", START, END)
        ));
        campaignIds.add(campaign.getId());
        return campaign.getId();
    }

    private void executeWithDifferentDefaultSchema(ByteArrayResource script) throws Exception {
        String configuredUrl = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        String urlWithoutDatabase = configuredUrl.substring(0, configuredUrl.lastIndexOf('/'));
        try (Connection connection = DriverManager.getConnection(urlWithoutDatabase,
            System.getProperty("datasource.mysql-jpa.main.username"),
            System.getProperty("datasource.mysql-jpa.main.password"))) {
            // mysql -uroot처럼 기본 DB를 지정하지 않은 실행을 모사해도 loopers FK를 찾아야 한다.
            assertThat(connection.getCatalog()).isBlank();
            ScriptUtils.executeSqlScript(connection, script);
        }
    }

    private long createUser() {
        long id = transact(() -> users.save(User.create())).getId();
        userIds.add(id);
        return id;
    }

    /**
     * 발급 트랜잭션과 같은 순서로 캠페인 카운터를 조건부 갱신한 뒤 쿠폰을 insert한다. 품절이면 비어 있다.
     */
    private Optional<Coupon> tryIssue(Long campaignId, Long userId, Instant issueNow) {
        return transact(() -> {
            if (campaigns.incrementIssuedCountIfIssuable(campaignId, issueNow) == 0) {
                return Optional.empty();
            }
            return Optional.of(coupons.insert(Coupon.create(campaignId, userId, issueNow)));
        });
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

}
