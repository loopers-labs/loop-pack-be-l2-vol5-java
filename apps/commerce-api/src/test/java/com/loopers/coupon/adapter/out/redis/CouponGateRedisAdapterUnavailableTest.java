package com.loopers.coupon.adapter.out.redis;

import com.loopers.coupon.application.port.out.CouponGatePort.GateUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 필수 실험 ⑤의 어댑터 쪽: Redis에 닿지 못하면 어댑터가 GateUnavailableException으로 바꾼다 → 서비스가 503으로 답한다.
 * 테스트 전체가 같이 쓰는 Redis 컨테이너는 멈출 수 없어서, 아무것도 듣지 않는 포트(localhost:1)에 붙인 어댑터로 확인한다.
 */
class CouponGateRedisAdapterUnavailableTest {

    private LettuceConnectionFactory connectionFactory;
    private CouponGateRedisAdapter adapter;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).build());
        connectionFactory.afterPropertiesSet();
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        adapter = new CouponGateRedisAdapter(template, 5, Duration.ofMillis(100), Duration.ofSeconds(30));
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @DisplayName("Redis에 닿지 못하면 발급·등록·조회·입장 모두 GateUnavailableException이다 (500이 아니라 503이 되게).")
    @Test
    void convertsConnectionFailure() {
        assertThatThrownBy(() -> adapter.issue(1L, 1L, 10)).isInstanceOf(GateUnavailableException.class);
        assertThatThrownBy(() -> adapter.register(1L, 1L, 10, 100)).isInstanceOf(GateUnavailableException.class);
        assertThatThrownBy(() -> adapter.status(1L, 1L, 10)).isInstanceOf(GateUnavailableException.class);
        assertThatThrownBy(() -> adapter.admit(1L, 10)).isInstanceOf(GateUnavailableException.class);
    }
}
