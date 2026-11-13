package com.loopers.config.coupon;

import com.loopers.application.coupon.CouponIssueSemaphore;
import com.loopers.config.redis.RedisConfig;
import com.loopers.infrastructure.coupon.RedisCouponIssueSemaphore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration(proxyBeanMethods = false)
public class CouponIssueSemaphoreConfiguration {
    @Bean
    public CouponIssueSemaphore couponIssueSemaphore(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> master,
        @Value("${commerce.coupon.worker.capacity:3}") int capacity
    ) {
        return new RedisCouponIssueSemaphore(master, capacity);
    }
}
