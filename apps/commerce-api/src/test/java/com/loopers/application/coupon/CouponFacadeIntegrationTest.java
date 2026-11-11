package com.loopers.application.coupon;

import com.loopers.config.CouponAdmissionProperties;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.CouponCampaignIssueData;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponQueryRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.OwnedCoupon;
import com.loopers.fixture.CouponFixture;
import com.loopers.fixture.CouponRedisTarget;
import com.loopers.fixture.SqlCapture;
import com.loopers.infrastructure.coupon.CouponAdmissionRedisRepository;
import com.loopers.infrastructure.coupon.WorkerTermination;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실제 MySQL·fixture Redis 컨테이너에서 Facade 의 발급 경로를 확인한다(Q3·Q4·Q5, FULL 뒤 추가 조회 없음, 닫힘·기존 결과 우선).
 * Facade 는 test 에서 직접 조립한다. 저장소는 실제 구현이고, 실제 ACQUIRED 직후·쓰기 TX 전 붙잡기와 실패 주입만 test decorator 가 맡는다.
 * 앱의 수용 서비스 bean 과 별개인 서비스 인스턴스를 쓰므로 수용량은 사례별로 정한다. Redis 키는 같은 전용 키다.
 *
 * <p>정리 순서: 붙잡기 해제 → worker 종료 확인 → 이 테스트가 보낸 token 만 SREM → DB 정리. 종료를 확인하지 못하면 정리를 보류한다.
 */
@SpringBootTest(properties = SqlCapture.PROPERTY)
// fixture Redis 주소 주입 등으로 이 클래스만의 컨텍스트가 생긴다. 끝나면 닫아 MySQL 연결 풀을 반납한다(누적 시 Too many connections).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Facade 는 실제 DB 경쟁에서 총량·사용자당 한 장을 지키고, 실패 뒤에도 종료 확인 후 자기 token 만 반환한다.")
class CouponFacadeIntegrationTest {

    private static final Duration DEADLINE = Duration.ofSeconds(20);
    private static final Duration TERMINATION_DEADLINE = Duration.ofSeconds(30);
    private static final String KEY = CouponAdmissionRedisRepository.PERMITS_KEY;

    @DynamicPropertySource
    static void pinToFixtureContainer(DynamicPropertyRegistry registry) {
        CouponRedisTarget.register(registry);
    }

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
    private CouponAdmissionRedisRepository redisRepository;
    @Autowired
    private RedisProperties redisProperties;
    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> master;
    @Autowired
    private CouponFixture fixture;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final WorkerTermination workers = new WorkerTermination();
    private HoldingAdmissionRepository holding;
    private boolean targetVerified;

    @BeforeEach
    void requireFixtureRedis() {
        CouponRedisTarget.verify(redisProperties, master);
        targetVerified = true;
        assertThat(master.type(KEY)).as("시작 전 전용 키가 비어 있어야 한다").isEqualTo(DataType.NONE);
        holding = new HoldingAdmissionRepository(redisRepository);
    }

    @AfterEach
    void cleanUp() {
        SqlCapture.stop();
        if (holding != null) {
            holding.stopHolding();
        }
        if (!workers.safeToClean()) {
            throw new IllegalStateException("worker 종료를 확인하지 못해 DB·token 정리를 보류한다.");
        }
        if (targetVerified && holding != null && !holding.sentTokens().isEmpty()) {
            master.opsForSet().remove(KEY, holding.sentTokens().toArray());
        }
        databaseCleanUp.truncateAllTables();
        if (targetVerified) {
            assertThat(master.type(KEY)).as("자기 token 정리 뒤 전용 키가 비어야 한다").isEqualTo(DataType.NONE);
        }
    }

    private CouponFacade facade(CouponQueryRepository query, CouponCampaignRepository campaigns,
                                CouponRepository coupons, CouponAdmissionService admission) {
        return new CouponFacade(transactionManager, query, campaigns, coupons, classifier, admission);
    }

