package com.loopers.experiment;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.loopers.application.purchase.PurchaseActivityFacade;
import com.loopers.domain.purchase.ProcessedOrderConfirmationRepository;
import com.loopers.domain.purchase.ProductPurchaseActivityRepository;
import com.loopers.interfaces.consumer.OrderConfirmedListener;
import com.loopers.support.KafkaProbe;
import com.loopers.testcontainers.KafkaTestContainers;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.loopers.support.KafkaProbe.item;
import static com.loopers.support.KafkaProbe.json;
import static com.loopers.support.KafkaProbe.nextId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 실험 C-3. DB 자체를 멈춘다 — 연결이 실제로 끊긴 장애에서 listener가 멈추고 offset이 고정되며, DB가 돌아온 뒤 다시 켜면
 * 같은 offset부터 이어 처리하고 커넥션 풀도 회복한다(docs/week6/design.md 9번 실험 C, docs/week6/decisions.md D-20, D-21 C-3).
 *
 * <p><b>{@code check}에서 뺀다</b>(D-21). {@code @Tag("experiment")}라 기본 {@code test}에서 제외되고
 * {@code ./gradlew :apps:commerce-streamer:experimentTest}로만 돈다. 매번 지켜 주는 흐름과 분류는 C-1·C-2가 덮는다.
 *
 * <ul>
 *   <li>공용 MySQL 컨테이너는 JVM 전체가 static으로 공유하고 modules/*는 수정 금지라, 이 테스트 전용 MySQL 컨테이너를 띄우고
 *       {@code datasource.mysql-jpa.main.*}를 {@link DynamicPropertySource}로 덮어쓴다. 공용 픽스처는 static 블록에서
 *       시스템 프로퍼티로 주소를 넣는데, DynamicPropertySource가 시스템 프로퍼티보다 우선한다. 실제로 이 컨테이너에 붙었는지
 *       풀의 jdbcUrl로 확인한다.</li>
 *   <li>장애는 {@code docker stop}, 복구는 같은 컨테이너의 {@code docker start}다. pause가 아닌 이유: pause는 프로세스를 얼릴 뿐
 *       소켓을 닫지 않아, 열려 있던 연결이 응답을 기다리며 매달린다(MySQL 드라이버 socketTimeout 기본값은 무한). 그건 "멈춘 DB"가
 *       아니라 "기다리다 끝나지 않는 DB"다.</li>
 *   <li>Testcontainers의 {@code stop()}은 컨테이너를 지우므로 docker client로 같은 컨테이너를 멈추고 다시 켠다. 다시 켜면 host
 *       포트가 새로 배정될 수 있어, 처음부터 고정 host 포트에 바인딩해 JDBC URL이 그대로 유지되게 한다.</li>
 *   <li>커넥션 획득 대기(Hikari connection-timeout)는 이 테스트에서만 2초로 명시한다. 운영 값은 건드리지 않는다.</li>
 * </ul>
 * 재시작은 KafkaListenerEndpointRegistry로 사람을 흉내 낸다(D-20). committed offset은 admin으로 group의 값을 읽는다.
 */
