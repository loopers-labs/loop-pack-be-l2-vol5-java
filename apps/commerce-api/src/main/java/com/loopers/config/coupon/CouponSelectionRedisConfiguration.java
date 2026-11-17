package com.loopers.config.coupon;

import com.loopers.application.coupon.CouponSelectionStore;
import com.loopers.config.redis.RedisConfig;
import com.loopers.infrastructure.coupon.RedisCouponSelectionStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration(proxyBeanMethods = false)
public class CouponSelectionRedisConfiguration {
    @Bean
    public CouponSelectionStore couponSelectionStore(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> master) {
        return new RedisCouponSelectionStore(master);
    }
}
