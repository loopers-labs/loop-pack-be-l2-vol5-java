package com.loopers.application.coupon;

import com.loopers.application.coupon.HoldingWaitingRepository.Finish;
import com.loopers.config.CouponAdmissionProperties;
import com.loopers.config.CouponWaitingProperties;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.coupon.CouponAdmissionRepository.AdmissionProgress;
import com.loopers.domain.coupon.CouponCampaignIssueData;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponQueryRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.CouponWaitingState;
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
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실제 MySQL·fixture Redis 컨테이너에서 Facade 의 대기 → 입장 → 발급 경로를 확인한다(설계 §12.9~12.13, Q3·Q4·Q5·Q13 과 WQ3·WQ5·WQ7·WQ10 의 Facade 부분).
 * Facade·수용 서비스는 test 에서 직접 조립하고, fixture 가 만든 campaignId 를 운영 캠페인으로 명시 전달한다. HTTP 는 거치지 않는다.
 * 저장소는 실제 구현이고, STARTED 직후·쓰기 TX 전 붙잡기와 실패 주입만 test decorator 가 맡는다.
 *
 * <p>Q3 의 같은 사용자 UNIQUE 경쟁은 실제 Lua 가 같은 권한의 두 번째 STARTED 를 막으므로 {@link HoldingWaitingRepository#alwaysStarted()}
 * 대역으로 DB 경쟁만 만든다. 실제 자리 상한 근거는 Redis 를 쓰는 다른 사례다.
 *
 * <p>정리 순서: 붙잡기 해제 → worker 종료 확인 → 이 테스트 캠페인의 대기실 키만 삭제 → DB 정리. 종료를 확인하지 못하면 정리를 보류한다.
 * 기본 token 키는 건드리지 않으며 시작·끝에 비어 있는지만 확인한다(대기실 발급이 token 경로를 쓰지 않는다는 확인).
 */
@SpringBootTest(properties = SqlCapture.PROPERTY)
// fixture Redis 주소 주입 등으로 이 클래스만의 컨텍스트가 생긴다. 끝나면 닫아 MySQL 연결 풀을 반납한다(누적 시 Too many connections).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Facade 는 입장 자리를 그대로 써서 실제 DB 경쟁에서 총량·사용자당 한 장을 지키고, 종료 확인 뒤에만 자기 자리를 반환한다.")
class CouponFacadeIntegrationTest {

    private static final Duration DEADLINE = Duration.ofSeconds(20);
    private static final Duration TERMINATION_DEADLINE = Duration.ofSeconds(30);
    private static final String TOKEN_KEY = CouponAdmissionRedisRepository.PERMITS_KEY;

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
    private JdbcTemplate jdbc;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final WorkerTermination workers = new WorkerTermination();
    private final Set<Long> ownCampaigns = new LinkedHashSet<>();
    private final List<HoldingWaitingRepository> repositories = new ArrayList<>();
    private boolean targetVerified;

    @BeforeEach
    void requireFixtureRedis() {
        CouponRedisTarget.verify(redisProperties, master);
        targetVerified = true;
        assertThat(master.type(TOKEN_KEY)).as("시작 전 기본 token 키가 비어 있어야 한다").isEqualTo(DataType.NONE);
    }

    @AfterEach
    void cleanUp() {
        SqlCapture.stop();
        repositories.forEach(HoldingWaitingRepository::stopHolding);
        if (!workers.safeToClean()) {
            throw new IllegalStateException("worker 종료를 확인하지 못해 DB·대기실 키 정리를 보류한다. [campaigns = " + ownCampaigns + "]");
        }
        if (targetVerified) {
            ownCampaigns.forEach(id -> master.delete(CouponAdmissionRedisRepository.waitingKeys(id)));
        }
        databaseCleanUp.truncateAllTables();
        if (targetVerified) {
            assertThat(master.type(TOKEN_KEY)).as("대기실 발급은 기본 token 키를 쓰지 않는다").isEqualTo(DataType.NONE);
        }
    }

    /** 캠페인을 만들고 그 ID 의 대기실 키가 비어 있음을 확인한 뒤 소유로 기록한다. 남은 값은 지우지 않고 실패한다. */
    private Long campaign(long quota) {
        Long id = fixture.activeCampaign(quota);
        for (String key : CouponAdmissionRedisRepository.waitingKeys(id)) {
            assertThat(master.type(key)).as("시작 전 대기실 키가 비어 있어야 한다: " + key).isEqualTo(DataType.NONE);
        }
        ownCampaigns.add(id);
        return id;
    }

    private HoldingWaitingRepository realRedis() {
        HoldingWaitingRepository repository = HoldingWaitingRepository.real(redisRepository);
        repositories.add(repository);
        return repository;
    }

    private CouponAdmissionService openAdmission(HoldingWaitingRepository repository, int capacity, Long campaignId) {
        CouponAdmissionService admission = new CouponAdmissionService(repository, new CouponAdmissionProperties(capacity),
            new CouponWaitingProperties(campaignId, Duration.ofSeconds(1), Duration.ofSeconds(30)));
        admission.open();
        return admission;
    }

    private CouponFacade facade(CouponQueryRepository query, CouponCampaignRepository campaigns,
                                CouponRepository coupons, CouponAdmissionService admission) {
        return new CouponFacade(transactionManager, query, campaigns, coupons, classifier, admission);
    }

    private CouponFacade facade(CouponAdmissionService admission) {
        return facade(queryRepository, campaignRepository, couponRepository, admission);
    }

    /** 등록 순서대로 대기시키고 입장 처리 한 회차로 모두 READY 로 만든다. 사용자 → waitingId. */
    private Map<Long, String> admit(CouponFacade facade, List<Long> users) {
        Map<Long, String> ids = register(facade, users);
        assertThat(facade.processAdmissions()).contains(new AdmissionProgress(0, users.size()));
        return ids;
    }

    private Map<Long, String> register(CouponFacade facade, List<Long> users) {
        Map<Long, String> ids = new LinkedHashMap<>();
        for (Long user : users) {
            CouponWaitingResult result = facade.registerWaiting(user, ownCampaigns.iterator().next());
            assertThat(result.created()).isTrue();
            ids.put(user, result.snapshot().waitingId());
        }
        return ids;
    }

    private Object issueOrError(CouponFacade facade, Long user, Long campaignId, String waitingId) {
        try {
            return facade.issue(user, campaignId, waitingId).result();
        } catch (RuntimeException e) {
            return e;
        }
    }

    /** 각 실행이 STARTED 직후 쓰기 TX 전에 붙잡힌 것을 확인하고 whileHeld 를 실행한 뒤 놓는다. 결과 또는 예외 목록. */
    private List<Object> runHeld(HoldingWaitingRepository repository, CouponFacade facade, Long campaignId,
                                 List<Long> users, List<String> waitingIds, Runnable whileHeld) throws Exception {
        int n = users.size();
        repository.holdNextStarted(n, DEADLINE);
        ExecutorService executor = workers.start(n);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                Long user = users.get(i);
                String waitingId = waitingIds.get(i);
                futures.add(executor.submit(() -> issueOrError(facade, user, campaignId, waitingId)));
            }
            assertThat(repository.awaitHeld(DEADLINE)).as("모든 실행이 STARTED 뒤 쓰기 TX 전에 붙잡혀야 한다").isTrue();
            whileHeld.run();
            repository.letThrough(n);
        } finally {
            repository.stopHolding();
            workers.close(executor, TERMINATION_DEADLINE);
        }
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(0, TimeUnit.MILLISECONDS));
        }
        return results;
    }

    private Set<String> processing(Long campaignId) {
        return master.opsForSet().members("waiting:processing:v1:" + campaignId);
    }

    private long occupancy(Long campaignId) {
        Long ready = master.opsForZSet().size("waiting:ready:v1:" + campaignId);
        Long running = master.opsForSet().size("waiting:processing:v1:" + campaignId);
        return (ready == null ? 0 : ready) + (running == null ? 0 : running);
    }

    private static long count(List<?> values, Predicate<Object> predicate) {
        return values.stream().filter(predicate).count();
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

    private static void assertError(Runnable call, ErrorType expected) {
        assertThatThrownBy(call::run).satisfies(e -> assertThat(((CoreException) e).getErrorType()).isEqualTo(expected));
    }

    private static long finishes(HoldingWaitingRepository repository, CouponWaitingState outcome) {
        return repository.finishes().stream().filter(f -> f.outcome() == outcome).count();
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

    @DisplayName("Q4: 총량 5·서로 다른 8명이 실제 Redis 에서 입장(C=8)·STARTED 뒤 DB 경쟁을 하면 발급 5·품절 3·쿠폰 5·발급 수 5 이고 8명 모두 종료 기록 뒤 점유가 빈다.")
    @Test
    void lastQuotaRace() throws Exception {
        Long campaignId = campaign(5);
        List<Long> users = fixture.users(8);
        HoldingWaitingRepository repository = realRedis();
        CouponAdmissionService admission = openAdmission(repository, 8, campaignId);
        CouponFacade facade = facade(admission);
        Map<Long, String> ids = admit(facade, users);

        List<Object> results = runHeld(repository, facade, campaignId, users, List.copyOf(ids.values()), () -> assertAll(
            () -> assertThat(processing(campaignId)).hasSize(8),
            () -> assertThat(fixture.issuedCount(campaignId)).as("붙잡힌 동안 쓰기 TX 미진입").isZero()
        ));

        System.out.printf("[coupon-q4] campaign=%d quota=5 users=8 C=8 created=%d soldOut=%d issued=%d coupons=%d finishes=%s%n",
            campaignId, count(results, CouponFacadeIntegrationTest::created),
            count(results, r -> rejected(r, ErrorType.COUPON_CAMPAIGN_SOLD_OUT)),
            fixture.issuedCount(campaignId), fixture.couponCount(campaignId), repository.finishes());
        assertAll(
            () -> assertThat(count(results, CouponFacadeIntegrationTest::created)).isEqualTo(5),
            () -> assertThat(count(results, r -> rejected(r, ErrorType.COUPON_CAMPAIGN_SOLD_OUT))).isEqualTo(3),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(5),
            () -> assertThat(fixture.couponCount(campaignId)).isEqualTo(5),
            () -> assertThat(finishes(repository, CouponWaitingState.ISSUED)).isEqualTo(5),
            () -> assertThat(finishes(repository, CouponWaitingState.FAILED)).isEqualTo(3),
            () -> assertThat(repository.finishes()).extracting(Finish::waitingId).doesNotHaveDuplicates().hasSize(8),
            () -> assertThat(occupancy(campaignId)).isZero(),
            () -> assertThat(admission.status()).isTrue()
        );
    }

    @DisplayName("Q3: 잔여량이 있을 때 같은 사용자의 두 실행이 모두 사전 조회를 통과하면 둘 다 UPDATE 1 건이고, 유일 제약으로 하나는 기존 결과다(STARTED 대역).")
    @Test
    void sameUserUniqueConflict() throws Exception {
        Long campaignId = campaign(5);
        Long user = fixture.user();
        List<Integer> reserves = Collections.synchronizedList(new ArrayList<>());
        HoldingWaitingRepository stub = HoldingWaitingRepository.alwaysStarted();
        repositories.add(stub);
        CouponFacade facade = facade(queryRepository, recordingCampaigns(reserves), couponRepository, openAdmission(stub, 2, campaignId));

        List<Object> results = runHeld(stub, facade, campaignId, List.of(user, user), List.of("w-a", "w-b"), () -> { });

        assertSameUserOutcome(results, campaignId, reserves, List.of(1, 1), stub);
    }

    @DisplayName("Q3: 마지막 한 장에서 같은 사용자의 두 실행이 경쟁하면 뒤 실행은 UPDATE 0 건이고 새 읽기에서 기존 결과를 받는다(STARTED 대역).")
    @Test
    void sameUserLastQuota() throws Exception {
        Long campaignId = campaign(1);
        Long user = fixture.user();
        List<Integer> reserves = Collections.synchronizedList(new ArrayList<>());
        HoldingWaitingRepository stub = HoldingWaitingRepository.alwaysStarted();
        repositories.add(stub);
        CouponFacade facade = facade(queryRepository, recordingCampaigns(reserves), couponRepository, openAdmission(stub, 2, campaignId));

        List<Object> results = runHeld(stub, facade, campaignId, List.of(user, user), List.of("w-a", "w-b"), () -> { });

        assertSameUserOutcome(results, campaignId, reserves, List.of(0, 1), stub);
    }

    private void assertSameUserOutcome(List<Object> results, Long campaignId, List<Integer> reserves, List<Integer> expectedReserves,
                                       HoldingWaitingRepository stub) {
        CouponIssueResult first = (CouponIssueResult) results.stream().filter(CouponFacadeIntegrationTest::created).findFirst().orElseThrow();
        CouponIssueResult second = (CouponIssueResult) results.stream().filter(CouponFacadeIntegrationTest::stored).findFirst().orElseThrow();
        System.out.printf("[coupon-q3] campaign=%d reserveResults=%s createdId=%d storedId=%d issued=%d coupons=%d finishes=%s%n",
            campaignId, reserves, first.couponId(), second.couponId(), fixture.issuedCount(campaignId),
            fixture.couponCount(campaignId), stub.finishes());
        assertAll(
            () -> assertThat(reserves).containsExactlyInAnyOrderElementsOf(expectedReserves),
            () -> assertThat(second.couponId()).isEqualTo(first.couponId()),
            () -> assertThat(fixture.couponCount(campaignId)).isEqualTo(1),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(1),
            () -> assertThat(finishes(stub, CouponWaitingState.ISSUED)).as("기존 결과도 쿠폰 결과라 ISSUED").isEqualTo(2)
        );
    }

    @DisplayName("Q5: 실제 수량 UPDATE 뒤 저장에서 실패하면 증가분까지 rollback 되고 finish(FAILED)로 자리를 반환한다. 같은 권한은 ALREADY_USED, 새 등록은 맨 뒤에서 정상 발급된다.")
    @Test
    void insertFailureRollsBackIncrement() {
        Long campaignId = campaign(5);
        Long user = fixture.user();
        List<Integer> reserves = Collections.synchronizedList(new ArrayList<>());
        HoldingWaitingRepository repository = realRedis();
        CouponAdmissionService admission = openAdmission(repository, 3, campaignId);
        CouponFacade facade = facade(admission);
        String waitingId = admit(facade, List.of(user)).get(user);
        IllegalStateException injected = new IllegalStateException("INSERT 실패 주입");
        CouponRepository failing = coupon -> {
            throw injected;
        };

        assertThatThrownBy(() -> facade(queryRepository, recordingCampaigns(reserves), failing, admission).issue(user, campaignId, waitingId))
            .isSameAs(injected);
        assertAll(
            () -> assertThat(reserves).containsExactly(1),
            () -> assertThat(fixture.issuedCount(campaignId)).as("증가분 rollback").isZero(),
            () -> assertThat(fixture.couponCount(campaignId)).isZero(),
            () -> assertThat(repository.finishes()).containsExactly(new Finish(user, waitingId, CouponWaitingState.FAILED)),
            () -> assertThat(occupancy(campaignId)).isZero(),
            () -> assertThat(admission.status()).isTrue()
        );
        assertError(() -> facade.issue(user, campaignId, waitingId), ErrorType.COUPON_WAITING_ALREADY_USED);

        CouponWaitingResult renewed = facade.registerWaiting(user, campaignId);
        assertThat(renewed.created()).isTrue();
        assertThat(facade.processAdmissions()).contains(new AdmissionProgress(0, 1));
        CouponIssueResult next = facade.issue(user, campaignId, renewed.snapshot().waitingId()).result();
        assertAll(
            () -> assertThat(next.created()).isTrue(),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(1),
            () -> assertThat(occupancy(campaignId)).isZero()
        );
    }

    @DisplayName("READY 뒤 캠페인이 비활성이 되면 발급은 DB 에서 409 INACTIVE·FAILED 이고 쿠폰·발급 수 효과가 없으며, 같은 권한은 ALREADY_USED 다.")
    @Test
    void inactiveAfterReady() {
        Long campaignId = campaign(5);
        Long user = fixture.user();
        HoldingWaitingRepository repository = realRedis();
        CouponFacade facade = facade(openAdmission(repository, 3, campaignId));
        String waitingId = admit(facade, List.of(user)).get(user);
        jdbc.update("UPDATE coupon_campaign SET status = 'INACTIVE' WHERE id = ?", campaignId);

        assertError(() -> facade.issue(user, campaignId, waitingId), ErrorType.COUPON_CAMPAIGN_INACTIVE);
        assertAll(
            () -> assertThat(repository.finishes()).containsExactly(new Finish(user, waitingId, CouponWaitingState.FAILED)),
            () -> assertThat(fixture.issuedCount(campaignId)).isZero(),
            () -> assertThat(fixture.couponCount(campaignId)).isZero(),
            () -> assertThat(occupancy(campaignId)).isZero()
        );
        assertError(() -> facade.issue(user, campaignId, waitingId), ErrorType.COUPON_WAITING_ALREADY_USED);
    }

    @DisplayName("WQ10: READY 두 명 중 한 명이 마지막 수량을 받으면 다른 한 명은 DB 에서 409 품절·FAILED 이고, 품절 회차 뒤 남은 대기자는 SOLD_OUT 으로 보인다.")
    @Test
    void soldOutAfterReady() {
        Long campaignId = campaign(1);
        List<Long> users = fixture.users(3);
        HoldingWaitingRepository repository = realRedis();
        CouponFacade facade = facade(openAdmission(repository, 2, campaignId));
        Map<Long, String> ids = register(facade, users);
        assertThat(facade.processAdmissions()).contains(new AdmissionProgress(0, 2));

        CouponIssueResult first = facade.issue(users.get(0), campaignId, ids.get(users.get(0))).result();
        assertError(() -> facade.issue(users.get(1), campaignId, ids.get(users.get(1))), ErrorType.COUPON_CAMPAIGN_SOLD_OUT);
        assertThat(facade.processAdmissions()).as("DB 품절 전달: 회수·승격 없음").contains(new AdmissionProgress(0, 0));
        assertAll(
            () -> assertThat(first.created()).isTrue(),
            () -> assertThat(finishes(repository, CouponWaitingState.FAILED)).isEqualTo(1),
            () -> assertThat(fixture.couponCount(campaignId)).isEqualTo(1),
            () -> assertThat(facade.getWaiting(users.get(2), campaignId).snapshot().status()).isEqualTo(CouponWaitingState.SOLD_OUT),
            () -> assertThat(facade.getWaiting(users.get(0), campaignId).snapshot().couponId()).as("기존 결과 우선").isEqualTo(first.couponId()),
            () -> assertError(() -> facade.issue(users.get(1), campaignId, ids.get(users.get(1))), ErrorType.COUPON_WAITING_ALREADY_USED),
            () -> assertError(() -> facade.registerWaiting(fixture.user(), campaignId), ErrorType.COUPON_CAMPAIGN_SOLD_OUT)
        );
    }

    @DisplayName("WQ3: 실제 C=3 에서 4명이면 넷째는 WAITING·발급 시도 409 NOT_READY(추가 DB 조회·쓰기 없음)이고, 앞 3명 종료 뒤 다음 회차에 승격돼 발급된다.")
    @Test
    void realCapacityThenReassign() {
        Long campaignId = campaign(10);
        List<Long> users = fixture.users(4);
        HoldingWaitingRepository repository = realRedis();
        CouponAdmissionService admission = openAdmission(repository, 3, campaignId);
        Map<Long, String> ids = register(facade(admission), users);
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
        CouponFacade facade = facade(countingQuery, recordingCampaigns(reserves), couponRepository, admission);

        assertThat(facade.processAdmissions()).contains(new AdmissionProgress(0, 3));
        Long fourth = users.get(3);
        assertAll(
            () -> assertThat(occupancy(campaignId)).isEqualTo(3),
            () -> assertThat(facade.getWaiting(fourth, campaignId).snapshot().position()).isEqualTo(1L)
        );
        findOwnedCalls.set(0);
        assertError(() -> facade.issue(fourth, campaignId, ids.get(fourth)), ErrorType.COUPON_WAITING_NOT_READY);
        assertAll(
            () -> assertThat(findOwnedCalls).as("기존 결과 사전 조회 한 번뿐").hasValue(1),
            () -> assertThat(reserves).isEmpty(),
            () -> assertThat(admission.status()).isTrue()
        );

        for (Long user : users.subList(0, 3)) {
            assertThat(facade.issue(user, campaignId, ids.get(user)).result().created()).isTrue();
            assertThat(occupancy(campaignId)).isLessThanOrEqualTo(3);
        }
        assertThat(occupancy(campaignId)).isZero();
        assertThat(facade.processAdmissions()).contains(new AdmissionProgress(0, 1));
        assertAll(
            () -> assertThat(facade.issue(fourth, campaignId, ids.get(fourth)).result().created()).isTrue(),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(4),
            () -> assertThat(occupancy(campaignId)).isZero()
        );
    }

    @DisplayName("WQ5: 같은 권한이 진행 중이면 두 번째 발급은 DB 쓰기 없이 PROCESSING 결과이고, 첫 실행은 그대로 발급된다.")
    @Test
    void sameWaitingWhileProcessing() throws Exception {
        Long campaignId = campaign(5);
        Long user = fixture.user();
        List<Integer> reserves = Collections.synchronizedList(new ArrayList<>());
        HoldingWaitingRepository repository = realRedis();
        CouponFacade facade = facade(queryRepository, recordingCampaigns(reserves), couponRepository, openAdmission(repository, 3, campaignId));
        String waitingId = admit(facade, List.of(user)).get(user);
        List<CouponIssueOutcome> duplicate = new ArrayList<>();

        List<Object> results = runHeld(repository, facade, campaignId, List.of(user), List.of(waitingId),
            () -> duplicate.add(facade.issue(user, campaignId, waitingId)));

        assertAll(
            () -> assertThat(duplicate.get(0).result()).isNull(),
            () -> assertThat(duplicate.get(0).processing().status()).isEqualTo(CouponWaitingState.PROCESSING),
            () -> assertThat(results).singleElement().matches(CouponFacadeIntegrationTest::created),
            () -> assertThat(reserves).containsExactly(1),
            () -> assertThat(repository.starts()).containsExactly(waitingId + "=STARTED", waitingId + "=PROCESSING"),
            () -> assertThat(repository.finishes()).containsExactly(new Finish(user, waitingId, CouponWaitingState.ISSUED))
        );
    }

    @DisplayName("close 는 새 진입만 막는다. 붙잡힌 진행 중 발급은 끝까지 가서 finish 로 자리를 반환하고, 수용은 닫힌 채로 남는다.")
    @Test
    void closeDuringProcessingStillFinishes() throws Exception {
        Long campaignId = campaign(5);
        Long user = fixture.user();
        HoldingWaitingRepository repository = realRedis();
        CouponAdmissionService admission = openAdmission(repository, 3, campaignId);
        CouponFacade facade = facade(admission);
        String waitingId = admit(facade, List.of(user)).get(user);

        List<Object> results = runHeld(repository, facade, campaignId, List.of(user), List.of(waitingId), admission::close);

        assertAll(
            () -> assertThat(results).singleElement().matches(CouponFacadeIntegrationTest::created),
            () -> assertThat(repository.finishes()).containsExactly(new Finish(user, waitingId, CouponWaitingState.ISSUED)),
            () -> assertThat(occupancy(campaignId)).isZero(),
            () -> assertThat(admission.status()).isFalse()
        );
    }

    @DisplayName("닫힌 수용에서 신규 발급·등록은 Redis 없이 503 이지만, 이미 가진 쿠폰은 헤더 없이 기존 결과이고 대기 조회는 막지 않는다.")
    @Test
    void closedRejectsNewButKeepsStoredAndQuery() {
        Long campaignId = campaign(5);
        List<Long> users = fixture.users(2);
        Long owner = users.get(0);
        Long newcomer = users.get(1);
        HoldingWaitingRepository repository = realRedis();
        CouponAdmissionService admission = openAdmission(repository, 3, campaignId);
        CouponFacade facade = facade(admission);
        Map<Long, String> ids = admit(facade, users);
        CouponIssueResult first = facade.issue(owner, campaignId, ids.get(owner)).result();
        admission.close();
        int startsBefore = repository.starts().size();

        CouponIssueResult again = facade.issue(owner, campaignId, null).result();

        assertError(() -> facade.issue(newcomer, campaignId, ids.get(newcomer)), ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        assertError(() -> facade.registerWaiting(fixture.user(), campaignId), ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        assertAll(
            () -> assertThat(again.created()).isFalse(),
            () -> assertThat(again.couponId()).isEqualTo(first.couponId()),
            () -> assertThat(repository.starts()).as("닫힌 뒤 start 미호출").hasSize(startsBefore),
            () -> assertThat(facade.getWaiting(newcomer, campaignId).snapshot().status()).isEqualTo(CouponWaitingState.READY),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(1),
            () -> assertThat(fixture.couponCount(campaignId, newcomer)).isZero()
        );
    }

    @DisplayName("Q13: 실제 Facade 정상 발급에서 Redis start·finish 위임 직전 스레드에 DB TX·바인딩 자원이 없고, 쓰기 구간 SQL 은 조건부 UPDATE → 캠페인 scalar 조회 → INSERT 이며 중복 재조회·캠페인 재UPDATE 가 없다.")
    @Test
    void facadeTransactionBoundariesAndSql() {
        Long campaignId = campaign(5);
        Long user = fixture.user();
        HoldingWaitingRepository repository = realRedis();
        CouponFacade facade = facade(openAdmission(repository, 3, campaignId));
        String waitingId = admit(facade, List.of(user)).get(user);
        List<String> redisCalls = Collections.synchronizedList(new ArrayList<>());
        List<List<String>> segments = new ArrayList<>();
        repository.beforeStartOrFinish(stage -> {
            redisCalls.add(stage + " tx=" + TransactionSynchronizationManager.isActualTransactionActive()
                + " sync=" + TransactionSynchronizationManager.isSynchronizationActive()
                + " resources=" + TransactionSynchronizationManager.getResourceMap().size());
            segments.add(SqlCapture.stop()); // start 전: 사전 조회 구간, finish 전: 쓰기 구간
            SqlCapture.start();
        });

        SqlCapture.start();
        CouponIssueResult result = facade.issue(user, campaignId, waitingId).result();

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
                "start tx=false sync=false resources=0", "finish tx=false sync=false resources=0"),
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
