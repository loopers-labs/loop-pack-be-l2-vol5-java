package com.loopers.infrastructure.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponAdmission;
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
class RedisCouponAdmissionTest {

    private final CouponAdmission admission;
    private final RedisTemplate<String, String> redis;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    RedisCouponAdmissionTest(CouponAdmission admission,
                             @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
                             RedisCleanUp redisCleanUp) {
        this.admission = admission;
        this.redis = redis;
        this.redisCleanUp = redisCleanUp;
    }

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    private String acquire() {
        return ((CouponAdmission.Result.Admitted) admission.tryAcquire()).token();
    }

    @DisplayName("자리가 C 개 차면 다음 획득은 Full 이다")
    @Test
    void fullAtCapacity() {
        acquire();
        acquire();
        acquire();

        assertThat(admission.tryAcquire()).isInstanceOf(CouponAdmission.Result.Full.class);
    }

    @DisplayName("EXP-018: 자기 token 을 두 번 반환하거나 모르는 token 을 반환해도 다른 자리는 그대로다")
    @Test
    void releaseTouchesOnlyOwnToken() {
        String mine = acquire();
        String other = acquire();

        admission.release(mine);
        admission.release(mine);
        admission.release("unknown-token");

        assertThat(redis.opsForSet().members(RedisCouponAdmission.KEY)).containsExactly(other);
    }

    @DisplayName("ADM-008: 자리에는 만료 시간이 없다")
    @Test
    void tokensDoNotExpire() {
        acquire();

        assertThat(redis.getExpire(RedisCouponAdmission.KEY)).isEqualTo(-1L);
    }
}
