package com.loopers.domains.coupon.in;

import com.loopers.domains.coupon.app.AdmissionCheckReport;
import com.loopers.domains.coupon.app.CouponAdmissionCheck;
import com.loopers.domains.coupon.app.port.AdmissionUnavailableException;
import com.loopers.domains.coupon.out.RedisCouponAdmission;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 8주차 3단계: epoch(D138)와 재개 확인·열기(D139·D140). 실제 Redis·MySQL이고, 붙잡기·반환 실패는 테스트 설정의 포트 래퍼로만 한다.
// 실제 Redis 중단·재개는 CouponAdmissionRedisFailureTest(전용 컨테이너)가 본다.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponAdmissionResumeIntegrationTest extends CouponIntegrationSupport {
    private static final long DEADLINE_SECONDS = 20;

    @Autowired private CouponAdmissionCheck couponAdmissionCheck;
    @Autowired private RedisCouponAdmission redisCouponAdmission;
    @Autowired private DataSource dataSource;
    @Autowired private Environment environment;

    // 확인 모드의 DB 기준(열린 쿠폰 잠금 트랜잭션 수)을 실제로 세려면 PROCESS·performance_schema 읽기 권한이 필요하다.
    // 테스트 컨테이너의 test 계정에는 없어서, 이 컨테이너 안에서만 root로 읽기 권한을 더한다(운영 코드·공용 fixture는 그대로).
    // 권한이 없을 때의 "조회 실패 → ✗" 장면은 CouponAdmissionCheckTest가 fake 포트로 본다.
    @BeforeAll
    void grantLockViewsToTestUser() throws Exception {
        try (Connection root = DriverManager.getConnection(environment.getRequiredProperty("datasource.mysql-jpa.main.jdbc-url"),
            "root", environment.getRequiredProperty("datasource.mysql-jpa.main.password"));
            Statement statement = root.createStatement()) {
            statement.execute("GRANT PROCESS ON *.* TO 'test'@'%'");
            statement.execute("GRANT SELECT ON performance_schema.* TO 'test'@'%'");
        }
        // 전역 권한(PROCESS)은 새로 맺는 연결부터 적용되므로, 풀에 이미 있는 연결을 바꾸게 한다.
        dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().softEvictConnections();
    }

    @Test
    @DisplayName("F3: 반환되지 않은 token은 시간이 지나도 그대로 남고 수용 키에는 만료 시간이 없다(TTL -1)")
    void stuckTokenHasNoTtl() throws Exception {
        redisCouponAdmission.acquire(1, "stuck-token", 3);

        Thread.sleep(1500);

        assertThat(admissionRedis.opsForSet().members(RedisCouponAdmission.key(1))).containsExactly("stuck-token");
        assertThat(admissionRedis.getExpire(RedisCouponAdmission.key(1))).isEqualTo(-1L);
    }

    @Test
    @DisplayName("F4: 확인 모드는 닫힌 뒤 시간이 모자라면·쿠폰 행 잠금을 쥔 트랜잭션이 있으면 열지 말라고, 모두 ✓이면 열어도 된다고 보고하고 아무 값도 바꾸지 않는다")
    void checkReportsWithoutChangingAnything() throws Exception {
        long campaignId = campaign(true, 10, 0, 3000);
        close("acquire 실패: 주입", NOW.minusSeconds(3));
        Map<String, Object> before = settingsRow();

        AdmissionCheckReport tooSoon = couponAdmissionCheck.check();
        close("acquire 실패: 주입", NOW.minusSeconds(60));
        Map<String, Object> afterClose = settingsRow();
        AdmissionCheckReport locked;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("select id from coupon_campaign where id = ? for update")) {
                lock.setLong(1, campaignId);
                lock.executeQuery();
                locked = couponAdmissionCheck.check();
            } finally {
                holder.rollback();
            }
        }
        AdmissionCheckReport clear = couponAdmissionCheck.check();

        assertThat(tooSoon.time().ok()).isFalse();
        assertThat(tooSoon.safeToOpen()).isFalse();
        assertThat(settingsRow()).isEqualTo(afterClose);
        assertThat(before.get("epoch")).isEqualTo(afterClose.get("epoch"));
        assertThat(locked.time().ok()).isTrue();
        assertThat(locked.database().ok()).isFalse();
        assertThat(locked.safeToOpen()).isFalse();
        assertThat(clear.database().ok()).as("잠금 조회: " + clear.database().detail()).isTrue();
        assertThat(clear.reconcile().ok()).isTrue();
        assertThat(clear.safeToOpen()).isTrue();
    }

    @Test
    @DisplayName("F5: 열기 모드는 epoch를 1 올리고, 옛 epoch 키에 token 3개가 남아 있어도 새 키에서 자리 3개를 얻어 발급한다")
    void openMovesToNewEpochKey() throws Exception {
        for (long userId = 1; userId <= 3; userId++) {
            user(userId);
        }
        long campaignId = campaign(true, 10, 0, 3000);
        admissionSettings(3, false);
        for (String token : new String[] {"old-1", "old-2", "old-3"}) {
            admissionRedis.opsForSet().add(RedisCouponAdmission.key(1), token);
        }

        CouponAdmissionCheck.Reopened reopened = couponAdmissionCheck.open();

        assertThat(reopened.previousEpoch()).isEqualTo(1);
        assertThat(reopened.newEpoch()).isEqualTo(2);
        assertThat(settingsRow()).containsEntry("epoch", 2L).containsEntry("open", true).containsEntry("closed_reason", null);
        for (long userId = 1; userId <= 3; userId++) {
            issue(userId, campaignId).andExpect(status().isCreated());
        }
        assertThat(permits(1)).isEqualTo(3);
        assertThat(permits(2)).isZero();
        assertThat(coupons(campaignId)).isEqualTo(3);
    }

    @Test
    @DisplayName("F6: 옛 epoch로 시작한 발급이 열기 뒤에 끝나면 옛 키에서만 자기 token을 지우고 새 키의 token은 그대로 둔다")
    void oldEpochRunReleasesFromOldKeyOnly() throws Exception {
        user(1);
        long campaignId = campaign(true, 10, 0, 3000);
        admissionSettings(3, true);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        admission.onAcquired(() -> {
            held.countDown();
            try {
                proceed.await(DEADLINE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> oldRun = executor.submit(() -> issue(1, campaignId).andReturn().getResponse().getStatus());
            assertThat(held.await(DEADLINE_SECONDS, TimeUnit.SECONDS)).as("옛 epoch 실행이 자리를 얻고 붙잡힘").isTrue();
            assertThat(permits(1)).isEqualTo(1);
            couponAdmissionCheck.open();
            admissionRedis.opsForSet().add(RedisCouponAdmission.key(2), "new-epoch-holder");

            proceed.countDown();

            assertThat(oldRun.get(DEADLINE_SECONDS, TimeUnit.SECONDS)).isEqualTo(201);
            assertThat(permits(1)).isZero();
            assertThat(admissionRedis.opsForSet().members(RedisCouponAdmission.key(2))).containsExactly("new-epoch-holder");
        } finally {
            proceed.countDown();
            executor.shutdownNow();
            executor.awaitTermination(DEADLINE_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("F7: 반환 실패로 닫힌 뒤 확인은 시간이 지나기 전엔 열지 말라고, 지난 뒤엔 열어도 된다고 하고, 열면 epoch가 올라 새 발급이 되며 먼저 받은 쿠폰은 그대로다")
    void releaseFailureThenCheckOpenAndIssueAgain() throws Exception {
        user(1);
        user(2);
        long campaignId = campaign(true, 10, 0, 3000);
        admission.failNextReleaseWith(new AdmissionUnavailableException("주입된 반환 실패"));
        issue(1, campaignId).andExpect(status().isCreated());
        long firstCoupon = couponId(campaignId, 1);
        assertThat(settingsRow()).containsEntry("open", false);
        issue(2, campaignId).andExpect(status().isServiceUnavailable());

        AdmissionCheckReport right = couponAdmissionCheck.check();
        clock.advance(Duration.ofSeconds(11));
        AdmissionCheckReport later = couponAdmissionCheck.check();
        CouponAdmissionCheck.Reopened reopened = couponAdmissionCheck.open();

        assertThat(right.safeToOpen()).isFalse();
        assertThat(later.safeToOpen()).as("시간 " + later.time().detail() + ", DB " + later.database().detail()).isTrue();
        assertThat(reopened.newEpoch()).isEqualTo(reopened.previousEpoch() + 1);
        issue(2, campaignId).andExpect(status().isCreated());
        assertThat(couponId(campaignId, 1)).isEqualTo(firstCoupon);
        assertThat(coupons(campaignId)).isEqualTo(2);
        assertThat(issuedCount(campaignId)).isEqualTo(2);
    }

    private void close(String reason, Instant closedAt) {
        jdbc.update("update coupon_admission_settings set open = false, closed_reason = ?, closed_at = ? where id = 1", reason,
            LocalDateTime.ofInstant(closedAt, ZoneOffset.UTC));
    }

    private Map<String, Object> settingsRow() {
        return jdbc.queryForMap("select capacity, open, epoch, closed_reason, closed_at from coupon_admission_settings where id = 1");
    }
}
