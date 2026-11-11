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

    @DisplayName("미발급 쿠폰 100행은 NULL 소유자가 충돌하지 않고 저장되며 가장 작은 ID부터 후보가 된다.")
    @Test
    void preparesOneHundredUnissuedRows() {
        Stock stock = prepare(100);

        assertThat(transact(() -> coupons.countByCampaignId(stock.campaignId())).longValue()).isEqualTo(100);
        assertThat(transact(() -> coupons.countIssuedByCampaignId(stock.campaignId())).longValue()).isZero();
        assertThat(transact(() -> coupons.findFirstUnissuedId(stock.campaignId())))
            .contains(stock.couponIds().getFirst());
        for (Long couponId : stock.couponIds()) {
            Coupon coupon = transact(() -> coupons.findById(couponId).orElseThrow());
            assertThat(coupon.getCampaignId()).isEqualTo(stock.campaignId());
            assertThat(coupon.isIssued()).isFalse();
            assertThat(coupon.getIssuedAt()).isNull();
        }
        assertThat(jdbc.queryForList("""
            SELECT COLUMN_NAME FROM information_schema.statistics
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'coupons' AND INDEX_NAME = 'uk_coupon_campaign_user'
            ORDER BY SEQ_IN_INDEX
            """, String.class)).containsExactly("campaign_id", "user_id");
    }

    @DisplayName("쿠폰 한 행을 조건부 배정한 뒤에는 다른 사용자가 같은 행을 다시 배정할 수 없다.")
    @Test
    void assignsOnceAndPreservesOwnerAndTime() {
        Stock stock = prepare(2);
        long owner = createUser();
        long other = createUser();

        Coupon issued = tryAssign(stock.campaignId(), owner, NOW).orElseThrow();
        int repeated = transact(() -> coupons.assignIfUnissued(issued.getId(), stock.campaignId(), other, NOW.plusSeconds(1)));

        assertThat(repeated).isZero();
        Coupon stored = transact(() -> coupons.findById(issued.getId()).orElseThrow());
        assertThat(stored.getUserId()).isEqualTo(owner);
        assertThat(stored.getIssuedAt()).isEqualTo(NOW);
        assertThat(transact(() -> coupons.countByCampaignId(stock.campaignId())).longValue()).isEqualTo(2);
        assertThat(transact(() -> coupons.findIssuedByCampaignIdAndUserId(stock.campaignId(), owner)))
            .get().extracting(Coupon::getId).isEqualTo(issued.getId());
    }

    @DisplayName("기존 DB용 쿠폰 스키마 SQL을 재적용해도 재고와 발급 결과를 보존한다.")
    @Test
    void preparesSchemaWithoutResettingStock() throws Exception {
        Stock stock = prepare(1);
        long userId = createUser();
        String migration = Files.readString(Path.of(System.getProperty("coupon.migration")))
            .replace("loopers.coupons", "loopers.review_coupons")
            .replace("table_name = 'coupons'", "table_name = 'review_coupons'")
            .replace("fk_coupons_", "fk_review_coupons_");
        ByteArrayResource script = new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8));
        try {
            executeWithDifferentDefaultSchema(script);
            jdbc.execute("""
                ALTER TABLE review_coupons ADD CONSTRAINT fk_review_coupons_user
                FOREIGN KEY (user_id) REFERENCES users (id)
                """);
            jdbc.update("""
                INSERT INTO review_coupons (id, created_at, updated_at, campaign_id, user_id, issued_at)
                VALUES (1, '2026-11-11 00:00:00', '2026-11-11 00:00:00', ?, ?, '2026-11-11 00:00:00.123456')
                """, stock.campaignId(), userId);
            executeWithDifferentDefaultSchema(script);

            assertThat(jdbc.queryForList("SELECT user_id FROM review_coupons", Long.class)).containsExactly(userId);
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
            assertThat(jdbc.queryForObject("SELECT issued_at FROM review_coupons WHERE id = 1", java.sql.Timestamp.class)
                .getNanos()).isEqualTo(123456000);
            assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO review_coupons (created_at, updated_at, campaign_id, user_id, issued_at)
                VALUES (NOW(6), NOW(6), ?, ?, NOW(6))
                """, stock.campaignId(), userId)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS review_coupons");
        }
    }

    @DisplayName("동일 사용자 제약 충돌은 별도로 구분하고 마지막 카운터와 조기 종료 기록도 rollback한다.")
    @Test
    void rollsBackCounterAndTerminationOnDuplicateUserConstraint() {
        Stock stock = prepare(2);
        long userId = createUser();
        Coupon first = tryAssign(stock.campaignId(), userId, NOW).orElseThrow();

        assertThatThrownBy(() -> tryAssign(stock.campaignId(), userId, NOW.plusSeconds(1)))
            .isInstanceOf(DuplicateCouponIssueException.class);

        CouponCampaign campaign = transact(() -> campaigns.findById(stock.campaignId()).orElseThrow());
        assertThat(campaign.getIssuedCount()).isEqualTo(1);
        assertThat(campaign.getTerminatedAt()).isNull();
        assertThat(transact(() -> coupons.countIssuedByCampaignId(stock.campaignId())).longValue()).isEqualTo(1);
        assertThat(transact(() -> coupons.findIssuedByCampaignIdAndUserId(stock.campaignId(), userId)))
            .get().extracting(Coupon::getId).isEqualTo(first.getId());
        assertThat(transact(() -> coupons.findFirstUnissuedId(stock.campaignId()))).isPresent();
    }

    @DisplayName("로컬 식별자에 대응하는 users 행이 없어도 발급할 수 있다.")
    @Test
    void allowsLocalIdentifierWithoutUserRow() {
        Stock stock = prepare(1);
        long localIdentifier = Long.MAX_VALUE;

        Coupon issued = tryAssign(stock.campaignId(), localIdentifier, NOW).orElseThrow();

        CouponCampaign campaign = transact(() -> campaigns.findById(stock.campaignId()).orElseThrow());
        Coupon coupon = transact(() -> coupons.findById(stock.couponIds().getFirst()).orElseThrow());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Long.class, localIdentifier)).isZero();
        assertThat(campaign.getIssuedCount()).isEqualTo(1);
        assertThat(campaign.getTerminatedAt()).isEqualTo(NOW);
        assertThat(coupon.getId()).isEqualTo(issued.getId());
        assertThat(coupon.getUserId()).isEqualTo(localIdentifier);
        assertThat(coupon.getIssuedAt()).isEqualTo(NOW);
    }

    @DisplayName("보유 목록은 해당 사용자만 발급 시각·쿠폰 ID 내림차순으로 반환한다.")
    @Test
    void listsOnlyOwnerCouponsInStableOrderAcrossCampaigns() {
        Stock first = prepare(2);
        Stock second = prepare(1);
        Stock third = prepare(1);
        long owner = createUser();
        long other = createUser();
        Coupon old = tryAssign(first.campaignId(), owner, NOW).orElseThrow();
        Coupon tiedEarlierId = tryAssign(second.campaignId(), owner, NOW.plusSeconds(1)).orElseThrow();
        Coupon tiedLaterId = tryAssign(third.campaignId(), owner, NOW.plusSeconds(1)).orElseThrow();
        Coupon others = tryAssign(first.campaignId(), other, NOW.plusSeconds(2)).orElseThrow();

        List<Coupon> owned = transact(() -> coupons.findAllIssuedByUserId(owner));

        assertThat(owned).extracting(Coupon::getId)
            .containsExactly(tiedLaterId.getId(), tiedEarlierId.getId(), old.getId());
        assertThat(owned).allSatisfy(coupon -> assertThat(coupon.getUserId()).isEqualTo(owner));
        assertThat(transact(() -> coupons.findAllIssuedByUserId(other)))
            .extracting(Coupon::getId).containsExactly(others.getId());
    }

    @DisplayName("준비 경로는 새 미발급 쿠폰만 저장하고 발급 상태를 덮어쓰지 않는다.")
    @Test
    void rejectsIssuedOrExistingCouponsInPreparationPath() {
        Stock stock = prepare(1);
        long userId = createUser();
        Coupon existing = transact(() -> coupons.findById(stock.couponIds().getFirst()).orElseThrow());
        Coupon issued = Coupon.create(stock.campaignId());
        issued.issueTo(userId, NOW);

        assertThatThrownBy(() -> transact(() -> coupons.saveStock(List.of(existing))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transact(() -> coupons.saveStock(List.of(issued))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(transact(() -> coupons.countByCampaignId(stock.campaignId())).longValue()).isEqualTo(1);
    }

    @DisplayName("서로 다른 사용자 8명이 총량 5개를 경쟁하면 쿠폰 5행만 배정된다.")
    @Test
    void assignsOnlyFiveRowsForEightConcurrentUsers() throws Exception {
        Stock stock = prepare(5);
        List<Long> competitors = IntStream.range(0, 8).mapToObj(index -> createUser()).toList();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<Optional<Coupon>>> attempts = competitors.stream().map(userId -> workers.submit(() -> {
                ready.countDown();
                if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new AssertionError("쿠폰 배정 시작 대기 시간 초과");
                }
                return tryAssign(stock.campaignId(), userId, NOW);
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
            assertThat(transact(() -> coupons.countByCampaignId(stock.campaignId())).longValue()).isEqualTo(5);
            assertThat(transact(() -> coupons.countIssuedByCampaignId(stock.campaignId())).longValue()).isEqualTo(5);
            CouponCampaign campaign = transact(() -> campaigns.findById(stock.campaignId()).orElseThrow());
            assertThat(campaign.getIssuedCount()).isEqualTo(5);
            assertThat(campaign.getTerminatedAt()).isEqualTo(NOW);
            assertThat(transact(() -> coupons.findFirstUnissuedId(stock.campaignId()))).isEmpty();
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Stock prepare(int quota) {
        CouponCampaign campaign = transact(() -> campaigns.save(
            CouponCampaign.create("쿠폰 저장 테스트", quota, "5,000원 할인권", START, END)
        ));
        campaignIds.add(campaign.getId());
        List<Coupon> saved = transact(() -> coupons.saveStock(IntStream.range(0, quota)
            .mapToObj(index -> Coupon.create(campaign.getId())).toList()));
        return new Stock(campaign.getId(), saved.stream().map(Coupon::getId).toList());
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

    private Optional<Coupon> tryAssign(Long campaignId, Long userId, Instant issueNow) {
        return transact(() -> {
            if (campaigns.incrementIssuedCountIfIssuable(campaignId, issueNow) == 0) {
                return Optional.empty();
            }
            long couponId = coupons.findFirstUnissuedId(campaignId).orElseThrow();
            if (coupons.assignIfUnissued(couponId, campaignId, userId, issueNow) != 1) {
                throw new IllegalStateException("쿠폰 조건부 배정 실패");
            }
            return coupons.findById(couponId);
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

    private record Stock(Long campaignId, List<Long> couponIds) {}
}
