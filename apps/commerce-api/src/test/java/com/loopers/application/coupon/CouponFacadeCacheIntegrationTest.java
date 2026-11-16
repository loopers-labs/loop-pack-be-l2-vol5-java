package com.loopers.application.coupon;

import com.loopers.config.CouponAdmissionProperties;
import com.loopers.config.CouponCacheProperties;
import com.loopers.config.CouponWaitingProperties;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.coupon.CouponAdmissionRepository.AdmissionProgress;
import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.CouponCacheRepository;
import com.loopers.domain.coupon.CouponCacheRepository.IssuedCoupon;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponQueryRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.CouponWaitingState;
import com.loopers.fixture.CouponFixture;
import com.loopers.fixture.CouponRedisTarget;
import com.loopers.fixture.SqlCapture;
import com.loopers.infrastructure.coupon.CouponAdmissionRedisRepository;
import com.loopers.infrastructure.coupon.CouponCacheRedisRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.persistence.ConstraintViolationClassifier;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실제 MySQL·fixture Redis 에서 Facade 의 기발급·품절 캐시 경로를 확인한다(설계 §13.10·13.11, KQ3~KQ6·KQ8·KQ9 의 실제 저장소 부분).
 * Facade·수용·캐시 서비스는 사례마다 새로 직접 조립하며 C1 miss 대역을 넣지 않는다. 로컬 품절 슬롯도 사례별 새 서비스에서 시작한다(KQ7).
 * HTTP·사용자 확인 resolver 는 다루지 않는다.
 * 쿠폰 관련 SQL 은 Hibernate StatementInspector(SqlCapture)로 센다. test fixture 의 JdbcTemplate SQL 은 세지 않는다.
 *
 * <p>소유·정리: 캠페인·사용자를 만든 뒤 첫 캐시 요청 전에 그 캠페인의 대기실 키 5개·품절 키와 쓸 campaign×user 기발급 키가
 * 모두 비어 있음을 확인한 경우에만 소유한다. 남은 키는 지우지 않고 실패한다. 이 클래스는 worker 를 만들지 않으며,
 * 끝나면 자기 키만 지우고 DB 를 정리한다. FLUSHALL·컨테이너 조작은 하지 않는다.
 */
@SpringBootTest(properties = SqlCapture.PROPERTY)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Facade 는 실제 Redis 캐시 hit 에서 쿠폰 DB·대기 상태를 건너뛰고, TX·finish 가 끝난 확정 결과와 DB 로 확인한 품절만 채운다.")
class CouponFacadeCacheIntegrationTest {

    private static final Duration LONG_TTL = Duration.ofSeconds(30);
    private static final Duration LOCAL_TTL = Duration.ofSeconds(5);

    @DynamicPropertySource
    static void pinToFixtureContainer(DynamicPropertyRegistry registry) {
        CouponRedisTarget.register(registry);
    }

    @Autowired
    private ApplicationContext context;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private CouponQueryRepository queryRepository;
    @Autowired
    private CouponCampaignRepository campaignRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private ConstraintViolationClassifier classifier;
    @Autowired
    private CouponAdmissionRedisRepository admissionRepository;
    @Autowired
    private CouponCacheRedisRepository cacheRepository;
    @Autowired
    private RedisProperties redisProperties;
    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> master;
    @Autowired
    private CouponFixture fixture;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final Set<String> ownKeys = new LinkedHashSet<>();
    private final List<String> putObservations = new ArrayList<>();
    private final AtomicInteger soldOutReads = new AtomicInteger();
    private boolean targetVerified;
    private CouponAdmissionService admission;

    @BeforeEach
    void requireFixtureRedis() {
        CouponRedisTarget.verify(redisProperties, master);
        targetVerified = true;
    }

    @AfterEach
    void cleanUp() {
        SqlCapture.stop();
        if (targetVerified) {
            master.delete(ownKeys);
        }
        databaseCleanUp.truncateAllTables();
    }

    private void claimEmpty(String key) {
        assertThat(master.type(key)).as("시작 전 키가 비어 있어야 한다: " + key).isEqualTo(DataType.NONE);
        ownKeys.add(key);
    }

    /** 캠페인의 대기실·품절 키와 사용자별 기발급 키를 모두 비었을 때만 소유한다. */
    private void own(Long campaignId, List<Long> users) {
        CouponAdmissionRedisRepository.waitingKeys(campaignId).forEach(this::claimEmpty);
        claimEmpty(CouponCacheRedisRepository.soldOutKey(campaignId));
        users.forEach(user -> claimEmpty(CouponCacheRedisRepository.issuedKey(user, campaignId)));
    }

