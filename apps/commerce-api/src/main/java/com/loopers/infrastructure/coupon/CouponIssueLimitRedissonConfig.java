package com.loopers.infrastructure.coupon;

import com.loopers.config.redis.RedisProperties;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 분산락·세마포어 비교 실험에서만 Redisson 클라이언트를 만든다. 발급 판단과 같은 Redis master를 쓴다. */
@Configuration
@ConditionalOnExpression("'${commerce.coupon.issue-limit.mode:NONE}' != 'NONE'")
public class CouponIssueLimitRedissonConfig {
    @Bean(destroyMethod = "shutdown")
    public RedissonClient couponIssueLimitRedisson(RedisProperties properties) {
        Config config = new Config();
        config.useSingleServer()
            .setAddress("redis://" + properties.master().host() + ":" + properties.master().port())
            .setDatabase(properties.database());
        return Redisson.create(config);
    }
}
