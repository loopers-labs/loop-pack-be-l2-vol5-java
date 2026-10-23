package com.loopers.support.testpg;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

// 로컬 Compose와 같은 test-pg 이미지를 테스트 클래스 사이에서 하나만 띄운다.
// 수동 처리 모드와 제어 API를 켜서 처리 시점·시나리오·호출 수를 테스트가 정한다.
public final class TestPgContainer {
    public static final String PROVIDER_ACCOUNT_ID = "135135";
    public static final Duration DROP_RESPONSE_DELAY = Duration.ofSeconds(3);

    private static GenericContainer<?> container;

    private TestPgContainer() {}

    public static synchronized String baseUrl() {
        if (container == null) {
            container = start();
        }
        return "http://" + container.getHost() + ":" + container.getMappedPort(8080);
    }

    // 컨테이너가 지금까지 출력한 로그다. PG 쪽에 카드 값이 남지 않는지 확인할 때 쓴다.
    public static synchronized String logs() {
        baseUrl();
        return container.getLogs();
    }

    private static GenericContainer<?> start() {
        Path dockerfile = Path.of(System.getProperty("test-pg.dockerfile"));
        Path jar = Path.of(System.getProperty("test-pg.jar"));
        GenericContainer<?> started = new GenericContainer<>(
            new ImageFromDockerfile("loopers-test-pg-" + UUID.randomUUID(), true)
                .withFileFromPath("Dockerfile", dockerfile)
                .withFileFromPath("apps/test-pg/build/libs/test-pg.jar", jar)
        )
            .withEnv("SERVER_PORT", "8080")
            .withEnv("SERVER_ADDRESS", "0.0.0.0")
            .withEnv("TEST_PG_PROCESSING_MODE", "MANUAL")
            .withEnv("TEST_PG_CONTROL_ENABLED", "true")
            .withEnv("TEST_PG_DROP_RESPONSE_DELAY", DROP_RESPONSE_DELAY.toSeconds() + "s")
            .withExposedPorts(8080)
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(8080))))
            // PG가 보내는 callback이 host.testcontainers.internal로 테스트 서버에 닿게 한다.
            .withAccessToHost(true)
            .waitingFor(Wait.forLogMessage(".*Started TestPgApplication.*", 1).withStartupTimeout(Duration.ofSeconds(90)));
        started.start();
        return started;
    }
}
