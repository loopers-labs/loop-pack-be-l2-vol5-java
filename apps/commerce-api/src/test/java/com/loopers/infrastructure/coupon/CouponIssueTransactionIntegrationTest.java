package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.CouponCampaignIssueData;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponModel;
import com.loopers.domain.coupon.CouponQueryRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.OwnedCoupon;
import com.loopers.fixture.CouponFixture;
import com.loopers.fixture.SqlCapture;
import com.loopers.support.persistence.ConstraintViolationClassifier;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 쿠폰 발급 쓰기 TX 의 DB 계약을 실제 MySQL 에서 확인한다(상세설계 6.2·6.3, Q3·Q4·Q5·Q13 의 DB 부분).
 * Facade 구현 전이므로 test 소스의 TransactionTemplate 으로 설계 순서(조건부 UPDATE → scalar 조회 → INSERT)를 조합한다.
 * HTTP 상태·Redis 수용·응답 JSON 은 이 테스트의 검증 대상이 아니다.
 *
 * <p>worker 는 유한한 deadline 으로 기다리고 executor 종료를 확인한다. 종료를 확인하지 못하면 살아 있는 TX 가 남았을 수 있으므로
 * 테스트 DB 정리를 보류한다.
 */
@SpringBootTest(properties = SqlCapture.PROPERTY)
@DisplayName("쿠폰 발급 쓰기 TX 는 조건부 증가와 INSERT 를 한 TX 로 commit/rollback 하고 총량·중복을 DB 에서 지킨다.")
class CouponIssueTransactionIntegrationTest {

    private static final Duration RESULT_DEADLINE = Duration.ofSeconds(20);
    private static final Duration TERMINATION_DEADLINE = Duration.ofSeconds(30);
    private static final Duration LOCK_WAIT_DEADLINE = Duration.ofSeconds(10);

    @Autowired
    private CouponCampaignRepository campaignRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponQueryRepository queryRepository;
    @Autowired
    private ConstraintViolationClassifier classifier;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CouponFixture fixture;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final WorkerTermination workers = new WorkerTermination();

    @AfterEach
    void tearDown() {
        SqlCapture.stop();
        if (!workers.safeToClean()) {
            throw new IllegalStateException("worker 종료 미확인: 살아 있는 TX 가 있을 수 있어 테스트 DB 정리를 보류한다");
        }
        databaseCleanUp.truncateAllTables();
    }

    @Nested
    @DisplayName("단일 TX")
    class SingleTransaction {

        @DisplayName("조건부 UPDATE 가 첫 업무 SQL 이고 scalar 조회·INSERT 가 같은 REPEATABLE READ TX 에서 commit 된다. save 반환 객체를 쓴다.")
        @Test
        void commitsUpdateAndInsertTogether() {
            Long userId = fixture.user();
            Long campaignId = fixture.activeCampaign("발급 캠페인", 3L, CouponBenefitType.FIXED_AMOUNT, 5_000L);

            SqlCapture.start();
            TxObservation observed = transactionTemplate.execute(status -> {
                int affected = campaignRepository.reserveOne(campaignId, ZonedDateTime.now());
                CouponCampaignIssueData data = campaignRepository.findIssueData(campaignId).orElseThrow();
                CouponModel coupon = CouponModel.issue(userId, campaignId, data.benefitType(), data.benefitValue());
                CouponModel saved = couponRepository.save(coupon);
                return new TxObservation(affected, data, saved.getId(), coupon.getId(), saved == coupon,
                    saved.getBenefitType(), saved.getBenefitValue(),
                    jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class),
                    TransactionSynchronizationManager.isActualTransactionActive());
            });
            List<String> sql = SqlCapture.stop().stream().map(s -> s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim()).toList();
            System.out.println("[COUPON TX SQL] " + sql);
            System.out.println("[COUPON TX] " + observed);

