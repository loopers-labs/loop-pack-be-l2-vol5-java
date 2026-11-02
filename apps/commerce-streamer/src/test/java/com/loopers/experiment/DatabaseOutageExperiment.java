package com.loopers.experiment;

import com.loopers.application.purchase.facade.PurchaseActivityFacade;
import com.loopers.interfaces.consumer.OrderConfirmedConsumer;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 6주차 D-87(재시도 상한)을 정하기 위한 실제 DB 장애 실험. 제품 코드가 아니며 결정의 근거로 씀.
 * <ul>
 *   <li>응답 없음: MySQL 컨테이너 docker pause. 연결 거부 · 끊김: offline_mode(관리자 외 세션을 끊고 새 접속 거부)</li>
 *   <li>재시작은 쓰지 않음. Docker 가 무작위 호스트 포트를 재시작마다 새로 배정해 앱이 옛 주소로 붙기 때문</li>
 *   <li>backoff 는 운영값(1s, ×2, 최대 30s), 상한만 60s 로 줄임. 상한(maxElapsedTime)은 간격의 합이라 실제로 멈추기까지의 벽시계 시간을 따로 잼</li>
 *   <li>시도 시각 · 예외 종류는 facade 호출을 감싸 기록. 시간 값은 매번 달라 assertion 은 분류 · 결과에만 걺</li>
 * </ul>
 * 실행: RUN_EXPERIMENT=true ./gradlew :apps:commerce-streamer:test --tests '*DatabaseOutageExperiment'
 */
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
@SpringBootTest(properties = {
    "order-event.topic=order-events-outage-experiment",
    "order-event.consumer-group=purchase-activity-outage-experiment",
    "order-event.retry.max-elapsed=60s"
})
class DatabaseOutageExperiment {

    private static final Logger log = LoggerFactory.getLogger(DatabaseOutageExperiment.class);
    private static final Path REPORT = Path.of("build", "experiment", "database-outage.md");

    private record Attempt(long atMillis, String outcome) {}

    private record Result(String scene, long outageSeconds, List<Attempt> attempts, boolean stopped,
                          long recoveryMillis, long dltAdded, long activities) {}

    @MockitoSpyBean
    private PurchaseActivityFacade purchaseActivityFacade;

    @Autowired
    private KafkaTemplate<String, byte[]> orderConfirmedDltTemplate;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Value("${order-event.topic}")
    private String topic;

    @Value("${order-event.consumer-group}")
    private String group;

    private final List<Attempt> attempts = new CopyOnWriteArrayList<>();
    private volatile Instant sceneStart = Instant.now();

    @AfterEach
    void tearDown() throws Exception {
        GenericContainer<?> mysql = MySqlTestContainersConfig.container().orElseThrow();
        unpause(mysql);
        setOfflineMode(mysql, false);
        databaseCleanUp.truncateAllTables();
    }

    private static void pause(GenericContainer<?> mysql) {
        mysql.getDockerClient().pauseContainerCmd(mysql.getContainerId()).exec();
    }

    private static void unpause(GenericContainer<?> mysql) {
        Boolean paused = mysql.getDockerClient().inspectContainerCmd(mysql.getContainerId()).exec().getState().getPaused();
        if (Boolean.TRUE.equals(paused)) {
            mysql.getDockerClient().unpauseContainerCmd(mysql.getContainerId()).exec();
        }
    }

    private static void setOfflineMode(GenericContainer<?> mysql, boolean on) throws IOException, InterruptedException {
        var result = mysql.execInContainer("mysql", "-uroot", "-ptest", "-e", "SET GLOBAL offline_mode = " + (on ? "ON" : "OFF"));
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private ConcurrentMessageListenerContainer<?, ?> container() {
        return (ConcurrentMessageListenerContainer<?, ?>) registry.getListenerContainer(OrderConfirmedConsumer.LISTENER_ID);
    }

    private RecordMetadata send(long orderId) throws Exception {
        String value = """
            {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:%d","orderId":%d,
             "confirmedAt":"2026-09-07T01:00:00.123456Z","items":[{"productId":7,"quantity":1}]}
            """.formatted(orderId, orderId);
        return orderConfirmedDltTemplate.send(topic, String.valueOf(orderId), value.getBytes(StandardCharsets.UTF_8))
            .get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private long committed(RecordMetadata sent) {
        TopicPartition partition = new TopicPartition(sent.topic(), sent.partition());
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            OffsetAndMetadata offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS).get(partition);
            return offset == null ? -1 : offset.offset();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long dltEnd(int partition) {
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.kafka.bootstrap-servers"),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            TopicPartition dlt = new TopicPartition(topic + ".DLT", partition);
            return consumer.endOffsets(List.of(dlt)).get(dlt);
        }
    }

    /** DB 가 멈춘 동안에는 엔티티 매니저로 읽을 수 없으므로 복구 뒤에만 부름 */
    private long activityCount(long orderId) {
        return transactionTemplate.execute(status -> ((Number) entityManager.createNativeQuery(
            "select count(*) from product_purchase_activity where order_id = " + orderId).getSingleResult()).longValue());
    }

    private static void waitUntil(String what, Duration limit, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(limit);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as(what).isBefore(deadline);
            Thread.sleep(200);
        }
    }

