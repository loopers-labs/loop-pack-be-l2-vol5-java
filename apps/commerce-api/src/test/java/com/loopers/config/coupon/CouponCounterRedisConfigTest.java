package com.loopers.config.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.config.redis.RedisNodeInfo;
import com.loopers.config.redis.RedisProperties;
import io.lettuce.core.ReadFrom;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CouponCounterRedisConfigTest {
    @Test
    void dedicatedClientUsesMasterAndBoundedCommandAndConnectTimeouts() {
        var factory = new CouponCounterRedisConfig().connectionFactory(new RedisProperties(2,
            new RedisNodeInfo("master.example", 6379), List.of(new RedisNodeInfo("replica.example", 6380))));

        assertThat(factory.getHostName()).isEqualTo("master.example");
        assertThat(factory.getDatabase()).isEqualTo(2);
        assertThat(factory.getClientConfiguration().getReadFrom()).contains(ReadFrom.MASTER);
        assertThat(factory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(factory.getClientConfiguration().getClientOptions().orElseThrow()
            .getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofSeconds(1));
    }
}
