package com.loopers.config.redis;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.MasterSlaveServersConfig;
import org.redisson.config.ReadMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Redisson 클라이언트. Lettuce 와 같은 datasource.redis.* (master/replica, database, timeout) 를 쓴다.
 * 읽기도 master 로만 보낸다.
 * Redisson 은 만들 때 바로 연결하므로 @Lazy 로 둔다. RedissonClient 를 주입받는 앱만 연결한다.
 */
@Configuration
public class RedissonConfig {

    @Lazy
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        MasterSlaveServersConfig servers = config.useMasterSlaveServers()
                .setDatabase(redisProperties.database())
                .setMasterAddress(address(redisProperties.master()))
                .setReadMode(ReadMode.MASTER);
        redisProperties.replicas().forEach(replica -> servers.addSlaveAddress(address(replica)));
        if (redisProperties.timeout() != null) {
            servers.setTimeout(Math.toIntExact(redisProperties.timeout().toMillis()));
        }
        return Redisson.create(config);
    }

    private static String address(RedisNodeInfo node) {
        return "redis://" + node.host() + ":" + node.port();
    }
}