    private CouponAdmissionService openAdmission(int capacity) {
        CouponAdmissionService admission = new CouponAdmissionService(holding, new CouponAdmissionProperties(capacity));
        admission.open();
        return admission;
    }

    /** 각 실행의 결과 또는 예외. */
    private List<Object> runHeld(CouponFacade facade, List<Long> users, Long campaignId) throws Exception {
        int n = users.size();
        holding.holdNextAcquired(n, DEADLINE);
        ExecutorService executor = workers.start(n);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (Long user : users) {
                futures.add(executor.submit(() -> {
                    try {
                        return facade.issue(user, campaignId);
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            assertThat(holding.awaitHeld(DEADLINE)).as("모든 실행이 실제 ACQUIRED 뒤 쓰기 TX 전에 붙잡혀야 한다").isTrue();
            assertAll(
                () -> assertThat(master.opsForSet().members(KEY)).containsExactlyInAnyOrderElementsOf(holding.heldTokens()),
                () -> assertThat(fixture.issuedCount(campaignId)).as("붙잡힌 동안 쓰기 TX 미진입").isZero()
            );
            holding.letThrough(n);
        } finally {
            holding.stopHolding();
            workers.close(executor, TERMINATION_DEADLINE);
        }
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(0, TimeUnit.MILLISECONDS));
        }
        return results;
    }

    private static long count(List<Object> results, java.util.function.Predicate<Object> predicate) {
        return results.stream().filter(predicate).count();
    }

    private static boolean created(Object r) {
        return r instanceof CouponIssueResult result && result.created();
    }

    private static boolean stored(Object r) {
        return r instanceof CouponIssueResult result && !result.created();
    }

    private static boolean rejected(Object r, ErrorType type) {
        return r instanceof CoreException e && e.getErrorType() == type;
    }

    /** reserveOne 결과를 기록하는 test decorator. 나머지는 실제 구현에 위임한다. */
    private CouponCampaignRepository recordingCampaigns(List<Integer> reserveResults) {
        return new CouponCampaignRepository() {
            @Override
            public CouponCampaignModel save(CouponCampaignModel campaign) {
                return campaignRepository.save(campaign);
            }

            @Override
            public int reserveOne(Long campaignId, ZonedDateTime now) {
                int affected = campaignRepository.reserveOne(campaignId, now);
                reserveResults.add(affected);
                return affected;
            }

            @Override
            public Optional<CouponCampaignIssueData> findIssueData(Long campaignId) {
                return campaignRepository.findIssueData(campaignId);
            }

            @Override
            public Optional<CouponCampaignModel> findById(Long campaignId) {
                return campaignRepository.findById(campaignId);
            }
        };
    }

    @DisplayName("Q4: 총량 5·서로 다른 8명이 수용(C=8)을 통과해 실제 DB 경쟁을 하면 발급 5·품절 3·쿠폰 5·발급 수 5 이고 token 8개가 모두 반환된다.")
    @Test
    void lastQuotaRace() throws Exception {
        Long campaignId = fixture.activeCampaign(5);
        List<Long> users = fixture.users(8);
        CouponAdmissionService admission = openAdmission(8);

        List<Object> results = runHeld(facade(queryRepository, campaignRepository, couponRepository, admission), users, campaignId);

        System.out.printf("[coupon-q4] campaign=%d quota=5 users=8 C=8 created=%d soldOut=%d issued=%d coupons=%d releases=%d%n",
            campaignId, count(results, CouponFacadeIntegrationTest::created),
            count(results, r -> rejected(r, ErrorType.COUPON_CAMPAIGN_SOLD_OUT)),
            fixture.issuedCount(campaignId), fixture.couponCount(campaignId), holding.releasedTokens().size());
        assertAll(
            () -> assertThat(count(results, CouponFacadeIntegrationTest::created)).isEqualTo(5),
            () -> assertThat(count(results, r -> rejected(r, ErrorType.COUPON_CAMPAIGN_SOLD_OUT))).isEqualTo(3),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(5),
            () -> assertThat(fixture.couponCount(campaignId)).isEqualTo(5),
            () -> assertThat(holding.releasedTokens()).hasSize(8).doesNotHaveDuplicates(),
            () -> assertThat(master.type(KEY)).isEqualTo(DataType.NONE),
            () -> assertThat(admission.status()).isTrue()
        );
    }

    @DisplayName("Q3: 잔여량이 있을 때 같은 사용자의 두 실행이 모두 사전 조회를 통과하면 둘 다 UPDATE 1 건이고, 유일 제약으로 하나는 기존 결과다.")
    @Test
    void sameUserUniqueConflict() throws Exception {
        Long campaignId = fixture.activeCampaign(5);
        Long user = fixture.user();
        List<Integer> reserves = Collections.synchronizedList(new ArrayList<>());
        CouponAdmissionService admission = openAdmission(2);

        List<Object> results = runHeld(facade(queryRepository, recordingCampaigns(reserves), couponRepository, admission),
            List.of(user, user), campaignId);

        assertSameUserOutcome(results, campaignId, reserves, List.of(1, 1));
    }

    @DisplayName("Q3: 마지막 한 장에서 같은 사용자의 두 실행이 경쟁하면 뒤 실행은 UPDATE 0 건이고 새 읽기에서 기존 결과를 받는다.")
    @Test
    void sameUserLastQuota() throws Exception {
        Long campaignId = fixture.activeCampaign(1);
        Long user = fixture.user();
        List<Integer> reserves = Collections.synchronizedList(new ArrayList<>());
        CouponAdmissionService admission = openAdmission(2);

        List<Object> results = runHeld(facade(queryRepository, recordingCampaigns(reserves), couponRepository, admission),
            List.of(user, user), campaignId);

        assertSameUserOutcome(results, campaignId, reserves, List.of(0, 1));
    }

    private void assertSameUserOutcome(List<Object> results, Long campaignId, List<Integer> reserves, List<Integer> expectedReserves) {
        CouponIssueResult first = (CouponIssueResult) results.stream().filter(CouponFacadeIntegrationTest::created).findFirst().orElseThrow();
        CouponIssueResult second = (CouponIssueResult) results.stream().filter(CouponFacadeIntegrationTest::stored).findFirst().orElseThrow();
        System.out.printf("[coupon-q3] campaign=%d reserveResults=%s createdId=%d storedId=%d issued=%d coupons=%d releases=%d%n",
            campaignId, reserves, first.couponId(), second.couponId(), fixture.issuedCount(campaignId),
            fixture.couponCount(campaignId), holding.releasedTokens().size());
        assertAll(
            () -> assertThat(reserves).containsExactlyInAnyOrderElementsOf(expectedReserves),
            () -> assertThat(second.couponId()).isEqualTo(first.couponId()),
            () -> assertThat(fixture.couponCount(campaignId)).isEqualTo(1),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(1),
            () -> assertThat(holding.releasedTokens()).hasSize(2).doesNotHaveDuplicates(),
            () -> assertThat(master.type(KEY)).isEqualTo(DataType.NONE)
        );
    }

    @DisplayName("Q5: 실제 수량 UPDATE 뒤 저장에서 실패하면 증가분까지 rollback 되고, 종료 뒤 token 을 반환하며 수용은 열려 있다. 다음 요청은 정상 발급된다.")
    @Test
    void insertFailureRollsBackIncrement() {
        Long campaignId = fixture.activeCampaign(5);
        Long user = fixture.user();
        List<Integer> reserves = Collections.synchronizedList(new ArrayList<>());
        CouponAdmissionService admission = openAdmission(3);
        IllegalStateException injected = new IllegalStateException("INSERT 실패 주입");
        CouponRepository failing = coupon -> {
            throw injected;
        };

        assertThatThrownBy(() -> facade(queryRepository, recordingCampaigns(reserves), failing, admission).issue(user, campaignId))
            .isSameAs(injected);
        assertAll(
            () -> assertThat(reserves).containsExactly(1),
            () -> assertThat(fixture.issuedCount(campaignId)).as("증가분 rollback").isZero(),
            () -> assertThat(fixture.couponCount(campaignId)).isZero(),
            () -> assertThat(holding.releasedTokens()).hasSize(1),
            () -> assertThat(master.type(KEY)).isEqualTo(DataType.NONE),
            () -> assertThat(admission.status()).isTrue()
        );

        CouponIssueResult next = facade(queryRepository, campaignRepository, couponRepository, admission).issue(user, campaignId);
        assertAll(
            () -> assertThat(next.created()).isTrue(),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(1)
        );
    }

    @DisplayName("FULL 은 429 이고 그사이 발급됐는지 추가 조회하지 않는다. 사전 조회 한 번 외에 DB 를 건드리지 않고 수용도 닫지 않는다.")
    @Test
    void fullDoesNotQueryAgain() {
        Long campaignId = fixture.activeCampaign(5);
        Long user = fixture.user();
        CouponAdmissionService admission = openAdmission(1);
        assertThat(holding.tryAcquire("coupon-facade-it-" + java.util.UUID.randomUUID(), 1))
            .isEqualTo(CouponAdmissionRepository.Acquisition.ACQUIRED);
        AtomicInteger findOwnedCalls = new AtomicInteger();
        List<Integer> reserves = Collections.synchronizedList(new ArrayList<>());
        CouponQueryRepository countingQuery = new CouponQueryRepository() {
            @Override
            public Optional<OwnedCoupon> findOwned(Long userId, Long campaign) {
                findOwnedCalls.incrementAndGet();
                return queryRepository.findOwned(userId, campaign);
            }

            @Override
            public PageResult<OwnedCoupon> findOwnedPage(Long userId, PageCommand page, ListSort sort) {
                return queryRepository.findOwnedPage(userId, page, sort);
            }
        };

        assertThatThrownBy(() -> facade(countingQuery, recordingCampaigns(reserves), couponRepository, admission).issue(user, campaignId))
            .satisfies(e -> assertThat(((CoreException) e).getErrorType()).isEqualTo(ErrorType.COUPON_ADMISSION_FULL));
        assertAll(
            () -> assertThat(findOwnedCalls).hasValue(1),
            () -> assertThat(reserves).isEmpty(),
            () -> assertThat(fixture.issuedCount(campaignId)).isZero(),
            () -> assertThat(holding.releasedTokens()).as("획득하지 못한 실행은 반환하지 않는다").isEmpty(),
            () -> assertThat(admission.status()).isTrue()
        );
    }

    @DisplayName("닫힌 수용에서는 Redis·쓰기 TX 를 호출하지 않고 503 이지만, 이미 가진 쿠폰은 Redis 와 무관하게 기존 결과를 받는다.")
    @Test
    void closedRejectsNewButKeepsStored() {
        Long campaignId = fixture.activeCampaign(5);
        Long owner = fixture.user();
        Long newcomer = fixture.user();
        CouponAdmissionService admission = openAdmission(3);
        CouponFacade facade = facade(queryRepository, campaignRepository, couponRepository, admission);
        CouponIssueResult first = facade.issue(owner, campaignId);
        admission.close();
        int sentBefore = holding.sentTokens().size();

        CouponIssueResult again = facade.issue(owner, campaignId);

        assertThatThrownBy(() -> facade.issue(newcomer, campaignId))
            .satisfies(e -> assertThat(((CoreException) e).getErrorType()).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE));
        assertAll(
            () -> assertThat(again.created()).isFalse(),
            () -> assertThat(again.couponId()).isEqualTo(first.couponId()),
            () -> assertThat(holding.sentTokens()).as("닫힌 뒤 Redis 미호출").hasSize(sentBefore),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(1),
            () -> assertThat(fixture.couponCount(campaignId, newcomer)).isZero()
        );
    }

    @DisplayName("Q13: 실제 Facade 정상 발급에서 Redis 획득·반환 위임 직전 스레드에 DB TX·바인딩 자원이 없고, 쓰기 구간 SQL 은 조건부 UPDATE → 캠페인 scalar 조회 → INSERT 이며 중복 재조회·캠페인 재UPDATE 가 없다.")
    @Test
    void facadeTransactionBoundariesAndSql() {
        Long campaignId = fixture.activeCampaign(5);
        Long user = fixture.user();
        List<String> redisCalls = Collections.synchronizedList(new ArrayList<>());
        List<List<String>> segments = new ArrayList<>();
        CouponAdmissionRepository observing = new CouponAdmissionRepository() {
            @Override
            public Acquisition tryAcquire(String token, int capacity) {
                redisCalls.add("acquire tx=" + TransactionSynchronizationManager.isActualTransactionActive()
                    + " sync=" + TransactionSynchronizationManager.isSynchronizationActive()
                    + " resources=" + TransactionSynchronizationManager.getResourceMap().size());
                segments.add(SqlCapture.stop()); // 사전 조회 구간
                SqlCapture.start();
                return holding.tryAcquire(token, capacity);
            }

            @Override
            public long release(String token) {
                redisCalls.add("release tx=" + TransactionSynchronizationManager.isActualTransactionActive()
                    + " sync=" + TransactionSynchronizationManager.isSynchronizationActive()
                    + " resources=" + TransactionSynchronizationManager.getResourceMap().size());
                segments.add(SqlCapture.stop()); // 쓰기 구간
                return holding.release(token);
            }
        };
        CouponAdmissionService admission = new CouponAdmissionService(observing, new CouponAdmissionProperties(3));
        admission.open();

        SqlCapture.start();
        CouponIssueResult result = facade(queryRepository, campaignRepository, couponRepository, admission).issue(user, campaignId);

        List<String> preRead = normalize(segments.get(0));
        List<String> write = normalize(segments.get(1));
        System.out.println("[coupon-q13] redisCalls=" + redisCalls);
        preRead.forEach(sql -> System.out.println("[coupon-q13] pre-read: " + sql));
        write.forEach(sql -> System.out.println("[coupon-q13] write: " + sql));

        int update = indexOf(write, "update coupon_campaign set issued_count");
        int scalar = indexOf(write, "from coupon_campaign");
        int insert = indexOf(write, "insert into coupon ");
        assertAll(
            () -> assertThat(result.created()).isTrue(),
            () -> assertThat(redisCalls).containsExactly(
                "acquire tx=false sync=false resources=0", "release tx=false sync=false resources=0"),
            () -> assertThat(preRead).singleElement().asString().contains("from coupon ", "user_id=?", "campaign_id=?"),
            () -> assertThat(update).as("조건부 UPDATE 가 쓰기 구간 첫 SQL").isZero(),
            () -> assertThat(scalar).isGreaterThan(update),
            () -> assertThat(insert).isGreaterThan(scalar),
            () -> assertThat(write.subList(0, insert)).as("INSERT 전 사용자·캠페인 중복 재조회 없음")
                .noneMatch(sql -> sql.contains("from coupon ") && sql.contains("user_id=?")), // merge 의 PK 조회(id=?)는 별개
            () -> assertThat(write).as("캠페인 재UPDATE·쿠폰 UPDATE 없음(stale 덮어쓰기 없음)")
                .filteredOn(sql -> sql.startsWith("update")).hasSize(1),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(1)
        );
    }

    private static List<String> normalize(List<String> statements) {
        return statements.stream().map(sql -> sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim()).toList();
    }

    private static int indexOf(List<String> statements, String fragment) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }
}