    /**
     * 운영 캠페인 campaignId 로 열린 수용과 실제 캐시 서비스를 새로 조립한다.
     * 캐시 저장 시점의 TX 활성 여부와 대기 항목 상태를 기록하는 decorator 를 실제 adapter 앞에 둔다(동작은 그대로 위임).
     */
    private CouponFacade facade(Long campaignId, Duration issuedTtl, Duration soldOutTtl, Duration localSoldOutTtl) {
        admission = new CouponAdmissionService(admissionRepository, new CouponAdmissionProperties(3),
            new CouponWaitingProperties(campaignId, Duration.ofSeconds(1), Duration.ofSeconds(30)));
        admission.open();
        CouponCacheService cache = new CouponCacheService(new Observing(cacheRepository),
            new CouponCacheProperties(issuedTtl, soldOutTtl, localSoldOutTtl));
        return new CouponFacade(transactionManager, queryRepository, campaignRepository, couponRepository, classifier, admission, cache);
    }

    private CouponFacade facade(Long campaignId, Duration issuedTtl, Duration soldOutTtl) {
        return facade(campaignId, issuedTtl, soldOutTtl, LOCAL_TTL);
    }

    private CouponFacade facade(Long campaignId) {
        return facade(campaignId, LONG_TTL, LONG_TTL);
    }

    private String issuedRaw(Long user, Long campaignId) {
        return master.opsForValue().get(CouponCacheRedisRepository.issuedKey(user, campaignId));
    }

    private long pttl(String key) {
        return master.getExpire(key, TimeUnit.MILLISECONDS);
    }

    private static List<String> couponSql(List<String> statements) {
        return statements.stream().filter(sql -> sql.toLowerCase(Locale.ROOT).contains("coupon")).toList();
    }

    private static ErrorType errorOf(Runnable call) {
        try {
            call.run();
        } catch (CoreException e) {
            return e.getErrorType();
        }
        throw new AssertionError("CoreException 이 나야 한다.");
    }

    private static void awaitAbsent(RedisTemplate<String, String> master, String key) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (master.type(key) != DataType.NONE && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(master.type(key)).as("TTL 만료: " + key).isEqualTo(DataType.NONE);
    }

    @DisplayName("일반 앱 조립에서 캐시 저장소는 실제 Redis 구현 하나뿐이고 test miss 대역은 없다.")
    @Test
    void productAssemblyUsesRealRepositoryOnly() {
        assertAll(
            () -> assertThat(context.getBeansOfType(CouponCacheRepository.class)).containsOnlyKeys("couponCacheRedisRepository"),
            () -> assertThat(context.containsBean("couponCacheService")).isTrue()
        );
    }

    @DisplayName("신규 발급은 쓰기 TX·finish(ISSUED) 뒤에 채워지고, 이후 등록·조회·발급은 쿠폰 SQL·대기 상태 없이 기존 결과다(닫혀 있어도). DB 총량·한 장은 그대로다.")
    @Test
    void issueFillsAfterFinishThenHitsSkipDatabase() {
        Long campaignId = fixture.activeCampaign("가을 캠페인", 5, CouponBenefitType.FIXED_AMOUNT, 5_000L);
        Long user = fixture.user();
        own(campaignId, List.of(user));
        CouponFacade facade = facade(campaignId);
        String waitingId = facade.registerWaiting(user, campaignId).snapshot().waitingId();
        assertThat(issuedRaw(user, campaignId)).as("미발급은 저장하지 않는다").isNull();
        assertThat(facade.processAdmissions()).contains(new AdmissionProgress(0, 1));

        CouponIssueResult created = facade.issue(user, campaignId, waitingId).result();

        assertAll(
            () -> assertThat(created.created()).isTrue(),
            () -> assertThat(putObservations).containsExactly("issued txActive=false waiting=ISSUED"),
            () -> assertThat(pttl(CouponCacheRedisRepository.issuedKey(user, campaignId))).isBetween(1L, LONG_TTL.toMillis()),
            () -> assertThat(cacheRepository.findIssued(user, campaignId)).contains(new IssuedCoupon(created.couponId(), campaignId,
                "가을 캠페인", CouponBenefitType.FIXED_AMOUNT, 5_000L))
        );

        admission.close();
        SqlCapture.start();
        CouponWaitingResult registered = facade.registerWaiting(user, campaignId);
        CouponWaitingResult found = facade.getWaiting(user, campaignId);
        CouponIssueResult again = facade.issue(user, campaignId, null).result();
        List<String> statements = SqlCapture.stop();

        assertAll(
            () -> assertThat(statements).as("hit 경로 SQL").isEmpty(),
            () -> assertThat(registered.snapshot().status()).isEqualTo(CouponWaitingState.ISSUED),
            () -> assertThat(registered.snapshot().couponId()).isEqualTo(created.couponId()),
            () -> assertThat(found.snapshot().couponId()).isEqualTo(created.couponId()),
            () -> assertThat(again).isEqualTo(new CouponIssueResult(created.couponId(), campaignId, "가을 캠페인",
                CouponBenefitType.FIXED_AMOUNT, 5_000L, false)),
            () -> assertThat(fixture.couponCount(campaignId, user)).isEqualTo(1),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(1)
        );
    }

