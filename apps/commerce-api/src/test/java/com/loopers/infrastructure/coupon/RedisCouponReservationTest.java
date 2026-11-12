package com.loopers.infrastructure.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponReservation;
import com.loopers.domain.coupon.CouponReservation.Result;
import com.loopers.domain.coupon.IssueQuota;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RedisCouponReservationTest {

    private static final Long CAMPAIGN = 7L;
    private static final IssueQuota QUOTA = IssueQuota.of(3);

    private final CouponReservation reservation;
    private final RedisTemplate<String, String> redis;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    RedisCouponReservationTest(CouponReservation reservation,
                               @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
                               RedisCleanUp redisCleanUp) {
        this.reservation = reservation;
        this.redis = redis;
        this.redisCleanUp = redisCleanUp;
    }

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    private Result reserve(long userId) {
        return reservation.reserve(CAMPAIGN, userId, QUOTA, 0);
    }

    @DisplayName("RSV-001: 받아들인 순서대로 1 부터 번호를 준다")
    @Test
    void numbersInArrivalOrder() {
        assertThat(reserve(101L)).isEqualTo(new Result.Reserved(1));
        assertThat(reserve(102L)).isEqualTo(new Result.Reserved(2));
        assertThat(redis.opsForZSet().score(RedisCouponReservation.key(CAMPAIGN), "102")).isEqualTo(2.0);
    }

    @DisplayName("RSV-001: 같은 사용자가 다시 오면 같은 번호이고 집합은 그대로다")
    @Test
    void sameUserSameNumber() {
        reserve(101L);
        reserve(102L);

        assertThat(reserve(101L)).isEqualTo(new Result.Existing(1));
        assertThat(redis.opsForZSet().size(RedisCouponReservation.key(CAMPAIGN))).isEqualTo(2L);
    }

    @DisplayName("RSV-001: 한도만큼 차면 품절이고 집합은 늘지 않는다")
    @Test
    void soldOutAtQuota() {
        reserve(101L);
        reserve(102L);
        reserve(103L);

        assertThat(reserve(104L)).isEqualTo(new Result.SoldOut());
        assertThat(redis.opsForZSet().size(RedisCouponReservation.key(CAMPAIGN))).isEqualTo(3L);
    }

    @DisplayName("RSV-002: 가운데 예약이 취소돼도 다음 번호는 이미 나간 번호와 겹치지 않는다")
    @Test
    void cancelledNumberIsNotReused() {
        reserve(101L);
        reserve(102L);
        reserve(103L);
        reservation.cancel(CAMPAIGN, 102L);

        assertThat(reserve(104L)).isEqualTo(new Result.Reserved(4));
    }

    @DisplayName("FLT-008: 집합 크기가 DB 발급 수보다 작으면 유실이고 받지 않는다")
    @Test
    void smallerThanDbIsLost() {
        reserve(101L);

        assertThat(reservation.reserve(CAMPAIGN, 102L, QUOTA, 2)).isEqualTo(new Result.Lost());
        assertThat(redis.opsForZSet().size(RedisCouponReservation.key(CAMPAIGN))).isEqualTo(1L);
    }
}
