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
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
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
import java.util.function.ToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 수용량 C를 바꿀 때 쿠폰 발급 처리량이 어떻게 변하는지 재고, Little의 법칙(처리량 = C / 자리 점유 시간 평균)과 비교하는 실험
 * (docs/week8/decisions.md D-3, D-8, D-16).
 *
 * <p>가설(검증 대상이지 전제가 아니다): 모든 발급이 같은 캠페인 행을 {@code FOR UPDATE}로 잡으므로 C를 올려도 처리량은 거의 그대로이고,
 * 늘어난 C는 대부분 락 대기(자리 점유 시간 증가)로 바뀐다.
 *
 * <ul>
 *   <li>C마다 새 {@link CouponAdmission}(실제 Redis 장부)을 만들어 열고, 실제 {@link CouponQuery}와 시간을 재는 {@link CouponIssuer}
 *       하위 클래스로 {@link CouponIssueFacade}를 조립한다. 제품 코드는 바꾸지 않는다.</li>
 *   <li>자리 점유 시간은 장부 포트를 감싼 측정용 decorator로 잰다: tryAcquire 성공 직후부터 release 완료까지.</li>
 *   <li>발급 1건을 구간으로 나눈다. 캠페인 저장소 포트를 감싼 decorator가 {@code findByIdForUpdate} 앞뒤 시각을 찍고
 *       TransactionSynchronization(beforeCommit, afterCompletion)을 건다. 이 decorator를 쓰는 {@link CouponIssueTransaction}은
 *       테스트에서 직접 만들고, 빈과 같은 {@code @Transactional} 해석(AnnotationTransactionAttributeSource + 같은 트랜잭션 매니저)으로
 *       감싼다 — 빈을 바꾸지 않으므로 다른 테스트와 Spring 컨텍스트를 같이 쓴다.</li>
 *   <li>고정 client 스레드가 닫힌 루프로 다음 사용자를 꺼내 발급하고, 429면 같은 사용자로 잠깐 쉬었다 다시 보낸다.
 *       C=1은 client를 C개만 두어 429 재시도가 없는 변형도 함께 잰다(재시도 트래픽이 connection pool을 다투는지 보기 위해).</li>
 *   <li>단언은 불변식만 한다(쿠폰 수 = 발급 수 = N, 장부 비어 있음, 다른 예외 없음). 속도는 단언하지 않고 표로만 남긴다.</li>
 * </ul>
 * N은 클래스가 약 30초 안에 끝나도록(D-16: 30초 안이면 check에 둔다) 40, 반복은 2회로 둔다. 로컬 Windows Docker에서 N=150이면 회차당
 * 5~12초가 걸렸다. N이 작아 회차 간 편차가 크므로 한 회차가 아니라 C별 반복 전체의 경향으로 읽는다.
 *
 * <p>로그 표는 ASCII로 쓴다 — Windows 콘솔에서 한글 stdout이 깨졌던 경험(IdempotentProducerExperimentTest와 같은 이유).
 */
@SpringBootTest
class CouponThroughputExperimentTest {

    private static final Logger log = LoggerFactory.getLogger(CouponThroughputExperimentTest.class);

    private static final int[] CAPACITIES = {1, 3, 6, 12};
    private static final int NO_RETRY_CAPACITY = 1;
    private static final int REPEATS = 2;
    private static final int QUOTA = 40;
    private static final int WARM_UP_QUOTA = 40;
    private static final int CLIENT_THREADS = 32;
    private static final long RETRY_PAUSE_MILLIS = 1;
    private static final long ROUND_TIMEOUT_SECONDS = 60;
    private static final double NANOS_PER_MILLI = 1_000_000.0;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final double P95 = 0.95;

    private static final String RETRY = "retry32";
    private static final String NO_RETRY = "noRetry";

    @Autowired
    private CouponQuery couponQuery;

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

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("C = 1, 3, 6, 12(C=1은 재시도 없는 변형 포함)로 같은 캠페인 발급 처리량과 구간별 시간을 재고, 매 회차 쿠폰 수 = 발급 수 = N이며 장부가 비어 있다.")
    @Test
    void throughputByCapacity() throws Exception {
        runRound(RETRY, CAPACITIES[CAPACITIES.length - 1], CLIENT_THREADS, WARM_UP_QUOTA);

        List<RoundResult> results = new ArrayList<>();
        for (int capacity : CAPACITIES) {
            for (int run = 1; run <= REPEATS; run++) {
                results.add(runRound(RETRY, capacity, CLIENT_THREADS, QUOTA).withRun(run));
            }
        }
        for (int run = 1; run <= REPEATS; run++) {
            results.add(runRound(NO_RETRY, NO_RETRY_CAPACITY, NO_RETRY_CAPACITY, QUOTA).withRun(run));
        }

        log.info(runTable(results));
        log.info(segmentTable(results));
    }

