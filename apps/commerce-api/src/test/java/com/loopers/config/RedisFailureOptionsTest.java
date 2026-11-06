package com.loopers.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class RedisFailureOptionsTest {

    @Autowired
    private LettuceConnectionFactory lettuceConnectionFactory;

    @DisplayName("Redis 연결은 연결 수립을 1초, 연결된 뒤의 명령을 300ms로 제한하고, 연결이 끊긴 동안의 명령은 바로 실패시킨다.")
    @Test
    void limitsWaitingTimeWhenRedisIsSlowOrDisconnected() {
        // act
        LettuceClientConfiguration configuration = lettuceConnectionFactory.getClientConfiguration();
        ClientOptions options = configuration.getClientOptions().orElseThrow();
        TimeoutOptions.TimeoutSource commandTimeout = options.getTimeoutOptions().getSource();

        // assert
        assertAll(
            () -> assertThat(configuration.getCommandTimeout()).isEqualTo(Duration.ofSeconds(1)),
            () -> assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofSeconds(1)),
            () -> assertThat(options.getTimeoutOptions().isTimeoutCommands()).isTrue(),
            () -> assertThat(commandTimeout.getTimeUnit().toMillis(commandTimeout.getTimeout(null))).isEqualTo(300L),
            () -> assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
        );
    }
}