    private interface Fault {
        void apply(GenericContainer<?> mysql) throws Exception;
    }

    /** outage 가 null 이면 상한을 넘겨 멈출 때까지 장애를 유지하고, 멈추기까지의 벽시계 시간을 잼 */
    private Result scene(String name, long orderId, Duration outage, Fault down, Fault up) throws Exception {
        GenericContainer<?> mysql = MySqlTestContainersConfig.container().orElseThrow();
        attempts.clear();
        down.apply(mysql);
        sceneStart = Instant.now();
        RecordMetadata sent = send(orderId);
        long dltBefore = dltEnd(sent.partition());
        boolean stopped;
        long faultMillis;
        if (outage == null) {
            // 응답 없는 DB 에서는 호출이 돌아오지 않아 멈추지 않을 수 있음. max.poll.interval(5분)을 넘길 만큼만 기다리고 결과로 남김
            Instant deadline = Instant.now().plus(Duration.ofSeconds(400));
            while (container().isChildRunning() && Instant.now().isBefore(deadline)) {
                Thread.sleep(200);
            }
            stopped = !container().isChildRunning();
            faultMillis = Duration.between(sceneStart, Instant.now()).toMillis();
        } else {
            Thread.sleep(outage.toMillis());
            stopped = !container().isChildRunning();
            faultMillis = outage.toMillis();
        }
        up.apply(mysql);
        Instant upAt = Instant.now();
        if (stopped || outage == null) {
            container().stop();
            container().start();
        }
        waitUntil(name + " 회복 뒤 commit", Duration.ofSeconds(180), () -> committed(sent) == sent.offset() + 1);
        long recoveryMillis = Duration.between(upAt, Instant.now()).toMillis();
        Result result = new Result(name, faultMillis / 1000, List.copyOf(attempts), stopped, recoveryMillis,
            dltEnd(sent.partition()) - dltBefore, activityCount(orderId));
        log.info("[DB 장애] {}", result);
        return result;
    }

    @DisplayName("DB 가 응답하지 않거나 연결을 거부해도 일시적 오류로 재시도하다 회복하고, 상한을 넘기면 멈춘 뒤 재시작으로 회복한다.")
    @Test
    void measure() throws Exception {
        // 재시도 동안 facade 시도의 시각과 결과를 기록함
        doAnswer(invocation -> {
            long at = Duration.between(sceneStart, Instant.now()).toMillis();
            try {
                Object result = invocation.callRealMethod();
                attempts.add(new Attempt(at, "성공"));
                return result;
            } catch (Throwable e) {
                attempts.add(new Attempt(at, e.getClass().getSimpleName()));
                throw e;
            }
        }).when(purchaseActivityFacade).record(any());

        List<Result> results = new ArrayList<>();
        results.add(scene("응답 없음(pause) 20초 · 상한 안", 9_001L, Duration.ofSeconds(20),
            DatabaseOutageExperiment::pause, DatabaseOutageExperiment::unpause));
        results.add(scene("연결 거부(offline_mode) 20초 · 상한 안", 9_002L, Duration.ofSeconds(20),
            mysql -> setOfflineMode(mysql, true), mysql -> setOfflineMode(mysql, false)));
        results.add(scene("연결 거부(offline_mode) 멈출 때까지 · 상한(간격 합) 60초", 9_004L, null,
            mysql -> setOfflineMode(mysql, true), mysql -> setOfflineMode(mysql, false)));
        results.add(scene("응답 없음(pause) 멈출 때까지(최대 400초) · 상한(간격 합) 60초", 9_003L, null,
            DatabaseOutageExperiment::pause, DatabaseOutageExperiment::unpause));

        writeReport(results);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.dltAdded()).as(result.scene() + ": DB 오류가 DLT 로 가지 않음").isZero();
            assertThat(result.activities()).as(result.scene() + ": 회복 뒤 한 번 저장").isEqualTo(1);
        });
    }

    private void writeReport(List<Result> results) throws IOException {
        StringBuilder report = new StringBuilder("# DB 장애 실험\n\n");
        report.append("| 장면 | 장애 유지(s) | 정지 | 시도 수 | 시도 시각(장애 시작 기준, s) | 예외 종류 | 복구 뒤 commit 까지(ms) | DLT 추가 | 활동 |\n");
        report.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Result result : results) {
            Set<String> outcomes = new LinkedHashSet<>();
            result.attempts().forEach(attempt -> outcomes.add(attempt.outcome()));
            report.append("| ").append(result.scene())
                .append(" | ").append(result.outageSeconds())
                .append(" | ").append(result.stopped() ? "중단" : "안 멈춤")
                .append(" | ").append(result.attempts().size())
                .append(" | ").append(result.attempts().stream().map(a -> String.format("%.1f", a.atMillis() / 1000.0)).toList())
                .append(" | ").append(outcomes)
                .append(" | ").append(result.recoveryMillis())
                .append(" | ").append(result.dltAdded())
                .append(" | ").append(result.activities()).append(" |\n");
        }
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report.toString());
        log.info("[DB 장애]\n{}", report);
    }
}