    private RoundResult runRound(String variant, int capacity, int clientThreads, int quota) throws Exception {
        TimedPermits timedPermits = new TimedPermits(admissionPermits);
        CouponAdmission admission = new CouponAdmission(timedPermits, capacity);
        admission.open();
        SegmentRecorder recorder = new SegmentRecorder();
        TimedIssuer timedIssuer = new TimedIssuer(transactional(recorder), couponQuery, recorder);
        CouponIssueFacade facade = new CouponIssueFacade(couponQuery, admission, timedIssuer);

        Long campaignId = couponCampaignSetup.create("throughput-c" + capacity, quota, "5,000 off", 5_000L).campaignId();

        AtomicInteger nextUser = new AtomicInteger(0);
        AtomicInteger created = new AtomicInteger(0);
        AtomicLong rejected = new AtomicLong(0);
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newFixedThreadPool(clientThreads);
        List<Future<?>> workers = new ArrayList<>();
        long startedAt = System.nanoTime();
        try {
            for (int i = 0; i < clientThreads; i++) {
                workers.add(executor.submit(() -> {
                    int index;
                    while ((index = nextUser.getAndIncrement()) < quota) {
                        long userId = index + 1L;
                        if (!issueUntilAdmitted(facade, campaignId, userId, created, rejected, unexpected)) {
                            return;
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
            () -> assertThat(created.get()).isEqualTo(quota),
            () -> assertThat(couponRows(campaignId)).isEqualTo(quota),
            () -> assertThat(issuedCount(campaignId)).isEqualTo(quota),
            () -> assertThat(leftover).isEmpty(),
            () -> assertThat(timedPermits.holdNanos).hasSize(quota),
            () -> assertThat(timedIssuer.issueNanos).hasSize(quota),
            () -> assertThat(recorder.completed).hasSize(quota)
        );

        return new RoundResult(variant, capacity, 0, clientThreads, quota, elapsedNanos, rejected.get(),
            new ArrayList<>(timedPermits.holdNanos), new ArrayList<>(timedIssuer.issueNanos),
            new ArrayList<>(recorder.completed));
    }

    /**
     * 빈과 같은 발급 TX를 측정용 캠페인 저장소로 만든다. {@code @Transactional}은 빈과 같은 방식(애너테이션 해석 + 같은 트랜잭션
     * 매니저)으로 프록시에서 적용한다.
     */
    private CouponIssueTransaction transactional(SegmentRecorder recorder) {
        CouponIssueTransaction target = new CouponIssueTransaction(
            new TimedCampaignRepository(couponCampaignRepository, recorder), issuedCouponRepository, clock);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (CouponIssueTransaction) proxyFactory.getProxy();
    }

    /** 이 사용자가 쿠폰을 받을 때까지 보낸다. 429는 세고 다시 보낸다. 다른 예외는 모아 두고 이 스레드를 멈춘다(false). */
    private static boolean issueUntilAdmitted(CouponIssueFacade facade, Long campaignId, long userId,
                                              AtomicInteger created, AtomicLong rejected,
                                              ConcurrentLinkedQueue<Throwable> unexpected) {
        while (true) {
            try {
                CouponIssueResult result = facade.issue(campaignId, userId);
                if (result.outcome() != Outcome.CREATED) {
                    unexpected.add(new IllegalStateException("CREATED가 아니다 userId=" + userId + " outcome=" + result.outcome()));
                    return false;
                }
                created.incrementAndGet();
                return true;
            } catch (CoreException e) {
                if (e.getErrorType() != ErrorType.TOO_MANY_REQUESTS) {
                    unexpected.add(e);
                    return false;
                }
                rejected.incrementAndGet();
                try {
                    Thread.sleep(RETRY_PAUSE_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    unexpected.add(interrupted);
                    return false;
                }
            } catch (RuntimeException e) {
                unexpected.add(e);
                return false;
            }
        }
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

    private static String runTable(List<RoundResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("Coupon issuance throughput by admission capacity C, per run (times in ms; little = C / hold_avg; "
            + "obs_conc = throughput * hold_avg)");
        sb.append(String.format("%n%-8s %3s %3s %3s %4s %8s %9s %6s %10s %10s %10s %10s %11s %8s%n",
            "variant", "C", "thr", "run", "N", "wall_ms", "issued/s", "429s", "hold_avg", "hold_p95", "db_avg", "db_p95",
            "little_c/h", "obs_conc"));
        for (RoundResult r : results) {
            sb.append(String.format("%-8s %3d %3d %3d %4d %8.0f %9.1f %6d %10.2f %10.2f %10.2f %10.2f %11.1f %8.2f%n",
                r.variant, r.capacity, r.clientThreads, r.run, r.quota, r.elapsedNanos / NANOS_PER_MILLI, r.throughput(),
                r.rejected, avgMillis(r.holdNanos), p95Millis(r.holdNanos), avgMillis(r.issueNanos), p95Millis(r.issueNanos),
                r.littlePrediction(), r.observedConcurrency()));
        }
        return sb.toString();
    }

    /** 같은 변형·C의 반복 회차 표본을 모아 구간별 avg/p95를 낸다. ceiling = 1 / avg(lock_held). */
    private static String segmentTable(List<RoundResult> results) {
        Map<String, List<RoundResult>> groups = new LinkedHashMap<>();
        for (RoundResult r : results) {
            groups.computeIfAbsent(r.variant + "/" + r.capacity, key -> new ArrayList<>()).add(r);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Segments per CREATED issuance, pooled over runs (ms, avg/p95). pre_tx = issuer entry -> before FOR UPDATE; "
            + "lock_wait = FOR UPDATE call; in_lock = after FOR UPDATE -> beforeCommit; commit = beforeCommit -> afterCompletion; "
            + "post_tx = afterCompletion -> issuer exit; lock_held = in_lock + commit; ceiling = 1 / avg(lock_held)");
        sb.append(String.format("%n%-8s %3s %9s %13s %13s %13s %13s %13s %13s %9s%n",
            "variant", "C", "issued/s", "pre_tx", "lock_wait", "in_lock", "commit", "post_tx", "lock_held", "ceiling/s"));
        for (List<RoundResult> group : groups.values()) {
            RoundResult first = group.get(0);
            List<long[]> segments = group.stream().flatMap(r -> r.segments.stream()).toList();
            double throughput = group.stream().mapToDouble(RoundResult::throughput).average().orElse(0);
            List<Long> lockHeld = segment(segments, s -> s[SegmentRecorder.BEFORE_COMMIT] - s[SegmentRecorder.AFTER_LOCK]
                + s[SegmentRecorder.AFTER_COMPLETION] - s[SegmentRecorder.BEFORE_COMMIT]);
            sb.append(String.format("%-8s %3d %9.1f %13s %13s %13s %13s %13s %13s %9.1f%n",
                first.variant, first.capacity, throughput,
                avgP95(segment(segments, s -> s[SegmentRecorder.BEFORE_LOCK] - s[SegmentRecorder.ENTRY])),
                avgP95(segment(segments, s -> s[SegmentRecorder.AFTER_LOCK] - s[SegmentRecorder.BEFORE_LOCK])),
                avgP95(segment(segments, s -> s[SegmentRecorder.BEFORE_COMMIT] - s[SegmentRecorder.AFTER_LOCK])),
                avgP95(segment(segments, s -> s[SegmentRecorder.AFTER_COMPLETION] - s[SegmentRecorder.BEFORE_COMMIT])),
                avgP95(segment(segments, s -> s[SegmentRecorder.EXIT] - s[SegmentRecorder.AFTER_COMPLETION])),
                avgP95(lockHeld),
                1_000.0 / avgMillis(lockHeld)));
        }
        return sb.toString();
    }

    private static List<Long> segment(List<long[]> segments, ToLongFunction<long[]> duration) {
        return segments.stream().map(s -> duration.applyAsLong(s)).toList();
    }

    private static String avgP95(List<Long> nanos) {
        return String.format("%6.2f/%6.2f", avgMillis(nanos), p95Millis(nanos));
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

    /**
     * 발급 1건의 시각을 스레드별로 모은다. 발급 TX는 호출 스레드에서 돌고 TransactionSynchronization도 같은 스레드에서 불리므로
     * ThreadLocal로 충분하다. CREATED이고 commit된 건만 남긴다.
     */
    private static final class SegmentRecorder {

        static final int ENTRY = 0;
        static final int BEFORE_LOCK = 1;
        static final int AFTER_LOCK = 2;
        static final int BEFORE_COMMIT = 3;
        static final int AFTER_COMPLETION = 4;
        static final int EXIT = 5;
        static final int COMMITTED = 6;

        private final ThreadLocal<long[]> current = ThreadLocal.withInitial(() -> new long[COMMITTED + 1]);
        private final ConcurrentLinkedQueue<long[]> completed = new ConcurrentLinkedQueue<>();

        void start() {
            long[] marks = current.get();
            Arrays.fill(marks, 0L);
            marks[ENTRY] = System.nanoTime();
        }

        void mark(int slot) {
            current.get()[slot] = System.nanoTime();
        }

        void committed(boolean committed) {
            current.get()[COMMITTED] = committed ? 1L : 0L;
        }

        void finish(boolean created) {
            long[] marks = current.get();
            marks[EXIT] = System.nanoTime();
            boolean complete = marks[BEFORE_LOCK] != 0 && marks[AFTER_LOCK] != 0 && marks[BEFORE_COMMIT] != 0
                && marks[AFTER_COMPLETION] != 0 && marks[COMMITTED] == 1L;
            if (created && complete) {
                completed.add(marks.clone());
            }
        }
    }

    /** 캠페인 저장소 포트를 감싸 FOR UPDATE 앞뒤 시각을 찍고, 이 TX의 commit 앞·완료 뒤 시각을 걸어 둔다. 측정 전용이다. */
    private static final class TimedCampaignRepository implements CouponCampaignRepository {

        private final CouponCampaignRepository delegate;
        private final SegmentRecorder recorder;

        private TimedCampaignRepository(CouponCampaignRepository delegate, SegmentRecorder recorder) {
            this.delegate = delegate;
            this.recorder = recorder;
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
            recorder.mark(SegmentRecorder.BEFORE_LOCK);
            Optional<CouponCampaign> campaign = delegate.findByIdForUpdate(id);
            recorder.mark(SegmentRecorder.AFTER_LOCK);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    recorder.mark(SegmentRecorder.BEFORE_COMMIT);
                }

                @Override
                public void afterCompletion(int status) {
                    recorder.mark(SegmentRecorder.AFTER_COMPLETION);
                    recorder.committed(status == STATUS_COMMITTED);
                }
            });
            return campaign;
        }
    }

    /** 실제 발급 경로(CouponIssuer)를 그대로 부르고 걸린 시간(DB 발급 TX, 락 대기 포함)과 입구·출구 시각을 잰다. */
    private static final class TimedIssuer extends CouponIssuer {

        private final SegmentRecorder recorder;
        private final ConcurrentLinkedQueue<Long> issueNanos = new ConcurrentLinkedQueue<>();

        private TimedIssuer(CouponIssueTransaction couponIssueTransaction, CouponQuery couponQuery, SegmentRecorder recorder) {
            super(couponIssueTransaction, couponQuery);
            this.recorder = recorder;
        }

        @Override
        public CouponIssueResult issue(Long campaignId, Long userId) {
            long start = System.nanoTime();
            recorder.start();
            boolean created = false;
            try {
                CouponIssueResult result = super.issue(campaignId, userId);
                created = result.outcome() == Outcome.CREATED;
                return result;
            } finally {
                recorder.finish(created);
                issueNanos.add(System.nanoTime() - start);
            }
        }
    }

    private record RoundResult(String variant, int capacity, int run, int clientThreads, int quota, long elapsedNanos,
                               long rejected, List<Long> holdNanos, List<Long> issueNanos, List<long[]> segments) {

        RoundResult withRun(int runNumber) {
            return new RoundResult(variant, capacity, runNumber, clientThreads, quota, elapsedNanos, rejected,
                holdNanos, issueNanos, segments);
        }

        double throughput() {
            return quota / (elapsedNanos / NANOS_PER_SECOND);
        }

        double littlePrediction() {
            double holdAvg = avgMillis(holdNanos);
            return holdAvg == 0 ? 0 : capacity / (holdAvg / 1_000.0);
        }

        double observedConcurrency() {
            return throughput() * avgMillis(holdNanos) / 1_000.0;
        }
    }
}
