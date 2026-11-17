package com.loopers.application.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.loopers.application.coupon.command.CouponCampaignCommand;
import com.loopers.application.coupon.command.CouponIssueCommand;
import com.loopers.application.coupon.port.CouponExecutionEvidence;
import com.loopers.application.coupon.query.CouponCounterSnapshotDao;
import com.loopers.application.coupon.result.CouponIssueResult;
import com.loopers.application.coupon.support.CouponAdmissionControl;
import com.loopers.application.coupon.support.CouponCounterException;
import com.loopers.application.coupon.support.CouponSlotRetryTiming;
import com.loopers.application.coupon.usecase.IssueCouponUseCase;
import com.loopers.config.coupon.CouponAdmissionProperties;
import com.loopers.config.coupon.CouponCounterRedisConfig;
import com.loopers.config.redis.RedisNodeInfo;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.coupon.model.Coupon;
import com.loopers.domain.coupon.repository.CouponRepository;
import com.loopers.infrastructure.cache.coupon.RedisCouponIssueCounter;
import com.loopers.infrastructure.concurrency.coupon.SemaphoreCouponAdmission;
import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@IntegrationTest
@Testcontainers
@TestPropertySource(properties = {"datasource.mysql-jpa.main.username=root", "datasource.mysql-jpa.main.password=test"})
class CouponCounterIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry properties) {
        properties.add("datasource.redis.master.host", REDIS::getHost);
        properties.add("datasource.redis.master.port", () -> REDIS.getMappedPort(6379));
        properties.add("datasource.redis.replicas[0].host", REDIS::getHost);
        properties.add("datasource.redis.replicas[0].port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private RedisCouponIssueCounter counter;
    @Autowired private CouponCounterManagementService management;
    @Autowired private PrepareCouponCampaignService preparation;
    @Autowired private CouponIssueLookupService lookup;
    @Autowired private IssueCouponService issuance;
    @Autowired private CouponExecutionEvidence evidence;
    @Autowired private CouponAdmissionProperties admissionProperties;
    @Autowired private CouponSlotRetryTiming timing;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext context;
    @Autowired @Qualifier("couponCounterRedisTemplate") private RedisTemplate<String, String> redis;
    @MockitoSpyBean private CouponCounterSnapshotDao snapshots;
    @MockitoSpyBean private CouponRepository coupons;
    private CouponAdmissionControl control;
    private CountingIssueCouponUseCase counted;
    private final List<Long> ownedCampaigns = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 테스트는 외부 TX 없이 실행한다. 실제 Spring TX/증거 adapter를 사용하며 registry만 사례별로 격리한다.
        control = new CouponAdmissionControl(new SemaphoreCouponAdmission(admissionProperties), evidence);
        IssueCouponFacade facade = new IssueCouponFacade(lookup, issuance, control, timing);
        counted = new CountingIssueCouponUseCase(facade, counter);
    }