@Tag("experiment")
@SpringBootTest(properties = "datasource.mysql-jpa.main.connection-timeout=2000")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DbOutageExperimentTest {

    private static final String TOPIC = KafkaProbe.unique("order-events-db-outage");
    private static final String GROUP = KafkaProbe.unique("purchase-activity-db-outage");
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final int PARTITION = 0;
    private static final int HOST_PORT = freePort();
    private static final MySQLContainer<?> MYSQL = startMySql();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        KafkaTestContainers.createTopic(TOPIC);
        registry.add("events.order-confirmed.topic", () -> TOPIC);
        registry.add("events.order-confirmed.group-id", () -> GROUP);
        registry.add("datasource.mysql-jpa.main.jdbc-url", DbOutageExperimentTest::jdbcUrl);
        registry.add("datasource.mysql-jpa.main.username", MYSQL::getUsername);
        registry.add("datasource.mysql-jpa.main.password", MYSQL::getPassword);
    }

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    /** listener가 받은 예외를 관찰만 한다(동작은 진짜 그대로). */
    @MockitoSpyBean
    private PurchaseActivityFacade facade;

    @Autowired
    private ProcessedOrderConfirmationRepository processedRepository;

    @Autowired
    private ProductPurchaseActivityRepository activityRepository;

    @Autowired
    private HikariDataSource dataSource;

    private final List<Throwable> listenerFailures = new CopyOnWriteArrayList<>();
    private KafkaProbe kafka;

    @BeforeAll
    void openProbe() {
        kafka = new KafkaProbe(TOPIC, GROUP);
        doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } catch (Throwable failure) {
                listenerFailures.add(failure);
                throw failure;
            }
        }).when(facade).handle(any(), any(), any());
    }

    @AfterAll
    void closeProbe() {
        kafka.close();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("빈 포트를 찾지 못함", e);
        }
    }

    @SuppressWarnings("resource")
    private static MySQLContainer<?> startMySql() {
        MySQLContainer<?> container = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("loopers")
            .withUsername("test")
            .withPassword("test")
            .withCommand(
                "--character-set-server=utf8mb4",
                "--collation-server=utf8mb4_general_ci",
                "--skip-character-set-client-handshake")
            .withCreateContainerCmdModifier(cmd -> {
                HostConfig hostConfig = cmd.getHostConfig() == null ? new HostConfig() : cmd.getHostConfig();
                cmd.withHostConfig(hostConfig.withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(HOST_PORT), new ExposedPort(MySQLContainer.MYSQL_PORT))));
            });
        container.start();
        return container;
    }

    private static String jdbcUrl() {
        return String.format("jdbc:mysql://%s:%d/%s", MYSQL.getHost(), HOST_PORT, MYSQL.getDatabaseName());
    }

    /** 장애: 같은 컨테이너를 멈춘다(지우지 않는다). mysqld가 내려가며 열린 연결을 닫는다. */
    private static void stopDb() {
        MYSQL.getDockerClient().stopContainerCmd(MYSQL.getContainerId()).withTimeout(10).exec();
    }

    /** 복구: 같은 컨테이너를 같은 host 포트로 다시 켜고, MySQL이 연결을 받을 때까지 기다린다(풀 밖의 별도 연결로 확인). */
    private static void startDb() {
        MYSQL.getDockerClient().startContainerCmd(MYSQL.getContainerId()).exec();
        await().atMost(WAIT).pollInterval(Duration.ofMillis(500)).ignoreExceptions()
            .until(() -> {
                try (Connection connection = DriverManager.getConnection(
                    jdbcUrl() + "?connectTimeout=2000&socketTimeout=2000", MYSQL.getUsername(), MYSQL.getPassword())) {
                    return connection.isValid(2);
                }
            });
    }

    private MessageListenerContainer container() {
        return listenerRegistry.getListenerContainer(OrderConfirmedListener.LISTENER_ID);
    }

    private RecordMetadata send(long orderId, long productId, int quantity) {
        return kafka.send(PARTITION, String.valueOf(orderId),
            json(orderId, ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS), item(productId, quantity))
                .getBytes(StandardCharsets.UTF_8));
    }

    private void awaitAcked(RecordMetadata sent) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100))
            .until(() -> kafka.committed(sent.partition()) >= sent.offset() + 1);
    }

    private void awaitStopped() {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100))
            .until(() -> !container().isRunning() && !container().isChildRunning());
    }

    private boolean processed(long orderId) {
        return processedRepository.findByOrderId(orderId).isPresent();
    }

    private String pool() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        return "total=" + pool.getTotalConnections() + ", active=" + pool.getActiveConnections() + ", idle=" + pool.getIdleConnections();
    }

    @DisplayName("C-3: DB 컨테이너를 멈추면 listener가 실패해 멈추고 offset은 실패한 기록에 고정된다. "
        + "DB를 다시 켜고 container를 재시작하면 그 offset부터 뒤에 쌓인 기록까지 이어 처리하고, 커넥션 풀도 회복한다.")
    @Test
    void dbStopped_stopsAndResumesFromSameOffset() {
        // arrange — 이 테스트 전용 DB에 붙었는지, 정상 1건으로 출발선을 만든다
        assertThat(dataSource.getJdbcUrl()).isEqualTo(jdbcUrl());
        RecordMetadata first = send(nextId(), nextId(), 1);
        awaitAcked(first);
        long failingOrder = nextId();
        long laterOrder = nextId();

        // act 1 — DB를 멈추고 기록을 보낸다. 멈춘 동안 뒤에 한 건이 더 쌓인다
        stopDb();
        RecordMetadata failing = send(failingOrder, nextId(), 2);
        awaitStopped();
        RecordMetadata later = send(laterOrder, nextId(), 3);
        long committedWhileStopped = kafka.committed(PARTITION);
        String poolWhileStopped = pool();

        // assert 1
        assertAll(
            () -> assertThat(listenerFailures).isNotEmpty(),
            () -> assertThat(committedWhileStopped).isEqualTo(failing.offset())
        );

        // act 2 — DB를 같은 포트로 다시 켜고, 사람이 container를 다시 켠다
        startDb();
        container().start();
        awaitAcked(later);

        // assert 2
        System.out.printf("[C-3] 정상 offset=%d → committed=%d, DB stop 뒤 실패 offset=%d → 멈춤 중 committed=%d "
                + "(뒤에 쌓인 offset=%d), 예외=%s%n",
            first.offset(), first.offset() + 1, failing.offset(), committedWhileStopped, later.offset(),
            rootCause(listenerFailures.get(0)));
        System.out.printf("[C-3] 풀: 멈춤 중 %s → 재시작 뒤 %s, DB start + registry start() 뒤 committed=%d%n",
            poolWhileStopped, pool(), kafka.committed(PARTITION));
        assertAll(
            () -> assertThat(processed(failingOrder)).isTrue(),
            () -> assertThat(processed(laterOrder)).isTrue(),
            () -> assertThat(activityRepository.findAllByOrderId(failingOrder)).hasSize(1),
            () -> assertThat(activityRepository.findAllByOrderId(laterOrder)).hasSize(1),
            () -> assertThat(kafka.committed(PARTITION)).isEqualTo(later.offset() + 1),
            () -> assertThat(dataSource.getHikariPoolMXBean().getTotalConnections()).isPositive()
        );
    }

    private static String rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return failure.getClass().getSimpleName() + " ← " + root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