    @DisplayName("기발급 miss 는 DB 를 읽고 읽기 TX 종료 뒤 채우며, 다음 요청부터 쿠폰 SQL 이 없다. 쿠폰이 없는 사용자는 채우지 않는다.")
    @Test
    void missReadsDatabaseThenFillsPositiveOnly() {
        Long campaignId = fixture.activeCampaign(5);
        List<Long> users = fixture.users(2);
        Long owner = users.get(0);
        Long stranger = users.get(1);
        own(campaignId, users);
        fixture.storedCoupon(owner, campaignId);
        CouponFacade facade = facade(campaignId);

        SqlCapture.start();
        facade.registerWaiting(owner, campaignId);
        List<String> first = couponSql(SqlCapture.stop());
        SqlCapture.start();
        facade.getWaiting(owner, campaignId);
        List<String> second = couponSql(SqlCapture.stop());
        CouponWaitingResult strangerResult = facade.registerWaiting(stranger, campaignId);

        assertAll(
            () -> assertThat(first).hasSize(1),
            () -> assertThat(second).isEmpty(),
            () -> assertThat(putObservations).containsExactly("issued txActive=false waiting=NONE"),
            () -> assertThat(strangerResult.created()).isTrue(),
            () -> assertThat(issuedRaw(stranger, campaignId)).isNull()
        );
    }

