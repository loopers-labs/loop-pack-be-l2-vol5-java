package com.loopers.experiment;

import com.loopers.application.coupon.AdmissionPermits;
import com.loopers.application.coupon.CouponAdmission;
import com.loopers.application.coupon.CouponCampaignSetup;
import com.loopers.application.coupon.CouponIssueFacade;
import com.loopers.application.coupon.CouponIssueResult;
import com.loopers.application.coupon.CouponIssueResult.Outcome;
import com.loopers.application.coupon.CouponIssueTransaction;
import com.loopers.application.coupon.CouponIssuer;
import com.loopers.application.coupon.CouponQuery;
import com.loopers.application.coupon.IssuedCouponInfo;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.IssuedCoupon;
import com.loopers.domain.coupon.IssuedCouponRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 기존 쿠폰 조회(사전 확인 SELECT)를 자리 획득 앞에 두는 순서(A)와 뒤에 두는 순서(B)를 같은 부하로 비교하는 실험
 * (docs/week8/decisions.md D-2, D-3, D-8, D-16).
 *
 * <ul>
 *   <li>A = 제품의 {@link CouponIssueFacade}: findIssued → 자리 획득 → 발급 → 반환. 429 재시도마다 DB SELECT가 한 번 나간다.</li>
 *   <li>B = 이 테스트 안에서만 만든 순서: 자리 획득 → findIssued → 있으면 EXISTING, 없으면 발급 → 반환. 429·503이면 DB에 닿지 않는다.
 *       제품 코드는 A 그대로다.</li>
 *   <li>사전 확인 SELECT 수는 사전 확인 전용 {@link CouponQuery} 인스턴스가 쓰는 저장소를 감싸 센다. 발급 TX 안의 기존 쿠폰 조회는
 *       다른 저장소(실제 빈)를 쓰므로 세지 않는다. 테스트에서 만든 CouponQuery·CouponIssueTransaction은 빈과 같은
 *       {@code @Transactional} 해석으로 감싼다 — 빈을 바꾸지 않으므로 다른 테스트와 Spring 컨텍스트를 같이 쓴다.</li>
 *   <li>C=3, client 32개 닫힌 루프, 429면 같은 요청을 약 1ms 뒤 다시 보낸다.</li>
 *   <li>open: 새 사용자 N명만. mixed: 새 사용자 N명 + 이미 받은 사용자 M명을 번갈아 보낸다. mixed 캠페인은 한 번 만들어 M명을
 *       미리 발급해 두고, 회차마다 새 사용자 번호 범위를 바꾼다(미리 발급을 회차마다 반복하지 않으려고).</li>
 *   <li>단언은 불변식만 한다. 속도는 단언하지 않고 표로만 남긴다. 표는 ASCII로 쓴다(Windows 콘솔 한글 깨짐).</li>
 * </ul>
 */
@SpringBootTest
class CouponPrecheckOrderExperimentTest {

    private static final Logger log = LoggerFactory.getLogger(CouponPrecheckOrderExperimentTest.class);

    private static final int CAPACITY = 3;
    private static final int CLIENT_THREADS = 32;
    private static final int NEW_USERS = 40;
    private static final int EXISTING_USERS = 40;
    private static final int WARM_UP_USERS = 20;
    private static final int REPEATS = 2;
    private static final long RETRY_PAUSE_MILLIS = 1;
    private static final long ROUND_TIMEOUT_SECONDS = 60;
    private static final long EXISTING_USER_BASE = 1_000_000L;
    private static final long NEW_USER_RANGE = 10_000L;
    private static final double NANOS_PER_MILLI = 1_000_000.0;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final double P95 = 0.95;

    @Autowired
    private CouponQuery couponQuery;

    @Autowired
    private CouponIssuer couponIssuer;

    @Autowired
    private CouponCampaignRepository couponCampaignRepository;

    @Autowired
    private IssuedCouponRepository issuedCouponRepository;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AdmissionPermits admissionPermits;

    @Autowired
    private CouponCampaignSetup couponCampaignSetup;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private long nextNewUserBase = 0;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("사전 확인 순서 A(조회 → 자리)와 B(자리 → 조회)를 open·mixed 부하로 재고, 매 회차 쿠폰 수 = 발급 수이며 장부가 비고 기존 사용자는 원래 쿠폰을 받는다.")
    @Test
    void compareOrders() throws Exception {
        for (Order order : Order.values()) {
            Long warmUpCampaign = createCampaign("warm-up-" + order, WARM_UP_USERS);
            runRound(order, "warm-up", warmUpCampaign, newUsers(WARM_UP_USERS), Map.of());
        }

        Long mixedCampaign = createCampaign("mixed", EXISTING_USERS + NEW_USERS * REPEATS * Order.values().length);
        Map<Long, Long> existingCoupons = preIssue(mixedCampaign);

        List<RoundResult> results = new ArrayList<>();
        for (int run = 1; run <= REPEATS; run++) {
            for (Order order : Order.values()) {
                Long openCampaign = createCampaign("open-" + order + "-" + run, NEW_USERS);
                results.add(runRound(order, "open", openCampaign, newUsers(NEW_USERS), Map.of()).withRun(run));
            }
            for (Order order : Order.values()) {
                results.add(runRound(order, "mixed", mixedCampaign, newUsers(NEW_USERS), existingCoupons).withRun(run));
            }
        }

        assertThat(couponRows(mixedCampaign)).isEqualTo(EXISTING_USERS + NEW_USERS * REPEATS * Order.values().length);
        log.info(table(results));
    }

