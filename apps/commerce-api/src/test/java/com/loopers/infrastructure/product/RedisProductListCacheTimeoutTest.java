package com.loopers.infrastructure.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.ProductListCacheKey;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisNodeInfo;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.product.ProductSort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("응답하지 않는 Redis의 대기 제한")
class RedisProductListCacheTimeoutTest {

    private static final Duration TIMEOUT = Duration.ofMillis(100);

    private ServerSocket silentServer;
    private final List<Socket> acceptedSockets = new CopyOnWriteArrayList<>();
    private LettuceConnectionFactory connectionFactory;

    @BeforeEach
    void startSilentServer() throws IOException {
        silentServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!silentServer.isClosed()) {
                try {
                    acceptedSockets.add(silentServer.accept());
                } catch (IOException ignored) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        for (Socket socket : acceptedSockets) {
            socket.close();
        }
        silentServer.close();
    }

    @Test
    @DisplayName("연결은 되지만 응답이 없으면 설정한 timeout 근처에서 실패한다.")
    void failsWithinConfiguredTimeout_whenRedisDoesNotRespond() {
        RedisProductListCache cache = cacheConnectedTo(silentServer.getLocalPort());
        ProductListCacheKey key = new ProductListCacheKey(7L, ProductSort.PRICE_ASC, 0, 20, 0L);

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> cache.find(key)).isInstanceOf(RuntimeException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
    }

    private RedisProductListCache cacheConnectedTo(int port) {
        RedisNodeInfo node = new RedisNodeInfo(InetAddress.getLoopbackAddress().getHostAddress(), port);
        RedisConfig redisConfig = new RedisConfig(new RedisProperties(0, node, List.of(), TIMEOUT, TIMEOUT));
        connectionFactory = redisConfig.masterRedisConnectionFactory();
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        RedisTemplate<String, String> redisTemplate = redisConfig.masterRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        return new RedisProductListCache(redisTemplate, new ObjectMapper());
    }
}