            assertAll(
                () -> assertThat(observed.affected()).isEqualTo(1),
                () -> assertThat(observed.isolation()).isEqualTo("REPEATABLE-READ"),
                () -> assertThat(observed.txActive()).isTrue(),
                () -> assertThat(observed.data().name()).isEqualTo("발급 캠페인"),
                () -> assertThat(observed.savedId()).isPositive(),
                () -> assertThat(observed.savedBenefitType()).isEqualTo(CouponBenefitType.FIXED_AMOUNT),
                () -> assertThat(observed.savedBenefitValue()).isEqualTo(5_000L),
                () -> assertThat(sql.get(0)).startsWith("update coupon_campaign set issued_count = issued_count + 1"),
                () -> assertThat(sql.get(1)).startsWith("select").contains("from coupon_campaign"),
                () -> assertThat(sql.get(sql.size() - 1)).startsWith("insert into coupon "),
                () -> assertThat(sql).filteredOn(s -> s.contains("coupon_campaign")).hasSize(2)
                    .noneMatch(s -> s.startsWith("update coupon_campaign set name")),
                () -> assertThat(sql).filteredOn(s -> s.startsWith("select") && s.contains("from coupon ")
                    && (s.contains("campaign_id=") || s.contains("user_id="))).as("INSERT 전 (campaign_id, user_id) 재조회 없음").isEmpty(),
                () -> assertThat(fixture.couponCount(campaignId)).isEqualTo(1L),
                () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(1L),
                () -> assertThat(jdbcTemplate.queryForObject("SELECT id FROM coupon WHERE campaign_id = ?", Long.class, campaignId))
                    .isEqualTo(observed.savedId())
            );
        }

        @DisplayName("UPDATE 뒤 save 지점 실패는 증가분까지 rollback 하고 새 조회에서 쿠폰 0·발급 수 0 이다.")
        @Test
        void rollsBackIncrementWhenSaveFails() {
            Long userId = fixture.user();
            Long campaignId = fixture.activeCampaign(3L);
            List<Integer> affected = new ArrayList<>();

            assertThatThrownBy(() -> transactionTemplate.execute(status -> {
                affected.add(campaignRepository.reserveOne(campaignId, ZonedDateTime.now()));
                affected.add(jdbcTemplate.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id = ?", Integer.class,
                    campaignId));
                CouponCampaignIssueData data = campaignRepository.findIssueData(campaignId).orElseThrow();
                CouponModel coupon = CouponModel.issue(userId, campaignId, data.benefitType(), data.benefitValue());
                throw new InjectedSaveFailure(coupon);
            })).isInstanceOf(InjectedSaveFailure.class);

