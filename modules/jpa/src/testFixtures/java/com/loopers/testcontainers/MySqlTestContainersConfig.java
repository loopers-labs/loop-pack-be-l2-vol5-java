package com.loopers.testcontainers;

import org.springframework.context.annotation.Configuration;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Configuration
public class MySqlTestContainersConfig {

    private static final MySQLContainer<?> mySqlContainer;

    static {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("loopers")
            .withUsername("test")
            .withPassword("test")
            .withExposedPorts(3306)
            .withCommand(
                "--character-set-server=utf8mb4",
                "--collation-server=utf8mb4_general_ci",
                "--skip-character-set-client-handshake",
                // 테스트마다 따로 뜬 Spring 컨텍스트가 연결 풀을 닫지 않고 쌓아 두므로 기본 151개로는 모자랄 수 있다.
                "--max-connections=500"
            );
        mySqlContainer.start();

        String mySqlJdbcUrl = String.format(
            "jdbc:mysql://%s:%d/%s",
            mySqlContainer.getHost(),
            mySqlContainer.getFirstMappedPort(),
            mySqlContainer.getDatabaseName()
        );

        System.setProperty("datasource.mysql-jpa.main.jdbc-url", mySqlJdbcUrl);
        System.setProperty("datasource.mysql-jpa.main.username", mySqlContainer.getUsername());
        System.setProperty("datasource.mysql-jpa.main.password", mySqlContainer.getPassword());
    }

    /** DB 프로세스를 얼린다. 연결과 포트는 남지만 응답하지 않는다. 장애 실험 전용이다. */
    public static void pause() {
        DockerClientFactory.instance().client().pauseContainerCmd(mySqlContainer.getContainerId()).exec();
    }

    /** {@link #pause()}로 얼린 DB를 다시 응답하게 한다. */
    public static void resume() {
        DockerClientFactory.instance().client().unpauseContainerCmd(mySqlContainer.getContainerId()).exec();
    }
}
