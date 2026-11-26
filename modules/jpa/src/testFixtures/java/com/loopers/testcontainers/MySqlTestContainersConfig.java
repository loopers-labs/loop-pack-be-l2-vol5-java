package com.loopers.testcontainers;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Arrays;

@Configuration
public class MySqlTestContainersConfig {

    /**
     * Docker Desktop 기본 공개(IPv6 와일드카드)는 같은 번호의 127.0.0.1 바인딩을 막지 못해 테스트 HTTP 서버가 DB 연결을
     * 가로챌 수 있다. 127.0.0.1 로 공개하면 운영체제가 같은 번호의 루프백 바인딩을 거부한다.
     */
    private static final String LOOPBACK = "127.0.0.1";
    private static final int MYSQL_PORT = 3306;
    private static final MySQLContainer<?> mySqlContainer;

    static {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("loopers")
            .withUsername("test")
            .withPassword("test")
            .withExposedPorts(MYSQL_PORT)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                .withPublishAllPorts(false)
                .withPortBindings(new PortBinding(Ports.Binding.bindIp(LOOPBACK), ExposedPort.tcp(MYSQL_PORT))))
            .withCommand(
                "--character-set-server=utf8mb4",
                "--collation-server=utf8mb4_general_ci",
                "--skip-character-set-client-handshake"
            );
        mySqlContainer.start();
        requireLoopbackBinding();

        String mySqlJdbcUrl = String.format(
            "jdbc:mysql://%s:%d/%s",
            LOOPBACK,
            mySqlContainer.getFirstMappedPort(),
            mySqlContainer.getDatabaseName()
        );

        System.setProperty("datasource.mysql-jpa.main.jdbc-url", mySqlJdbcUrl);
        System.setProperty("datasource.mysql-jpa.main.username", mySqlContainer.getUsername());
        System.setProperty("datasource.mysql-jpa.main.password", mySqlContainer.getPassword());
    }

    private static void requireLoopbackBinding() {
        Ports.Binding[] bindings = mySqlContainer.getContainerInfo().getNetworkSettings().getPorts().getBindings()
            .get(ExposedPort.tcp(MYSQL_PORT));
        if (bindings == null || bindings.length == 0
            || !Arrays.stream(bindings).allMatch(binding -> LOOPBACK.equals(binding.getHostIp()))) {
            throw new IllegalStateException("MySQL 테스트 컨테이너가 127.0.0.1 로만 공개되지 않았습니다. [bindings = "
                + Arrays.toString(bindings) + "]");
        }
    }
}