    @AfterEach
    void tearDown() {
        control.close("test-cleanup");
        for (long campaign : ownedCampaigns) {
            Set<String> keys = redis.keys("coupon:counter:{" + campaign + "}:*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        }
        cleanUp.truncateAllTables();
    }

    @Test
    void distinguishesUnpreparedZeroLostAndWrongTypeAndWiresPrimaryUseCase() {
        long campaign = campaign();
        assertThat(context.getBean(IssueCouponUseCase.class)).isInstanceOf(CountingIssueCouponUseCase.class);
        assertThat(counter.findIssuedCount(campaign)).isEmpty();
        assertThatThrownBy(() -> counter.recordIssued(campaign, 1L)).isInstanceOf(CouponCounterException.class);
        assertThat(management.rebuild(campaign)).isZero();
        assertThat(counter.findIssuedCount(campaign)).hasValue(0L);
        redis.delete(RedisCouponIssueCounter.activeKey(campaign));
        assertThat(counter.findIssuedCount(campaign)).isEmpty();
        redis.opsForValue().set(RedisCouponIssueCounter.activeKey(campaign), "wrong type");
        assertThatThrownBy(() -> counter.findIssuedCount(campaign)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void concurrentDuplicateRecordsIncreaseOnlyOnceAndFailedBuildKeepsPreviousAggregate() throws Exception {
        long campaign = campaign();
        management.rebuild(campaign);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                futures.add(executor.submit(() -> counter.recordIssued(campaign, 123L)));
            }
            int applied = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    applied++;
                }
            }
            assertThat(applied).isEqualTo(1);
            assertThat(counter.findIssuedCount(campaign)).hasValue(1L);
            assertThatThrownBy(() -> counter.replace(campaign, List.of(456L, 456L)))
                .isInstanceOf(CouponCounterException.class);
            assertThat(counter.findIssuedCount(campaign)).hasValue(1L);
            assertThat(redis.getExpire(RedisCouponIssueCounter.activeKey(campaign))).isEqualTo(-1L);
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void delayedPublishCannotOverwriteRecordOrNewerRebuild() {
        long campaign = campaign();
        management.rebuild(campaign);
        String base = "coupon:counter:{" + campaign + "}:";
        String oldStage = base + "build:old";
        assertThat(redis.execute(script("stage"), List.of(oldStage, base + "fence"), "old-token")).isEqualTo(1L);

        assertThat(counter.recordIssued(campaign, 123L)).isTrue();
        assertThat(redis.execute(script("publish"),
            List.of(oldStage, base + "active", base + "fence"), "0", "old-token")).isEqualTo(-1L);
        assertThat(counter.findIssuedCount(campaign)).hasValue(1L);

        // 새로운 rebuild의 token도 이전 publish를 무효화한다.
        counter.replace(campaign, List.of(456L, 789L));
        assertThat(redis.execute(script("publish"),
            List.of(oldStage, base + "active", base + "fence"), "0", "old-token")).isEqualTo(-1L);
        assertThat(counter.findIssuedCount(campaign)).hasValue(2L);
    }

    @Test
    void unpreparedRecordInvalidatesDelayedInitializationWithoutCreatingZeroAggregate() {
        long campaign = campaign();
        String base = "coupon:counter:{" + campaign + "}:";
        String oldStage = base + "build:old-init";
        assertThat(redis.execute(script("stage"), List.of(oldStage, base + "fence"), "old-token")).isEqualTo(1L);

        assertThatThrownBy(() -> counter.recordIssued(campaign, 123L)).isInstanceOf(CouponCounterException.class);

        assertThat(redis.execute(script("publish"),
            List.of(oldStage, base + "active", base + "fence"), "0", "old-token")).isEqualTo(-1L);
        assertThat(counter.findIssuedCount(campaign)).isEmpty();
    }

    @Test
    void countsOnlyCommittedResultsAndRebuildsLostAggregateFromDatabase() {
        long campaign = campaign();
        management.rebuild(campaign);
        open();
        CouponIssueCommand command = new CouponIssueCommand(campaign, 11L);
        CouponIssueResult issued = counted.execute(command);
        assertThat(counted.execute(command).couponId()).isEqualTo(issued.couponId());
        assertThat(counter.findIssuedCount(campaign)).hasValue(1L);
        redis.delete(RedisCouponIssueCounter.activeKey(campaign));
        assertThat(management.compare(campaign).aggregateCount()).isEqualTo(OptionalLong.empty());
        assertThat(management.rebuild(campaign)).isEqualTo(1L);
        assertThat(counter.recordIssued(campaign, issued.couponId())).isFalse();
        assertThat(counter.findIssuedCount(campaign)).hasValue(1L);
        assertThat(control.status().available()).isEqualTo(3);
    }

    @Test
    void redisFailureAfterCommitPreservesCouponCountPermitAndStoredResponse() {
        long campaign = campaign();
        redis.opsForValue().set(RedisCouponIssueCounter.activeKey(campaign), "wrong type");
        open();
        CouponIssueResult result = counted.execute(new CouponIssueCommand(campaign, 11L));

        assertThat(result.created()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupons WHERE campaign_id = ?", Long.class, campaign)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_campaigns WHERE id = ?", Integer.class, campaign))
            .isEqualTo(1);
        assertThat(control.status().available()).isEqualTo(3);
        assertThat(control.status().open()).isTrue();
        assertThat(counted.execute(new CouponIssueCommand(campaign, 11L)).couponId()).isEqualTo(result.couponId());
    }

    @Test
    void rebuildPublishesBeforeBlockedNewCommitAndItsLaterAppendIsPreserved() throws Exception {
        long campaign = campaign();
        management.rebuild(campaign);
        open();
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch publish = new CountDownLatch(1);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch finishIssue = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            snapshotRead.countDown();
            await(publish);
            return result;
        }).when(snapshots).findCommittedCouponIds(campaign);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            inserted.countDown();
            await(finishIssue);
            return result;
        }).when(coupons).save(any(Coupon.class));
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> rebuild = executor.submit(() -> management.rebuild(campaign));
            assertThat(snapshotRead.await(10, TimeUnit.SECONDS)).isTrue();
            Future<CouponIssueResult> issue = executor.submit(() -> counted.execute(new CouponIssueCommand(campaign, 11L)));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            publish.countDown();
            assertThat(rebuild.get(10, TimeUnit.SECONDS)).isZero();
            finishIssue.countDown();
            assertThat(issue.get(10, TimeUnit.SECONDS).created()).isTrue();
            assertThat(counter.findIssuedCount(campaign)).hasValue(1L);
            assertThat(management.compare(campaign).databaseCount()).isEqualTo(1L);
        } finally {
            publish.countDown();
            finishIssue.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void disconnectedRedisIsAnAccessFailureRatherThanEmptyOrZero() {
        try (GenericContainer<?> server = new GenericContainer<>(DockerImageName.parse("redis:7.0")).withExposedPorts(6379)) {
            server.start();
            var config = new CouponCounterRedisConfig();
            var factory = config.connectionFactory(new RedisProperties(0,
                new RedisNodeInfo(server.getHost(), server.getMappedPort(6379)), List.of()));
            factory.afterPropertiesSet();
            try {
                RedisTemplate<String, String> template = config.template(factory);
                template.afterPropertiesSet();
                RedisCouponIssueCounter isolated = new RedisCouponIssueCounter(template);
                isolated.replace(1L, List.of());
                server.stop();
                assertThatThrownBy(() -> isolated.findIssuedCount(1L)).isInstanceOf(RuntimeException.class);
            } finally {
                factory.destroy();
            }
        }
    }

    private long campaign() {
        Instant now = Instant.now();
        long id = preparation.execute(new CouponCampaignCommand.Create("counter test", 2, "saved benefit", 1000L,
            now.minusSeconds(3600), now.plusSeconds(3600))).campaignId();
        preparation.execute(new CouponCampaignCommand.Activation(id, true));
        ownedCampaigns.add(id);
        return id;
    }

    private void open() {
        control.verifyRestart(control.status().bootId(), true, "test-first-deployment", true, List.of());
        control.open(control.status().bootId());
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Counter test barrier deadline exceeded");
        }
    }

    private static DefaultRedisScript<Long> script(String action) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/coupon-counter-" + action + ".lua"));
        script.setResultType(Long.class);
        return script;
    }
}
