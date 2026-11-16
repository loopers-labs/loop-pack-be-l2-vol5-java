package com.loopers.fixture;

import com.loopers.config.redis.RedisProperties;
import com.loopers.testcontainers.RedisTestContainersConfig;
import io.lettuce.core.ReadFrom;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.Properties;

/**
 * 쿠폰 Redis 테스트를 fixture 컨테이너에 고정하고 확인한다.
 * fixture 의 System property 덮어쓰기는 RedisProperties 바인딩보다 늦을 수 있어 일반 컨텍스트가 로컬 6379 에 붙을 수 있다.
 * 그래서 컨텍스트 생성 전에 {@link #register} 로 주소를 넣고, 첫 Redis 명령 전에 {@link #verify} 로 대조한다.
 */
public final class CouponRedisTarget {

    private CouponRedisTarget() {}

    /** @DynamicPropertySource 에서 호출한다. 새 컨테이너를 만들지 않고 fixture 컨테이너 주소를 쓴다. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.database", () -> "0");
        registry.add("datasource.redis.master.host", RedisTestContainersConfig::host);
        registry.add("datasource.redis.master.port", RedisTestContainersConfig::port);
        registry.add("datasource.redis.replicas[0].host", RedisTestContainersConfig::host);
        registry.add("datasource.redis.replicas[0].port", RedisTestContainersConfig::port);
    }

    /**
     * 바인딩 값·template 의 연결 팩토리 설정이 fixture 컨테이너와 같고, 그 연결로 받은 run_id 가 컨테이너 안에서 직접 읽은 run_id 와
     * 같으며, 서버 역할이 master 인지 확인한다. 다르면 예외로 준비를 멈춘다. 포트 번호나 role 만으로 대신하지 않는다.
     * 정적 master/replica 팩토리는 노드 목록 getter 가 없어 주소는 바인딩 값으로, 실제 서버는 run_id 로 대조한다.
     */
    public static void verify(RedisProperties redisProperties, RedisTemplate<String, String> master) {
        String host = RedisTestContainersConfig.host();
        int port = RedisTestContainersConfig.port();
        if (!host.equals(redisProperties.master().host()) || port != redisProperties.master().port()
            || redisProperties.database() != 0 || redisProperties.replicas().size() != 1
            || !host.equals(redisProperties.replicas().get(0).host()) || port != redisProperties.replicas().get(0).port()) {
            throw new IllegalStateException("바인딩된 Redis 주소가 fixture 컨테이너와 다릅니다. [container = " + host + ":" + port
                + ", bound = " + redisProperties + "]");
        }
        if (!(master.getConnectionFactory() instanceof LettuceConnectionFactory factory)
            || !factory.getClientConfiguration().getReadFrom().map(ReadFrom.MASTER::equals).orElse(false)
            || factory.getDatabase() != 0) {
            throw new IllegalStateException("master template 의 연결 팩토리가 MASTER 읽기·database 0 이 아닙니다.");
        }
        String containerRunId = RedisTestContainersConfig.serverRunId();
        Properties server = master.execute((RedisCallback<Properties>) c -> c.serverCommands().info("server"));
        Properties replication = master.execute((RedisCallback<Properties>) c -> c.serverCommands().info("replication"));
        String connectedRunId = server == null ? null : server.getProperty("run_id");
        String role = replication == null ? null : replication.getProperty("role");
        if (!containerRunId.equals(connectedRunId) || !"master".equals(role)) {
            throw new IllegalStateException("master template 이 fixture 컨테이너 서버에 연결되지 않았습니다. [containerRunId = "
                + containerRunId + ", connectedRunId = " + connectedRunId + ", role = " + role + "]");
        }
    }
}
