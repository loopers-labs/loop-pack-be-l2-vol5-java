package com.loopers.testcontainers;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.redis.testcontainers.RedisContainer;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.Container;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.Arrays;

@Configuration
public class RedisTestContainersConfig {
    /**
     * Docker Desktop 기본 공개(IPv6 와일드카드)는 같은 번호의 127.0.0.1 바인딩을 막지 못해 테스트 HTTP 서버가 Redis 연결을
     * 가로챌 수 있다. 127.0.0.1 로 공개하면 운영체제가 같은 번호의 루프백 바인딩을 거부한다.
     */
    private static final String LOOPBACK = "127.0.0.1";
    private static final int REDIS_PORT = 6379;
    private static final RedisContainer redisContainer = new RedisContainer(DockerImageName.parse("redis:latest"))
        .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
            .withPublishAllPorts(false)
            .withPortBindings(new PortBinding(Ports.Binding.bindIp(LOOPBACK), ExposedPort.tcp(REDIS_PORT))));

    static {
        redisContainer.start();
        requireLoopbackBinding();
    }

    public RedisTestContainersConfig() {
        System.setProperty("datasource.redis.database", "0");
        System.setProperty("datasource.redis.master.host", host());
        System.setProperty("datasource.redis.master.port", String.valueOf(port()));
        System.setProperty("datasource.redis.replicas[0].host", host());
        System.setProperty("datasource.redis.replicas[0].port", String.valueOf(port()));
    }

    private static void requireLoopbackBinding() {
        Ports.Binding[] bindings = redisContainer.getContainerInfo().getNetworkSettings().getPorts().getBindings()
            .get(ExposedPort.tcp(REDIS_PORT));
        if (bindings == null || bindings.length == 0
            || !Arrays.stream(bindings).allMatch(binding -> LOOPBACK.equals(binding.getHostIp()))) {
            throw new IllegalStateException("Redis 테스트 컨테이너가 127.0.0.1 로만 공개되지 않았습니다. [bindings = "
                + Arrays.toString(bindings) + "]");
        }
    }

    /*
     * 아래는 테스트가 이 컨테이너를 연결 대상으로 직접 주입하고 대조할 때 쓰는 읽기 수단이다.
     * 위 생성자의 System property 는 RedisProperties 가 먼저 바인딩되면 반영되지 않으므로, 대상 고정이 필요한 테스트는
     * 이 값을 @DynamicPropertySource 로 넣는다. 컨테이너 수명·기존 덮어쓰기 동작은 바꾸지 않는다.
     */

    public static String host() {
        return LOOPBACK;
    }

    public static int port() {
        return redisContainer.getFirstMappedPort();
    }

    public static String containerId() {
        return redisContainer.getContainerId();
    }

    /** 앱 연결을 거치지 않고 컨테이너 안의 redis-cli 로 읽은 run_id. 연결 대상 서버의 동일성 대조에 쓴다. */
    public static String serverRunId() {
        try {
            Container.ExecResult result = redisContainer.execInContainer("redis-cli", "INFO", "server");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("컨테이너 redis-cli 실패: " + result.getStderr());
            }
            return result.getStdout().lines()
                .filter(line -> line.startsWith("run_id:"))
                .map(line -> line.substring("run_id:".length()).trim())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("컨테이너 INFO server 에 run_id 가 없습니다."));
        } catch (IOException e) {
            throw new IllegalStateException("컨테이너 redis-cli 실행 실패", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("컨테이너 redis-cli 대기 중 interrupt", e);
        }
    }
}
