package com.loopers.domains.coupon.in;

import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.support.coupon.CouponTestConfig;
import com.loopers.support.coupon.CouponTestConfig.FailingCouponRepository;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.utils.DatabaseCleanUp;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// 쿠폰 발급 통합 테스트가 같은 컨텍스트를 쓰도록 설정을 이 부모 하나에 둔다. 준비 데이터는 JDBC로 넣어 바로 commit되고,
// 결과는 서비스 트랜잭션이 끝난 뒤 JDBC 새 조회로 본다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(CouponTestConfig.class)
abstract class CouponIntegrationSupport {
    static final Instant NOW = Instant.parse("2026-11-11T01:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DatabaseCleanUp cleanup;
    @Autowired UserJpaRepository users;
    @Autowired MutablePaymentClock clock;
    @Autowired FailingCouponRepository failingCoupons;

    @BeforeEach
    void prepareCoupons() {
        clock.set(NOW);
        failingCoupons.disarm();
    }

    @AfterEach
    void cleanCoupons() {
        failingCoupons.disarm();
        cleanup.truncateAllTables();
    }

    void user(long id) {
        users.save(new UserJpaEntity(id));
    }

    long campaign(boolean active, int totalQuota, int issuedCount, long discountAmount) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("insert into coupon_campaign(name, active, total_quota, issued_count,"
                + " discount_amount, created_at) values ('한정 수량 캠페인', ?, ?, ?, ?, ?)", new String[] {"id"});
            statement.setBoolean(1, active);
            statement.setInt(2, totalQuota);
            statement.setInt(3, issuedCount);
            statement.setLong(4, discountAmount);
            statement.setObject(5, LocalDateTime.parse("2026-11-01T00:00:00"));
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    ResultActions issue(long userId, long campaignId) throws Exception {
        return mvc.perform(post("/api/v1/coupon-campaigns/{campaignId}/issues", campaignId).header("X-USER-ID", String.valueOf(userId)));
    }

    ResultActions mine(long userId) throws Exception {
        return mvc.perform(get("/api/v1/coupons").header("X-USER-ID", String.valueOf(userId)));
    }

    int issuedCount(long campaignId) {
        return jdbc.queryForObject("select issued_count from coupon_campaign where id = ?", Integer.class, campaignId);
    }

    long coupons(long campaignId) {
        return jdbc.queryForObject("select count(*) from issued_coupon where campaign_id = ?", Long.class, campaignId);
    }

    // 없으면 null이다. 비교 단언에서 차이로 드러나게 한다.
    Long couponId(long campaignId, long userId) {
        return jdbc.queryForList("select id from issued_coupon where campaign_id = ? and user_id = ?", Long.class, campaignId, userId)
            .stream().findFirst().orElse(null);
    }
}