            assertAll(
                () -> assertThat(affected).as("실제 UPDATE 1건, TX 안에서 증가 관찰").containsExactly(1, 1),
                () -> assertThat(fixture.issuedCount(campaignId)).isZero(),
                () -> assertThat(fixture.couponCount(campaignId)).isZero()
            );
        }

        @DisplayName("실제 INSERT 의 named unique 충돌은 증가분까지 rollback 하고 분류기는 uk_coupon_campaign_user 로 식별한다.")
        @Test
        void rollsBackIncrementWhenInsertViolatesUnique() {
            Long userId = fixture.user();
            Long campaignId = fixture.activeCampaign(3L);
            issueOnce(userId, campaignId);

            Throwable failure = catchFailure(() -> issueOnce(userId, campaignId));

            assertAll(
                () -> assertThat(failure).isInstanceOf(DataIntegrityViolationException.class),
                () -> assertThat(classifier.namedUniqueViolation(failure)).contains("uk_coupon_campaign_user"),
                () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(1L),
                () -> assertThat(fixture.couponCount(campaignId, userId)).isEqualTo(1L)
            );
        }

        @DisplayName("비활성·품절·없는 캠페인의 조건부 UPDATE 는 0건이며 DB 효과가 없다.")
        @Test
        void reserveReturnsZeroWithoutEffect() {
            Long inactive = fixture.inactiveCampaign(3L);
            Long soldOut = fixture.soldOutCampaign(2L);

            List<Integer> affected = transactionTemplate.execute(status -> List.of(
                campaignRepository.reserveOne(inactive, ZonedDateTime.now()),
                campaignRepository.reserveOne(soldOut, ZonedDateTime.now()),
                campaignRepository.reserveOne(999_999L, ZonedDateTime.now())));

            assertAll(
                () -> assertThat(affected).containsExactly(0, 0, 0),
                () -> assertThat(fixture.issuedCount(inactive)).isZero(),
                () -> assertThat(fixture.issuedCount(soldOut)).isEqualTo(2L),
                () -> assertThat(campaignRepository.findIssueData(999_999L)).isEmpty()
            );
        }
    }

    @Nested
    @DisplayName("직접 DB 경쟁")
    class Races {

        @DisplayName("총량 5 에 서로 다른 8명이 동시에 발급하면 성공 5·수량 미확보 3, 실제 쿠폰 5·issuedCount 5 다.")
        @Test
        void lastQuotaRace() throws Exception {
            List<Long> users = fixture.users(8);
            Long campaignId = fixture.activeCampaign(5L);
            CountDownLatch start = new CountDownLatch(1);

            List<Callable<Outcome>> tasks = users.stream().<Callable<Outcome>>map(userId -> () -> {
                awaitLatch(start);
                return attempt(userId, campaignId, null, null);
            }).toList();
            List<Outcome> outcomes = runAll(tasks, start::countDown);
            System.out.println("[COUPON RACE quota=5 users=8] " + outcomes);

            assertAll(
                () -> assertThat(outcomes).filteredOn(o -> o == Outcome.CREATED).hasSize(5),
                () -> assertThat(outcomes).filteredOn(o -> o == Outcome.NOT_RESERVED).hasSize(3),
                () -> assertThat(outcomes).hasSize(8),
                () -> assertThat(fixture.couponCount(campaignId)).isEqualTo(5L),
                () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(5L),
                () -> assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT user_id) FROM coupon WHERE campaign_id = ?", Long.class, campaignId)).isEqualTo(5L)
            );
        }

        @DisplayName("잔여량이 있는 같은 사용자 두 TX 는 둘 다 UPDATE 1건이고 뒤 INSERT 가 named unique 로 rollback 된다. 새 읽기는 앞 결과다.")
        @Test
        void sameUserUniqueConflictWithRemainingQuota() throws Exception {
            Long userId = fixture.user();
            Long campaignId = fixture.activeCampaign(10L);
            SameUserRace race = sameUserRace(userId, campaignId);

            assertAll(
                () -> assertThat(race.first()).isEqualTo(Outcome.CREATED),
                () -> assertThat(race.second()).isEqualTo(Outcome.UNIQUE_CONFLICT),
                () -> assertThat(race.secondReserved()).as("뒤 TX 도 잔여량이 있어 UPDATE 1건").isEqualTo(1),
                () -> assertThat(fixture.couponCount(campaignId, userId)).isEqualTo(1L),
                () -> assertThat(fixture.issuedCount(campaignId)).as("뒤 TX 증가분 rollback").isEqualTo(1L),
                () -> assertThat(race.storedAfter()).hasValueSatisfying(owned ->
                    assertThat(owned.couponId()).isEqualTo(race.firstCouponId()))
            );
        }

        @DisplayName("앞 TX 가 마지막 수량을 발급하면 같은 사용자의 뒤 TX 는 UPDATE 0건으로 INSERT 에 도달하지 않는다. 새 읽기는 앞 결과다.")
        @Test
        void sameUserLastQuotaUpdatesZero() throws Exception {
            Long userId = fixture.user();
            Long campaignId = fixture.activeCampaign(1L);
            SameUserRace race = sameUserRace(userId, campaignId);

            assertAll(
                () -> assertThat(race.first()).isEqualTo(Outcome.CREATED),
                () -> assertThat(race.second()).isEqualTo(Outcome.NOT_RESERVED),
                () -> assertThat(race.secondReserved()).isZero(),
                () -> assertThat(fixture.couponCount(campaignId, userId)).isEqualTo(1L),
                () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(1L),
                () -> assertThat(race.storedAfter()).hasValueSatisfying(owned ->
                    assertThat(owned.couponId()).isEqualTo(race.firstCouponId()))
            );
        }

        /**
         * 앞 TX 를 INSERT 뒤 commit 전에 붙잡고, 뒤 TX 가 같은 캠페인 행 잠금을 기다리는 것을 InnoDB 로 확인한 다음 앞 TX 를 놓는다.
         * 두 worker 종료를 확인한 뒤 새 읽기 TX 로 기존 결과를 조회한다.
         */
        private SameUserRace sameUserRace(Long userId, Long campaignId) throws Exception {
            CountDownLatch firstInserted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            int[] secondReserved = {-1};
            Long[] firstCouponId = {null};

            ExecutorService executor = workers.start(2);
            Future<Outcome> first;
            Future<Outcome> second;
            Outcome firstOutcome;
            Outcome secondOutcome;
            try {
                first = executor.submit(() -> attempt(userId, campaignId, null, savedId -> {
                    firstCouponId[0] = savedId;
                    firstInserted.countDown();
                    awaitLatch(releaseFirst);
                }));
                awaitLatch(firstInserted);
                second = executor.submit(() -> attempt(userId, campaignId, affected -> secondReserved[0] = affected, null));
                boolean waited = awaitLockWait();
                releaseFirst.countDown();
                assertThat(waited).as("뒤 TX 가 캠페인 행 잠금을 기다린 관찰").isTrue();
                firstOutcome = first.get(RESULT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
                secondOutcome = second.get(RESULT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            } finally {
                releaseFirst.countDown();
                workers.close(executor, TERMINATION_DEADLINE);
            }
            Optional<OwnedCoupon> stored = transactionTemplate.execute(status -> queryRepository.findOwned(userId, campaignId));
            System.out.println("[COUPON SAME USER] first=" + firstOutcome + " second=" + secondOutcome
                + " secondReserved=" + secondReserved[0]);
            return new SameUserRace(firstOutcome, secondOutcome, secondReserved[0], firstCouponId[0], stored);
        }
    }

    /** 설계 6.2 순서를 test 소스에서 조합한 한 번의 발급 시도. 결과 분류는 TX 종료(execute 반환/예외) 뒤에 한다. */
    private Outcome attempt(Long userId, Long campaignId, java.util.function.IntConsumer onReserved,
                            java.util.function.LongConsumer afterSave) {
        try {
            transactionTemplate.execute(status -> {
                int affected = campaignRepository.reserveOne(campaignId, ZonedDateTime.now());
                if (onReserved != null) {
                    onReserved.accept(affected);
                }
                if (affected == 0) {
                    throw new NotReserved();
                }
                CouponCampaignIssueData data = campaignRepository.findIssueData(campaignId).orElseThrow();
                CouponModel saved = couponRepository.save(CouponModel.issue(userId, campaignId, data.benefitType(), data.benefitValue()));
                if (afterSave != null) {
                    afterSave.accept(saved.getId());
                }
                return saved.getId();
            });
            return Outcome.CREATED;
        } catch (NotReserved e) {
            return Outcome.NOT_RESERVED;
        } catch (RuntimeException e) {
            if (classifier.namedUniqueViolation(e).filter("uk_coupon_campaign_user"::equals).isPresent()) {
                return Outcome.UNIQUE_CONFLICT;
            }
            throw e;
        }
    }

    private Long issueOnce(Long userId, Long campaignId) {
        return transactionTemplate.execute(status -> {
            campaignRepository.reserveOne(campaignId, ZonedDateTime.now());
            CouponCampaignIssueData data = campaignRepository.findIssueData(campaignId).orElseThrow();
            return couponRepository.save(CouponModel.issue(userId, campaignId, data.benefitType(), data.benefitValue())).getId();
        });
    }

    private <T> List<T> runAll(List<Callable<T>> tasks, Runnable afterSubmit) throws Exception {
        ExecutorService executor = workers.start(tasks.size());
        List<T> results = new ArrayList<>();
        try {
            List<Future<T>> futures = tasks.stream().map(executor::submit).toList();
            afterSubmit.run();
            long deadline = System.nanoTime() + RESULT_DEADLINE.toNanos();
            for (Future<T> future : futures) {
                results.add(future.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
        } finally {
            afterSubmit.run();
            workers.close(executor, TERMINATION_DEADLINE);
        }
        return results;
    }

    /** 테스트 DB 사용자는 PROCESS 권한이 없어 테스트 컨테이너 root 로 InnoDB 잠금 대기를 관측한다. */
    private boolean awaitLockWait() throws Exception {
        long deadline = System.nanoTime() + LOCK_WAIT_DEADLINE.toNanos();
        String url = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        String password = System.getProperty("datasource.mysql-jpa.main.password");
        while (System.nanoTime() < deadline) {
            try (var connection = DriverManager.getConnection(url, "root", password);
                 var statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.innodb_trx
                     WHERE trx_state = 'LOCK WAIT' AND LOWER(trx_query) LIKE '%update coupon_campaign%'
                     """);
                 var rows = statement.executeQuery()) {
                rows.next();
                if (rows.getLong(1) > 0) {
                    return true;
                }
            }
            Thread.sleep(20L);
        }
        return false;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(RESULT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("장벽 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Throwable catchFailure(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("실패가 발생하지 않았다");
    }

    enum Outcome { CREATED, NOT_RESERVED, UNIQUE_CONFLICT }

    /** 수량 미확보 신호. 쓰기 TX 를 rollback 시키고 결과 판별은 TX 종료 뒤에 한다. */
    static class NotReserved extends RuntimeException {
    }

    static class InjectedSaveFailure extends RuntimeException {
        InjectedSaveFailure(CouponModel coupon) {
            super("save 지점 실패 주입: userId=" + coupon.getUserId());
        }
    }

    record TxObservation(int affected, CouponCampaignIssueData data, Long savedId, Long argumentId, boolean sameInstance,
                         CouponBenefitType savedBenefitType, long savedBenefitValue, String isolation, boolean txActive) {
    }

    record SameUserRace(Outcome first, Outcome second, int secondReserved, Long firstCouponId,
                        Optional<OwnedCoupon> storedAfter) {
    }
}
