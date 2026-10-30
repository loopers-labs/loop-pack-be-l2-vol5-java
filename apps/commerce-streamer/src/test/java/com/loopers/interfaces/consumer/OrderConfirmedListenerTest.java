package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.PurchaseActivityFacade;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProcessedOrderConfirmationRepository;
import com.loopers.domain.purchase.ProductPurchaseActivityRepository;
import com.loopers.support.KafkaProbe;
import com.loopers.testcontainers.KafkaTestContainers;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * 실제 listener가 실패하면 멈추고, offset이 고정되고, 사람이 다시 켜면 정확히 그 offset부터 이어 간다
 * (docs/week6/design.md 9번 실험 C, docs/week6/decisions.md D-8, D-20, D-21, INV-606).
 *
 * <ul>
 *   <li>C-1 이음매의 가짜: 처리 기록 저장 자리에서 DB 장애 같은 예외를 던진다. 멈춤과 재개의 <b>흐름</b>을 결정적으로 보인다.
 *       던지는 예외를 우리가 골랐으므로 실제 DB가 그 예외를 던지는지는 증명하지 못한다.</li>
 *   <li>C-2 다른 연결의 락: 별도 커넥션이 같은 event_id를 insert한 채 commit하지 않고 버틴다. consumer의 insert는 unique 락을
 *       기다리다 진짜 락 타임아웃(MySQL 1205)을 받는다. 그 예외가 "다시 하면 풀리는 실패"로 분류돼 멈춤으로 가는지를 증명한다.</li>
 * </ul>
 * 재시작은 KafkaListenerEndpointRegistry로 사람을 흉내 낸다(D-20). committed offset은 admin으로 group의 값을 읽는다.
 *
 * <p>락 대기 시간은 이 테스트 컨텍스트의 consumer DB 연결에서만 2초로 줄인다(D-28) — HikariCP connection-init-sql로 연결마다
 * {@code SET SESSION}을 건다. 공용 MySQL 컨테이너의 전역 값과 운영 설정은 건드리지 않는다.
 */
