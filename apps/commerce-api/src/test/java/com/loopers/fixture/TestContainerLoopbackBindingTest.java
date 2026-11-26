package com.loopers.fixture;

import com.loopers.testcontainers.RedisTestContainersConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** W10 1차 전체 실행의 Redis 포트 가로채기(응답 첫 바이트 H) 재발 방지 검증. */
@DisplayName("테스트 컨테이너 루프백 공개")
class TestContainerLoopbackBindingTest {

    private static final byte[] LOOPBACK = {127, 0, 0, 1};

    @BeforeAll
    static void startContainers() throws ClassNotFoundException {
        Class.forName("com.loopers.testcontainers.MySqlTestContainersConfig");
        Class.forName("com.loopers.testcontainers.RedisTestContainersConfig");
    }

    @Test
    @DisplayName("Redis 공개 포트에 같은 번호의 127.0.0.1 바인딩을 거부한다.")
    void rejectsLoopbackBindOnRedisPort() {
        assertThat(RedisTestContainersConfig.host()).isEqualTo("127.0.0.1");
        assertThatThrownBy(() -> bindLoopback(RedisTestContainersConfig.port())).isInstanceOf(BindException.class);
    }

    @Test
    @DisplayName("MySQL 공개 포트에 같은 번호의 127.0.0.1 바인딩을 거부한다.")
    void rejectsLoopbackBindOnMySqlPort() {
        URI jdbc = URI.create(System.getProperty("datasource.mysql-jpa.main.jdbc-url").substring("jdbc:".length()));

        assertThat(jdbc.getHost()).isEqualTo("127.0.0.1");
        assertThatThrownBy(() -> bindLoopback(jdbc.getPort())).isInstanceOf(BindException.class);
    }

    @Test
    @DisplayName("127.0.0.1 연결은 실제 Redis 가 받는다.")
    void loopbackReachesRedis() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(LOOPBACK), RedisTestContainersConfig.port()), 2000);
            socket.setSoTimeout(2000);
            socket.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();

            assertThat(new String(in.readNBytes(7), StandardCharsets.US_ASCII)).isEqualTo("+PONG\r\n");
        }
    }

    private static void bindLoopback(int port) throws Exception {
        try (ServerSocket ignored = new ServerSocket(port, 1, InetAddress.getByAddress(LOOPBACK))) {
            // 바인딩이 성공하면 가로채기가 가능한 상태다.
        }
    }
}