    @DisplayName("등록에서 DB 로 확인한 품절만 채우고, 그 뒤에는 캠페인 SQL 없이 409 다. 이후 INACTIVE 가 돼도 유효한 품절 캐시가 우선한다.")
    @Test
    void soldOutFilledFromDatabaseThenWinsOverInactive() {
        Long campaignId = fixture.soldOutCampaign(1);
        List<Long> users = fixture.users(2);
        own(campaignId, users);
        CouponFacade facade = facade(campaignId);

        assertThat(errorOf(() -> facade.registerWaiting(users.get(0), campaignId))).isEqualTo(ErrorType.COUPON_CAMPAIGN_SOLD_OUT);
        String soldOutKey = CouponCacheRedisRepository.soldOutKey(campaignId);
        assertAll(
            () -> assertThat(master.opsForValue().get(soldOutKey)).isEqualTo("true"),
            () -> assertThat(pttl(soldOutKey)).isBetween(1L, LONG_TTL.toMillis()),
            () -> assertThat(putObservations).containsExactly("soldOut txActive=false")
        );

        jdbc.update("UPDATE coupon_campaign SET status = 'INACTIVE' WHERE id = ?", campaignId);
        SqlCapture.start();
        ErrorType cached = errorOf(() -> facade.registerWaiting(users.get(1), campaignId));
        List<String> statements = SqlCapture.stop();

        assertAll(
            () -> assertThat(cached).isEqualTo(ErrorType.COUPON_CAMPAIGN_SOLD_OUT),
            // 기발급 확인 조회(coupon 기준 join)는 남고, 캠페인 상태 조회(from coupon_campaign)는 없다.
            () -> assertThat(couponSql(statements)).hasSize(1),
            () -> assertThat(statements).noneMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("from coupon_campaign")),
            () -> assertThat(admissionRepository.find(campaignId, users.get(1))).isEmpty(),
            () -> assertThat(admission.isAccepting()).isTrue()
        );
    }

    @DisplayName("로컬 품절이 유효하면 등록은 품절 Redis·캠페인 SQL 없이 409 다. 기발급 확인·운영 ID·열림이 먼저이고, 로컬 만료 뒤에는 Redis 를 다시 읽는다.")
    @Test
    void localSoldOutSkipsRedisAndCampaignSql() throws InterruptedException {
        Long campaignId = fixture.activeCampaign(1);
        List<Long> users = fixture.users(5);
        Long owner = users.get(0);
        Long ownerCoupon = fixture.storedCoupon(owner, campaignId); // 발급 1/1 로 품절이 된다.
        own(campaignId, users);
        CouponFacade facade = facade(campaignId, LONG_TTL, LONG_TTL, Duration.ofSeconds(2));
        String soldOutKey = CouponCacheRedisRepository.soldOutKey(campaignId);

        // DB 로 품절 확인 → Redis 저장만(로컬 아님) → 다음 요청이 실제 Redis true·PTTL 을 읽고 로컬을 채운다.
        assertThat(errorOf(() -> facade.registerWaiting(users.get(1), campaignId))).isEqualTo(ErrorType.COUPON_CAMPAIGN_SOLD_OUT);
        assertThat(soldOutReads.get()).as("첫 요청: 빈 Redis 조회 1").isEqualTo(1);
        assertThat(errorOf(() -> facade.registerWaiting(users.get(2), campaignId))).isEqualTo(ErrorType.COUPON_CAMPAIGN_SOLD_OUT);
        long filledAt = System.nanoTime();
        assertThat(soldOutReads.get()).as("둘째 요청: Redis true 조회로 로컬 채움").isEqualTo(2);
        long pttlAfterFill = pttl(soldOutKey);

        SqlCapture.start();
        ErrorType local = errorOf(() -> facade.registerWaiting(users.get(3), campaignId));
        List<String> statements = SqlCapture.stop();
        var ownerResult = facade.registerWaiting(owner, campaignId);
        admission.close();
        ErrorType closed = errorOf(() -> facade.registerWaiting(users.get(4), campaignId));
        admission.open();
        long readsWhileLocal = soldOutReads.get();
        long pttlAfterHits = pttl(soldOutKey);
        long localElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - filledAt);

        assertAll(
            () -> assertThat(localElapsed).as("위 요청들은 로컬 수명(2초) 안에서 끝나야 판정이 유효하다").isLessThan(2_000),
            () -> assertThat(local).isEqualTo(ErrorType.COUPON_CAMPAIGN_SOLD_OUT),
            () -> assertThat(readsWhileLocal).as("로컬 hit·기존 결과·닫힘은 품절 Redis 를 읽지 않는다").isEqualTo(2),
            // 기발급 확인 조회(coupon 기준 join)는 남고, 캠페인 상태 조회(from coupon_campaign)는 없다.
            () -> assertThat(couponSql(statements)).hasSize(1),
            () -> assertThat(statements).noneMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("from coupon_campaign")),
            () -> assertThat(ownerResult.snapshot().status()).isEqualTo(CouponWaitingState.ISSUED),
            () -> assertThat(ownerResult.snapshot().couponId()).isEqualTo(ownerCoupon),
            () -> assertThat(closed).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE),
            () -> assertThat(pttlAfterHits).as("로컬 hit 은 Redis TTL 을 연장하지 않는다").isLessThanOrEqualTo(pttlAfterFill),
            () -> assertThat(admissionRepository.find(campaignId, users.get(3))).isEmpty(),
            () -> assertThat(putObservations).containsExactly("soldOut txActive=false", "issued txActive=false waiting=NONE")
        );

        long remaining = 2_500 - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - filledAt);
        if (remaining > 0) {
            Thread.sleep(remaining);
        }
        assertThat(errorOf(() -> facade.registerWaiting(users.get(3), campaignId))).isEqualTo(ErrorType.COUPON_CAMPAIGN_SOLD_OUT);
        assertThat(soldOutReads.get()).as("로컬 만료 뒤 Redis 를 다시 읽는다").isEqualTo(3);
    }

    @DisplayName("품절 캐시 miss 의 없음 404·비활성 409 는 기존 순서이고 품절 캐시를 쓰지 않는다.")
    @Test
    void missingAndInactiveDoNotFillSoldOut() {
        Long inactive = fixture.inactiveCampaign(1);
        Long missing = inactive + 1_000;
        Long user = fixture.user();
        own(inactive, List.of(user));
        own(missing, List.of(user));

        assertAll(
            () -> assertThat(errorOf(() -> facade(inactive).registerWaiting(user, inactive))).isEqualTo(ErrorType.COUPON_CAMPAIGN_INACTIVE),
            () -> assertThat(errorOf(() -> facade(missing).registerWaiting(user, missing))).isEqualTo(ErrorType.COUPON_CAMPAIGN_NOT_FOUND),
            () -> assertThat(master.type(CouponCacheRedisRepository.soldOutKey(inactive))).isEqualTo(DataType.NONE),
            () -> assertThat(master.type(CouponCacheRedisRepository.soldOutKey(missing))).isEqualTo(DataType.NONE),
            () -> assertThat(putObservations).isEmpty()
        );
    }

    @DisplayName("발급의 수량 부족 409 는 기발급·품절 캐시를 쓰지 않고, 자동 입장 처리는 품절 캐시가 있어도 DB 기준으로 승격한다.")
    @Test
    void issueRejectionAndSchedulerIgnoreCache() {
        Long campaignId = fixture.activeCampaign(1);
        List<Long> users = fixture.users(2);
        own(campaignId, users);
        CouponFacade facade = facade(campaignId);
        List<String> waitingIds = new ArrayList<>();
        for (Long user : users) {
            waitingIds.add(facade.registerWaiting(user, campaignId).snapshot().waitingId());
        }
        String soldOutKey = CouponCacheRedisRepository.soldOutKey(campaignId);
        master.opsForValue().set(soldOutKey, "true", LONG_TTL);

        assertThat(facade.processAdmissions()).contains(new AdmissionProgress(0, 2));
        assertThat(facade.issue(users.get(0), campaignId, waitingIds.get(0)).result().created()).isTrue();
        assertThat(errorOf(() -> facade.issue(users.get(1), campaignId, waitingIds.get(1)))).isEqualTo(ErrorType.COUPON_CAMPAIGN_SOLD_OUT);

        assertAll(
            () -> assertThat(issuedRaw(users.get(1), campaignId)).isNull(),
            () -> assertThat(putObservations).containsExactly("issued txActive=false waiting=ISSUED"),
            () -> assertThat(fixture.couponCount(campaignId)).isEqualTo(1)
        );
    }

    @DisplayName("warm cache 의 캠페인 이름은 TTL 동안 이전 값이고, 만료 뒤 DB 에서 새 이름으로 다시 채운다. 목록은 캐시 없이 현재 이름이다.")
    @Test
    void warmCacheNameDelayThenRefresh() throws Exception {
        Long campaignId = fixture.activeCampaign("이전 이름", 5, CouponBenefitType.PERCENTAGE, 10L);
        Long user = fixture.user();
        own(campaignId, List.of(user));
        fixture.storedCoupon(user, campaignId);
        CouponFacade facade = facade(campaignId, Duration.ofSeconds(2), LONG_TTL);

        assertThat(facade.issue(user, campaignId, null).result().campaignName()).isEqualTo("이전 이름");
        fixture.rename(campaignId, "새 이름");
        String cached = facade.issue(user, campaignId, null).result().campaignName();
        String listed = facade.getMyCoupons(user, new PageCommand(0, 20), ListSort.LATEST).items().get(0).campaignName();
        awaitAbsent(master, CouponCacheRedisRepository.issuedKey(user, campaignId));
        String refreshed = facade.issue(user, campaignId, null).result().campaignName();

        assertAll(
            () -> assertThat(cached).isEqualTo("이전 이름"),
            () -> assertThat(listed).isEqualTo("새 이름"),
            () -> assertThat(refreshed).isEqualTo("새 이름"),
            () -> assertThat(cacheRepository.findIssued(user, campaignId).map(IssuedCoupon::campaignName)).contains("새 이름")
        );
    }

    @DisplayName("손상·TTL 없음·다른 캠페인 값의 캐시는 DB 결과로 보완하고 수용을 닫지 않으며, 그 키를 지우거나 덮어쓰지 않는다.")
    @Test
    void corruptOrNoTtlCacheFallsBackToDatabase() {
        Long campaignId = fixture.activeCampaign("가을 캠페인", 5, CouponBenefitType.FIXED_AMOUNT, 5_000L);
        List<Long> users = fixture.users(3);
        own(campaignId, users);
        Long corrupt = users.get(0);
        Long noTtl = users.get(1);
        Long otherCampaign = users.get(2);
        Long couponOfCorrupt = fixture.storedCoupon(corrupt, campaignId);
        Long couponOfNoTtl = fixture.storedCoupon(noTtl, campaignId);
        Long couponOfOther = fixture.storedCoupon(otherCampaign, campaignId);
        String corruptJson = "{\"couponId\":\"1\"}";
        String noTtlJson = IssuedJson.of(999_999L, campaignId);
        String otherJson = IssuedJson.of(888_888L, campaignId + 1);
        master.opsForValue().set(CouponCacheRedisRepository.issuedKey(corrupt, campaignId), corruptJson, LONG_TTL);
        master.opsForValue().set(CouponCacheRedisRepository.issuedKey(noTtl, campaignId), noTtlJson);
        master.opsForValue().set(CouponCacheRedisRepository.issuedKey(otherCampaign, campaignId), otherJson, LONG_TTL);
        master.opsForValue().set(CouponCacheRedisRepository.soldOutKey(campaignId), "TRUE", LONG_TTL);
        CouponFacade facade = facade(campaignId);

        Long first = facade.issue(corrupt, campaignId, null).result().couponId();
        Long second = facade.issue(noTtl, campaignId, null).result().couponId();
        Long third = facade.issue(otherCampaign, campaignId, null).result().couponId();
        Long fresh = fixture.user();
        claimEmpty(CouponCacheRedisRepository.issuedKey(fresh, campaignId));
        boolean registered = facade.registerWaiting(fresh, campaignId).created();

        assertAll(
            () -> assertThat(first).isEqualTo(couponOfCorrupt),
            () -> assertThat(second).isEqualTo(couponOfNoTtl),
            () -> assertThat(third).isEqualTo(couponOfOther),
            () -> assertThat(registered).as("손상 품절 값은 품절로 보지 않고 DB 로 확인").isTrue(),
            () -> assertThat(issuedRaw(corrupt, campaignId)).isEqualTo(corruptJson),
            () -> assertThat(issuedRaw(noTtl, campaignId)).isEqualTo(noTtlJson),
            () -> assertThat(pttl(CouponCacheRedisRepository.issuedKey(noTtl, campaignId))).isEqualTo(-1L),
            () -> assertThat(issuedRaw(otherCampaign, campaignId)).isEqualTo(otherJson),
            () -> assertThat(master.opsForValue().get(CouponCacheRedisRepository.soldOutKey(campaignId))).isEqualTo("TRUE"),
            () -> assertThat(admission.isAccepting()).isTrue()
        );
    }

    /** 다섯 필드 JSON 을 만든다. adapter 의 직렬화와 같은 모양이다. */
    private static final class IssuedJson {
        static String of(long couponId, long campaignId) {
            return "{\"couponId\":" + couponId + ",\"campaignId\":" + campaignId
                + ",\"campaignName\":\"가을 캠페인\",\"benefitType\":\"FIXED_AMOUNT\",\"benefitValue\":5000}";
        }
    }

    /** 실제 adapter 에 위임하면서 저장 시점의 TX 활성 여부와 그 사용자의 대기 항목 상태를 기록한다. */
    private final class Observing implements CouponCacheRepository {
        private final CouponCacheRepository delegate;

        Observing(CouponCacheRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<IssuedCoupon> findIssued(Long userId, Long campaignId) {
            return delegate.findIssued(userId, campaignId);
        }

        @Override
        public void putIssuedIfAbsent(Long userId, Long campaignId, IssuedCoupon value, long ttlMillis) {
            String waiting = admissionRepository.find(campaignId, userId).map(s -> s.status().name()).orElse("NONE");
            putObservations.add("issued txActive=" + TransactionSynchronizationManager.isActualTransactionActive() + " waiting=" + waiting);
            delegate.putIssuedIfAbsent(userId, campaignId, value, ttlMillis);
        }

        @Override
        public OptionalLong findSoldOutTtl(Long campaignId) {
            soldOutReads.incrementAndGet();
            return delegate.findSoldOutTtl(campaignId);
        }

        @Override
        public void putSoldOutIfAbsent(Long campaignId, long ttlMillis) {
            putObservations.add("soldOut txActive=" + TransactionSynchronizationManager.isActualTransactionActive());
            delegate.putSoldOutIfAbsent(campaignId, ttlMillis);
        }
    }
}