@SpringBootTest(properties = "datasource.mysql-jpa.main.connection-init-sql=SET SESSION innodb_lock_wait_timeout = 2")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderConfirmedListenerTest {

    private static final String TOPIC = KafkaProbe.unique("order-events-listener");
    private static final String GROUP = KafkaProbe.unique("purchase-activity-listener");
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final int PARTITION = 0;

    @DynamicPropertySource
    static void topic(DynamicPropertyRegistry registry) {
        KafkaTestContainers.createTopic(TOPIC);
        registry.add("events.order-confirmed.topic", () -> TOPIC);
        registry.add("events.order-confirmed.group-id", () -> GROUP);
    }

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    /** C-1의 이음매. 스텁하지 않으면 진짜 구현 그대로다. */
    @MockitoSpyBean
    private ProcessedOrderConfirmationRepository processedRepository;

    /** listener가 받은 예외를 관찰만 한다(동작은 진짜 그대로). */
    @MockitoSpyBean
    private PurchaseActivityFacade facade;

    @Autowired
    private ProductPurchaseActivityRepository activityRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final List<Throwable> listenerFailures = new CopyOnWriteArrayList<>();
    private KafkaProbe kafka;

    @BeforeAll
    void openProbe() {
        kafka = new KafkaProbe(TOPIC, GROUP);
    }

    @AfterAll
    void cleanUp() {
        kafka.close();
        databaseCleanUp.truncateAllTables();
    }

    @BeforeEach
    void observeFailures() {
        listenerFailures.clear();
        doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } catch (Throwable failure) {
                listenerFailures.add(failure);
                throw failure;
            }
        }).when(facade).handle(any(), any(), any());
        container().start(); // 앞 테스트가 멈춘 채 끝났더라도 같은 출발선에서 시작한다
    }

    private MessageListenerContainer container() {
        return listenerRegistry.getListenerContainer(OrderConfirmedListener.LISTENER_ID);
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private RecordMetadata send(long orderId, long productId, int quantity) {
        return kafka.send(PARTITION, String.valueOf(orderId),
            json(orderId, now(), item(productId, quantity)).getBytes(StandardCharsets.UTF_8));
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

    @DisplayName("C-1: 저장 자리에서 DB 장애가 나면 ack하지 않고 container가 멈추며 offset은 실패한 기록에 고정된다. "
        + "장애를 걷고 다시 켜면 그 offset부터 이어 처리한다.")
    @Test
    void seamFailure_stopsAndResumesFromSameOffset() {
        // arrange — 정상 1건으로 출발선을 만든다
        RecordMetadata first = send(nextId(), nextId(), 1);
        awaitAcked(first);
        long failingOrder = nextId();
        long laterOrder = nextId();
        doThrow(new DataAccessResourceFailureException("주입된 DB 장애"))
            .when(processedRepository).insert(argThat((ProcessedOrderConfirmation p) -> p != null && p.getOrderId().equals(failingOrder)));

        // act 1 — 실패할 기록과, 멈춘 동안 쌓일 기록
        RecordMetadata failing = send(failingOrder, nextId(), 2);
        awaitStopped();
        RecordMetadata later = send(laterOrder, nextId(), 3);
        long committedWhileStopped = kafka.committed(PARTITION);

        // assert 1
        assertAll(
            () -> assertThat(listenerFailures).anySatisfy(f -> assertThat(f).isInstanceOf(DataAccessResourceFailureException.class)),
            () -> assertThat(committedWhileStopped).isEqualTo(failing.offset()),
            () -> assertThat(processed(failingOrder)).isFalse(),
            () -> assertThat(activityRepository.findAllByOrderId(failingOrder)).isEmpty(),
            () -> assertThat(processed(laterOrder)).isFalse()
        );

        // act 2 — 장애를 걷고 사람이 다시 켠다
        Mockito.reset(processedRepository);
        container().start();
        awaitAcked(later);

        // assert 2
        System.out.printf("[C-1] 정상 offset=%d → committed=%d, 실패 offset=%d → 멈춤 중 committed=%d (뒤에 쌓인 offset=%d), "
                + "재시작 뒤 committed=%d%n",
            first.offset(), first.offset() + 1, failing.offset(), committedWhileStopped, later.offset(), kafka.committed(PARTITION));
        assertAll(
            () -> assertThat(processed(failingOrder)).isTrue(),
            () -> assertThat(processed(laterOrder)).isTrue(),
            () -> assertThat(kafka.committed(PARTITION)).isEqualTo(later.offset() + 1)
        );
    }

    @DisplayName("C-2: 다른 연결이 같은 event_id를 잠그고 있으면 진짜 락 타임아웃(1205)으로 멈추고 offset은 고정된다. "
        + "락을 풀고 다시 켜면 그 offset부터 이어 처리한다. (D-28: 락 대기 2초는 이 테스트의 연결에서만)")
    @Test
    void realLockTimeout_stopsAndResumesFromSameOffset() throws SQLException {
        // arrange
        assertThat(sessionLockWaitTimeout()).isEqualTo(2);
        RecordMetadata first = send(nextId(), nextId(), 1);
        awaitAcked(first);
        long lockedOrder = nextId();
        long productId = nextId();

        try (Connection blocker = blockerConnection()) {
            holdProcessedRow(blocker, lockedOrder);

            // act 1
            RecordMetadata locked = send(lockedOrder, productId, 2);
            awaitStopped();
            long committedWhileStopped = kafka.committed(PARTITION);

            // assert 1
            assertAll(
                () -> assertThat(listenerFailures).anySatisfy(f -> assertThat(isLockWaitTimeout(f)).as(String.valueOf(f)).isTrue()),
                () -> assertThat(committedWhileStopped).isEqualTo(locked.offset()),
                () -> assertThat(activityRepository.findAllByOrderId(lockedOrder)).isEmpty()
            );

            // act 2 — 락을 쥔 쪽이 롤백해 장애가 걷히고, 사람이 다시 켠다
            blocker.rollback();
            container().start();
            awaitAcked(locked);

            // assert 2
            System.out.printf("[C-2] 정상 offset=%d → committed=%d, 락에 막힌 offset=%d → 멈춤 중 committed=%d, 예외=%s, "
                    + "재시작 뒤 committed=%d%n",
                first.offset(), first.offset() + 1, locked.offset(), committedWhileStopped,
                rootCause(listenerFailures.get(0)), kafka.committed(PARTITION));
            assertAll(
                () -> assertThat(processed(lockedOrder)).isTrue(),
                () -> assertThat(activityRepository.findAllByOrderId(lockedOrder)).hasSize(1),
                () -> assertThat(kafka.committed(PARTITION)).isEqualTo(locked.offset() + 1)
            );
        }
    }

    private int sessionLockWaitTimeout() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet rs = connection.createStatement().executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** consumer와 다른 연결. 공용 MySQL 픽스처가 넣어 둔 주소로 직접 연다(풀 밖이라 락 대기 설정도 따로다). */
    private static Connection blockerConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(
            System.getProperty("datasource.mysql-jpa.main.jdbc-url"),
            System.getProperty("datasource.mysql-jpa.main.username"),
            System.getProperty("datasource.mysql-jpa.main.password"));
        connection.setAutoCommit(false);
        return connection;
    }

    /** 같은 event_id·order_id를 insert하고 commit하지 않는다. consumer의 insert는 이 행의 unique 락을 기다린다. */
    private static void holdProcessedRow(Connection blocker, long orderId) throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        try (PreparedStatement insert = blocker.prepareStatement(
            "INSERT INTO processed_order_confirmation (event_id, order_id, fact, processed_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, "order-confirmed:v1:" + orderId);
            insert.setLong(2, orderId);
            insert.setString(3, "blocker");
            insert.setTimestamp(4, now);
            insert.setTimestamp(5, now);
            insert.setTimestamp(6, now);
            insert.executeUpdate();
        }
    }

    private static boolean isLockWaitTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1205) {
                return true;
            }
        }
        return false;
    }

    private static String rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return failure.getClass().getSimpleName() + " ← " + root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
