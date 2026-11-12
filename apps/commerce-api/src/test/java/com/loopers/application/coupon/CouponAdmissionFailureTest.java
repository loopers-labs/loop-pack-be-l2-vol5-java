package com.loopers.application.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponAdmission;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.domain.coupon.IssueQuota;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CouponAdmissionFailureTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private static final String EPOCH_KEY = "coupon:issue:admission:epoch:v1";
    private static final String ADMISSION_KEY = "coupon:issue:admission:v1";
    private static final long PAUSE_MILLIS = 600;
    private static final long DEADLINE_MILLIS = 3_000;

    private final CouponFacade couponFacade;
    private final CouponAdmission couponAdmission;
    private final RedisTemplate<String, String> redis;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;
    private final SoldOutCampaigns soldOutCampaigns;
    private final RedisProperties redisProperties;
    private RedisClient controlClient;
    private StatefulRedisConnection<String, String> control;

    @Autowired
    CouponAdmissionFailureTest(CouponFacade couponFacade, CouponAdmission couponAdmission,
                               @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
                               JdbcTemplate jdbcTemplate, DataSource dataSource,
                               DatabaseCleanUp databaseCleanUp, RedisCleanUp redisCleanUp,
                               SoldOutCampaigns soldOutCampaigns, RedisProperties redisProperties) {
        this.couponFacade = couponFacade;
        this.couponAdmission = couponAdmission;
        this.redis = redis;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
        this.soldOutCampaigns = soldOutCampaigns;
        this.redisProperties = redisProperties;
    }

    @BeforeEach
    void setUp() {
        controlClient = RedisClient.create(RedisURI.create(redisProperties.master().host(), redisProperties.master().port()));
        control = controlClient.connect();
    }

    @AfterEach
    void tearDown() {
        unpauseWrites();
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        soldOutCampaigns.clear();
        couponAdmission.close();
        control.close();
        controlClient.shutdown();
    }

    private Long openCampaign() {
        return couponFacade.openCampaign(CampaignName.of("블프 쿠폰"), DiscountAmount.of(5_000),
            IssueQuota.of(10), true).getId();
    }

    private long scriptCalls() {
        long calls = 0;
        for (String line : control.sync().info("commandstats").split("\r?\n")) {
            if (line.startsWith("cmdstat_evalsha:") || line.startsWith("cmdstat_eval:")) {
                calls += Long.parseLong(line.replaceAll("^cmdstat_\\w+:calls=(\\d+).*", "$1"));
            }
        }
        return calls;
    }

    private void pauseWrites() {
        control.sync().dispatch(CommandType.CLIENT, new StatusOutput<>(StringCodec.UTF8),
            new CommandArgs<>(StringCodec.UTF8).add("PAUSE").add(PAUSE_MILLIS).add("WRITE"));
    }

    private void unpauseWrites() {
        control.sync().dispatch(CommandType.CLIENT, new StatusOutput<>(StringCodec.UTF8),
            new CommandArgs<>(StringCodec.UTF8).add("UNPAUSE"));
    }

    private long activeTokens() {
        return control.sync().scard(ADMISSION_KEY);
    }

    private void awaitActiveTokens(long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + DEADLINE_MILLIS;
        while (activeTokens() != expected) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("활성 token 이 " + expected + " 개가 되지 않았다: " + activeTokens());
            }
            Thread.sleep(20);
        }
    }

    private int couponRows(Long campaignId) {
        Integer rows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM coupon WHERE campaign_id = ?", Integer.class, campaignId);
        return rows == null ? -1 : rows;
    }

    private int issuedCount(Long campaignId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT issued_count FROM coupon_campaign WHERE id = ?", Integer.class, campaignId);
        return count == null ? -1 : count;
    }

    private void assertUnavailableWithoutRedis(Long campaignId, Long userId) {
        long before = scriptCalls();
        assertThatThrownBy(() -> couponFacade.issue(campaignId, userId, NOW))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        assertThat(scriptCalls()).isEqualTo(before);
    }

    @DisplayName("FLT-004: 수용은 닫힌 채 시작하고, 닫혀 있으면 Redis 에 묻지 않고 503 이다")
    @Test
    void startsClosed() {
        Long campaignId = openCampaign();

        assertThat(couponAdmission.isOpen()).isFalse();
        assertUnavailableWithoutRedis(campaignId, 1L);
    }

    @DisplayName("FLT-004: 열면 epoch 가 Redis 에 남고 발급이 된다")
    @Test
    void opensWithEpoch() {
        Long campaignId = openCampaign();

        CouponAdmission.Opened opened = couponAdmission.open();

        assertThat(couponAdmission.isOpen()).isTrue();
        assertThat(opened.epoch()).isNotBlank();
        assertThat(redis.opsForValue().get(EPOCH_KEY)).isEqualTo(opened.epoch());
        assertThat(couponFacade.issue(campaignId, 1L, NOW).newlyIssued()).isTrue();
    }

    @DisplayName("FLT-004: 열린 뒤 Redis 의 epoch 가 사라지면 상태 유실로 보고 닫히며, 기존 쿠폰 조회는 그대로다")
    @Test
    void closesWhenEpochIsLost() {
        Long campaignId = openCampaign();
        couponAdmission.open();
        Coupon issued = couponFacade.issue(campaignId, 1L, NOW).coupon();
        redis.delete(EPOCH_KEY);

        assertThatThrownBy(() -> couponFacade.issue(campaignId, 2L, NOW))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.COUPON_ADMISSION_UNAVAILABLE);

        assertThat(couponAdmission.isOpen()).isFalse();
        assertUnavailableWithoutRedis(campaignId, 3L);
        CouponFacade.Issued again = couponFacade.issue(campaignId, 1L, NOW);
        assertThat(again.newlyIssued()).isFalse();
        assertThat(again.coupon().id()).isEqualTo(issued.id());
    }

    @DisplayName("FLT-001: 획득이 timeout 이면 같은 token 으로 한 번 더 묻고, 그래도 모르면 503 으로 닫는다")
    @Test
    void closesAfterAcquireTimeoutRetry() throws Exception {
        Long campaignId = openCampaign();
        couponAdmission.open();
        long before = scriptCalls();
        pauseWrites();

        assertThatThrownBy(() -> couponFacade.issue(campaignId, 1L, NOW))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.COUPON_ADMISSION_UNAVAILABLE);

        assertThat(couponAdmission.isOpen()).isFalse();
        assertThat(couponRows(campaignId)).isZero();
        unpauseWrites();
        assertThat(scriptCalls()).isEqualTo(before + 2);
        assertThat(activeTokens()).isEqualTo(1);
        assertUnavailableWithoutRedis(campaignId, 2L);
    }

    @DisplayName("FLT-003: 반환이 timeout 이면 쿠폰과 발급 수는 그대로 두고, 한 번 더 시도한 뒤 닫는다")
    @Test
    void closesAfterReleaseTimeoutButKeepsCoupon() throws Exception {
        Long campaignId = openCampaign();
        couponAdmission.open();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<CouponFacade.Issued> issuing;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM coupon_campaign WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, campaignId);
                lock.executeQuery();
            }
            issuing = executor.submit(() -> couponFacade.issue(campaignId, 1L, NOW));
            awaitActiveTokens(1);
            pauseWrites();
            holder.commit();
        }
        CouponFacade.Issued issued = issuing.get(DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
        executor.shutdownNow();

        assertThat(issued.newlyIssued()).isTrue();
        assertThat(couponRows(campaignId)).isEqualTo(1);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
        assertThat(couponAdmission.isOpen()).isFalse();
        unpauseWrites();
        assertUnavailableWithoutRedis(campaignId, 2L);
    }
}