    private RoundResult runRound(Order order, String scenario, Long campaignId, List<Long> newUserIds,
                                 Map<Long, Long> existingCoupons) throws Exception {
        TimedPermits timedPermits = new TimedPermits(admissionPermits);
        CouponAdmission admission = new CouponAdmission(timedPermits, CAPACITY);
        admission.open();
        CountingIssuedCouponRepository countingRepository = new CountingIssuedCouponRepository(issuedCouponRepository);
        CouponQuery precheckQuery = transactional(new CouponQuery(countingRepository));
        ConcurrentLinkedQueue<Long> lockHeldNanos = new ConcurrentLinkedQueue<>();
        CouponIssueTransaction issueTransaction = transactional(new CouponIssueTransaction(
            new LockTimedCampaignRepository(couponCampaignRepository, lockHeldNanos), issuedCouponRepository, clock));
        CouponIssuer issuer = new CouponIssuer(issueTransaction, couponQuery);
        IssueFlow flow = order == Order.A
            ? new CouponIssueFacade(precheckQuery, admission, issuer)::issue
            : new SeatFirstFlow(precheckQuery, admission, issuer)::issue;

        List<Request> requests = interleave(newUserIds, new ArrayList<>(existingCoupons.keySet()));
        int rowsBefore = couponRows(campaignId);
        int issuedBefore = issuedCount(campaignId);

        AtomicInteger next = new AtomicInteger(0);
        AtomicLong rejected = new AtomicLong(0);
        Map<Long, CouponIssueResult> outcomes = new ConcurrentHashMap<>();
        ConcurrentLinkedQueue<Long> existingLatencyNanos = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newFixedThreadPool(CLIENT_THREADS);
        List<Future<?>> workers = new ArrayList<>();
        long startedAt = System.nanoTime();
        try {
            for (int i = 0; i < CLIENT_THREADS; i++) {
                workers.add(executor.submit(() -> {
                    int index;
                    while ((index = next.getAndIncrement()) < requests.size()) {
                        Request request = requests.get(index);
                        long sentAt = System.nanoTime();
                        CouponIssueResult result = sendUntilAdmitted(flow, campaignId, request.userId, rejected, unexpected);
                        if (result == null) {
                            return;
                        }
                        outcomes.put(request.userId, result);
                        if (request.existing) {
                            existingLatencyNanos.add(System.nanoTime() - sentAt);
                        }
                    }
                }));
            }
            for (Future<?> worker : workers) {
                worker.get(ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        long elapsedNanos = System.nanoTime() - startedAt;

        Set<String> leftover = admissionPermits.holders();
        assertAll(
            () -> assertThat(unexpected).isEmpty(),
            () -> assertThat(outcomes).hasSize(requests.size()),
            () -> assertThat(newUserIds).allSatisfy(userId ->
                assertThat(outcomes.get(userId).outcome()).isEqualTo(Outcome.CREATED)),
            () -> assertThat(existingCoupons).allSatisfy((userId, couponId) -> {
                assertThat(outcomes.get(userId).outcome()).isEqualTo(Outcome.EXISTING);
                assertThat(outcomes.get(userId).coupon().couponId()).isEqualTo(couponId);
            }),
            () -> assertThat(couponRows(campaignId) - rowsBefore).isEqualTo(newUserIds.size()),
            () -> assertThat(issuedCount(campaignId) - issuedBefore).isEqualTo(newUserIds.size()),
            () -> assertThat(couponRows(campaignId)).isEqualTo(issuedCount(campaignId)),
            () -> assertThat(leftover).isEmpty(),
            () -> assertThat(lockHeldNanos).hasSize(newUserIds.size())
        );

        return new RoundResult(order, scenario, 0, newUserIds.size(), existingCoupons.size(), elapsedNanos, rejected.get(),
            countingRepository.calls.get(), new ArrayList<>(timedPermits.holdNanos), new ArrayList<>(lockHeldNanos),
            new ArrayList<>(existingLatencyNanos));
    }

    /** 결과를 받을 때까지 같은 요청을 다시 보낸다. 429는 세고 다시 보낸다. 다른 예외는 모아 두고 null(이 스레드를 멈춘다). */
    private static CouponIssueResult sendUntilAdmitted(IssueFlow flow, Long campaignId, long userId, AtomicLong rejected,
                                                       ConcurrentLinkedQueue<Throwable> unexpected) {
        while (true) {
            try {
                return flow.issue(campaignId, userId);
            } catch (CoreException e) {
                if (e.getErrorType() != ErrorType.TOO_MANY_REQUESTS) {
                    unexpected.add(e);
                    return null;
                }
                rejected.incrementAndGet();
                try {
                    Thread.sleep(RETRY_PAUSE_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    unexpected.add(interrupted);
                    return null;
                }
            } catch (RuntimeException e) {
                unexpected.add(e);
                return null;
            }
        }
    }

    /** 이미 받은 사용자 M명을 시간 재기 전에 실제 발급 경로로 발급해 두고 userId → couponId를 돌려준다. */
    private Map<Long, Long> preIssue(Long campaignId) throws Exception {
        Map<Long, Long> coupons = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(CAPACITY);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 1; i <= EXISTING_USERS; i++) {
                long userId = EXISTING_USER_BASE + i;
                futures.add(executor.submit(() -> {
                    CouponIssueResult result = couponIssuer.issue(campaignId, userId);
                    coupons.put(userId, result.coupon().couponId());
                }));
            }
            for (Future<?> future : futures) {
                future.get(ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        assertThat(coupons).hasSize(EXISTING_USERS);
        return new HashMap<>(coupons);
    }

    private List<Long> newUsers(int count) {
        nextNewUserBase += NEW_USER_RANGE;
        List<Long> userIds = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            userIds.add(nextNewUserBase + i);
        }
        return userIds;
    }

    /** 새 사용자와 기존 사용자를 번갈아 놓는다. 한쪽이 남으면 뒤에 붙인다. */
    private static List<Request> interleave(List<Long> newUserIds, List<Long> existingUserIds) {
        List<Request> requests = new ArrayList<>();
        int size = Math.max(newUserIds.size(), existingUserIds.size());
        for (int i = 0; i < size; i++) {
            if (i < newUserIds.size()) {
                requests.add(new Request(newUserIds.get(i), false));
            }
            if (i < existingUserIds.size()) {
                requests.add(new Request(existingUserIds.get(i), true));
            }
        }
        return requests;
    }

    private Long createCampaign(String name, int quota) {
        return couponCampaignSetup.create(name, quota, "5,000 off", 5_000L).campaignId();
    }

    /** 빈과 같은 @Transactional 해석(애너테이션 + 같은 트랜잭션 매니저)으로 테스트에서 만든 인스턴스를 감싼다. */
    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (T) proxyFactory.getProxy();
    }

    private int issuedCount(Long campaignId) {
        Integer value = jdbcTemplate.queryForObject(
            "SELECT issued_count FROM coupon_campaign WHERE id = ?", Integer.class, campaignId);
        return value == null ? -1 : value;
    }

    private int couponRows(Long campaignId) {
        Integer value = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM issued_coupon WHERE campaign_id = ?", Integer.class, campaignId);
        return value == null ? -1 : value;
    }

    private static String table(List<RoundResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("Pre-check order A (findIssued -> seat) vs B (seat -> findIssued), C=" + CAPACITY + ", clients="
            + CLIENT_THREADS + " (times in ms; created/s = new issuances per second; selects = pre-check findIssued SELECTs; "
            + "exist_lat = existing-user request incl. 429 retries)");
        sb.append(String.format("%n%-3s %-6s %3s %4s %4s %8s %10s %6s %8s %10s %10s %10s %10s %10s%n",
            "ord", "scen", "run", "new", "old", "wall_ms", "created/s", "429s", "selects", "hold_avg", "hold_p95",
            "lock_avg", "exist_avg", "exist_p95"));
        for (RoundResult r : results) {
            sb.append(String.format("%-3s %-6s %3d %4d %4d %8.0f %10.1f %6d %8d %10.2f %10.2f %10.2f %10s %10s%n",
                r.order, r.scenario, r.run, r.newUsers, r.existingUsers, r.elapsedNanos / NANOS_PER_MILLI,
                r.newUsers / (r.elapsedNanos / NANOS_PER_SECOND), r.rejected, r.selects,
                avgMillis(r.holdNanos), p95Millis(r.holdNanos), avgMillis(r.lockHeldNanos),
                r.existingLatencyNanos.isEmpty() ? "-" : String.format("%.2f", avgMillis(r.existingLatencyNanos)),
                r.existingLatencyNanos.isEmpty() ? "-" : String.format("%.2f", p95Millis(r.existingLatencyNanos))));
        }
        return sb.toString();
    }

    private static double avgMillis(List<Long> nanos) {
        return nanos.stream().mapToLong(Long::longValue).average().orElse(0) / NANOS_PER_MILLI;
    }

    private static double p95Millis(List<Long> nanos) {
        if (nanos.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        int index = (int) Math.ceil(P95 * sorted.size()) - 1;
        return sorted.get(Math.max(index, 0)) / NANOS_PER_MILLI;
    }

    private enum Order { A, B }

    @FunctionalInterface
    private interface IssueFlow {
        CouponIssueResult issue(Long campaignId, Long userId);
    }

    private record Request(long userId, boolean existing) {
    }

    /** 순서 B: 자리를 먼저 잡고, 그 안에서 기존 쿠폰을 확인한다. 이 테스트에서만 쓴다. */
    private static final class SeatFirstFlow {

        private final CouponQuery query;
        private final CouponAdmission admission;
        private final CouponIssuer issuer;

        private SeatFirstFlow(CouponQuery query, CouponAdmission admission, CouponIssuer issuer) {
            this.query = query;
            this.admission = admission;
            this.issuer = issuer;
        }

        CouponIssueResult issue(Long campaignId, Long userId) {
            String token = admission.acquire();
            try {
                Optional<IssuedCouponInfo> existing = query.findIssued(campaignId, userId);
                if (existing.isPresent()) {
                    return CouponIssueResult.existing(existing.get());
                }
                return issuer.issue(campaignId, userId);
            } finally {
                admission.release(token);
            }
        }
    }

    /** 사전 확인 SELECT(findByCampaignIdAndUserId) 수를 센다. 측정 전용이다. */
    private static final class CountingIssuedCouponRepository implements IssuedCouponRepository {

        private final IssuedCouponRepository delegate;
        private final AtomicLong calls = new AtomicLong(0);

        private CountingIssuedCouponRepository(IssuedCouponRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public IssuedCoupon save(IssuedCoupon coupon) {
            return delegate.save(coupon);
        }

        @Override
        public Optional<IssuedCoupon> findByCampaignIdAndUserId(Long campaignId, Long userId) {
            calls.incrementAndGet();
            return delegate.findByCampaignIdAndUserId(campaignId, userId);
        }

        @Override
        public List<IssuedCoupon> findAllByUserId(Long userId) {
            return delegate.findAllByUserId(userId);
        }
    }

    /** FOR UPDATE가 돌아온 뒤부터 이 TX가 commit으로 끝날 때까지(락을 잡은 시간)를 잰다. 측정 전용이다. */
    private static final class LockTimedCampaignRepository implements CouponCampaignRepository {

        private final CouponCampaignRepository delegate;
        private final ConcurrentLinkedQueue<Long> lockHeldNanos;

        private LockTimedCampaignRepository(CouponCampaignRepository delegate, ConcurrentLinkedQueue<Long> lockHeldNanos) {
            this.delegate = delegate;
            this.lockHeldNanos = lockHeldNanos;
        }

        @Override
        public CouponCampaign save(CouponCampaign campaign) {
            return delegate.save(campaign);
        }

        @Override
        public Optional<CouponCampaign> findById(Long id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<CouponCampaign> findByIdForUpdate(Long id) {
            Optional<CouponCampaign> campaign = delegate.findByIdForUpdate(id);
            long lockedAt = System.nanoTime();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) {
                        lockHeldNanos.add(System.nanoTime() - lockedAt);
                    }
                }
            });
            return campaign;
        }
    }

    /** 장부 포트를 감싸 자리 점유 시간(tryAcquire 성공 직후 → release 완료)을 잰다. 측정 전용이다. */
    private static final class TimedPermits implements AdmissionPermits {

        private final AdmissionPermits delegate;
        private final Map<String, Long> acquiredAt = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> holdNanos = new ConcurrentLinkedQueue<>();

        private TimedPermits(AdmissionPermits delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean tryAcquire(String token, int capacity) {
            boolean admitted = delegate.tryAcquire(token, capacity);
            if (admitted) {
                acquiredAt.put(token, System.nanoTime());
            }
            return admitted;
        }

        @Override
        public void release(String token) {
            delegate.release(token);
            Long start = acquiredAt.remove(token);
            if (start != null) {
                holdNanos.add(System.nanoTime() - start);
            }
        }

        @Override
        public Set<String> holders() {
            return delegate.holders();
        }
    }

    private record RoundResult(Order order, String scenario, int run, int newUsers, int existingUsers, long elapsedNanos,
                               long rejected, long selects, List<Long> holdNanos, List<Long> lockHeldNanos,
                               List<Long> existingLatencyNanos) {

        RoundResult withRun(int runNumber) {
            return new RoundResult(order, scenario, runNumber, newUsers, existingUsers, elapsedNanos, rejected, selects,
                holdNanos, lockHeldNanos, existingLatencyNanos);
        }
    }
}
