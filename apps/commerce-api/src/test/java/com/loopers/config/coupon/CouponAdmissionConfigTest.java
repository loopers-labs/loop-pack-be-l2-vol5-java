package com.loopers.config.coupon;

import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 8주차 설계 §4·§7-1, C6. 쿠폰 전용 Redis 연결이 master 고정·짧은 타임아웃으로 만들어지고, coupon.admission 설정이 test 프로파일 값으로 바인딩되는지 확인한다.
 */
@SpringBootTest
class CouponAdmissionConfigTest {

    private final CouponAdmissionProperties properties;
    private final LettuceConnectionFactory connectionFactory;
    private final RedisTemplate<String, String> redisTemplate;

    @Autowired
    public CouponAdmissionConfigTest(
        CouponAdmissionProperties properties,
        @Qualifier(CouponAdmissionRedisConfig.COUPON_ADMISSION_CONNECTION_FACTORY) LettuceConnectionFactory connectionFactory,
        @Qualifier(CouponAdmissionRedisConfig.COUPON_ADMISSION_REDIS_TEMPLATE) RedisTemplate<String, String> redisTemplate
    ) {
        this.properties = properties;
        this.connectionFactory = connectionFactory;
        this.redisTemplate = redisTemplate;
    }

    @DisplayName("test 프로파일에서는 스케줄러가 꺼지고 하트비트 200ms·permit TTL 1초·admitted TTL 1초로 짧아지며, 나머지는 운영 값 그대로다.")
    @Test
    void bindsTestProfileValues() {
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.capacity()).isEqualTo(3);
        assertThat(properties.promoteFixedDelay()).isEqualTo(Duration.ofMillis(200));
        assertThat(properties.heartbeatInterval()).isEqualTo(Duration.ofMillis(200));
        assertThat(properties.permitTtl()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.admittedTtl()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.acceptLimitMultiplier()).isEqualTo(1.2);
    }

    @DisplayName("자동 재개는 기본이 꺼져 있고(수동 재개), probe 5초·연속 3회·10분 창에 3회로 바인딩된다 (§7-3, D10).")
    @Test
    void autoReopenIsDisabledByDefault() {
        CouponAdmissionProperties.AutoReopen autoReopen = properties.autoReopen();

        assertThat(autoReopen.enabled()).isFalse();
        assertThat(autoReopen.probeInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(autoReopen.requiredSuccesses()).isEqualTo(3);
        assertThat(autoReopen.maxReopens()).isEqualTo(3);
        assertThat(autoReopen.window()).isEqualTo(Duration.ofMinutes(10));
    }

    @DisplayName("쿠폰 전용 연결은 master 단일 노드에 command 300ms·connect 500ms 로 만들어진다.")
    @Test
    void buildsMasterOnlyConnectionWithShortTimeouts() {
        assertThat(connectionFactory.getStandaloneConfiguration()).isInstanceOf(RedisStandaloneConfiguration.class);
        assertThat(connectionFactory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofMillis(300));
        assertThat(connectionFactory.getClientConfiguration().getClientOptions()).hasValueSatisfying(options -> {
            assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
            assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofMillis(500));
        });
    }

    @DisplayName("쿠폰 전용 template 은 전용 팩토리로 Redis 에 실제로 명령을 보낼 수 있다.")
    @Test
    void templateUsesDedicatedFactory_andReachesRedis() {
        assertThat(redisTemplate.getConnectionFactory()).isSameAs(connectionFactory);
        assertThat(redisTemplate.getConnectionFactory().getConnection().ping()).isEqualTo("PONG");
    }
}
