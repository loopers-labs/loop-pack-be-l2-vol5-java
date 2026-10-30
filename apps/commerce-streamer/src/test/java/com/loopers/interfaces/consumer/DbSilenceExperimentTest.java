package com.loopers.interfaces.consumer;

import com.loopers.config.PurchaseActivityListenerConfig;
import com.loopers.contract.OrderConfirmedContract;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "kafka.topics.order-events=" + DbSilenceExperimentTest.TOPIC,
    "kafka.groups.purchase-activity=" + DbSilenceExperimentTest.TOPIC
})
class DbSilenceExperimentTest {

    static final String TOPIC = "exp-db-silence";

    private static final MySQLContainer<?> SILENT_DB = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
        .withDatabaseName("loopers")
        .withUsername("test")
        .withPassword("test")
        .withCommand("--transaction-isolation=READ-COMMITTED", "--character-set-server=utf8mb4");

    static {
        SILENT_DB.start();
    }

    @DynamicPropertySource
    static void silentDb(DynamicPropertyRegistry registry) {
        registry.add("datasource.mysql-jpa.main.jdbc-url", () -> "jdbc:mysql://" + SILENT_DB.getHost() + ":"
            + SILENT_DB.getFirstMappedPort() + "/" + SILENT_DB.getDatabaseName());
        registry.add("datasource.mysql-jpa.main.username", SILENT_DB::getUsername);
        registry.add("datasource.mysql-jpa.main.password", SILENT_DB::getPassword);
    }

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final KafkaListenerEndpointRegistry registry;
    private final MeterRegistry meterRegistry;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    DbSilenceExperimentTest(KafkaTemplate<String, byte[]> kafkaTemplate, KafkaListenerEndpointRegistry registry,
                            MeterRegistry meterRegistry, JdbcTemplate jdbcTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.registry = registry;
        this.meterRegistry = meterRegistry;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeAll
    static void createTopic() {
        KafkaTestContainersConfig.createTopic(TOPIC, 1, Map.of());
    }

    private static void silence(boolean paused) {
        var docker = DockerClientFactory.instance().client();
        if (paused) {
            docker.pauseContainerCmd(SILENT_DB.getContainerId()).exec();
        } else {
            docker.unpauseContainerCmd(SILENT_DB.getContainerId()).exec();
        }
    }

    private Map<String, Double> retriesByCause() {
        return meterRegistry.find("purchase.activity.retry").counters().stream()
            .collect(Collectors.toMap(counter -> counter.getId().getTag("cause"), Counter::count, Double::sum));
    }

    private double retries() {
        return retriesByCause().values().stream().mapToDouble(Double::doubleValue).sum();
    }

    private static void eventually(BooleanSupplier condition, Duration within) throws InterruptedException {
        long deadline = System.nanoTime() + within.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(within + " 안에 조건이 채워지지 않았다");
            }
            Thread.sleep(200);
        }
    }

    @DisplayName("T-5, ACT-010: DB 가 응답하지 않으면 socketTimeout(10 초)에 예외가 되어 그 record 를 다시 시도하고, DB 가 돌아오면 사람 없이 반영한다")
    @Test
    void retriesWhileDbIsSilentAndRecovers() throws Exception {
        double retriesBefore = retries();
        silence(true);
        long silencedAt = System.nanoTime();
        try {
            kafkaTemplate.send(new ProducerRecord<>(TOPIC, null, "42",
                OrderConfirmedContract.example().getBytes(StandardCharsets.UTF_8)))
                .get(10, TimeUnit.SECONDS);

            eventually(() -> retries() > retriesBefore, Duration.ofSeconds(40));
        } finally {
            silence(false);
        }
        Duration firstFailureAfter = Duration.ofNanos(System.nanoTime() - silencedAt);
        boolean runningWhileSilent =
            registry.getListenerContainer(PurchaseActivityListenerConfig.LISTENER_ID).isRunning();
        long resumedAt = System.nanoTime();

        eventually(() -> jdbcTemplate.queryForObject("select count(*) from purchase_activity", Integer.class) == 2,
            Duration.ofSeconds(90));
        Duration recoveredIn = Duration.ofNanos(System.nanoTime() - resumedAt);

        System.out.printf("T-5 첫 실패까지=%s, 원인=%s, 컨테이너 실행=%s, DB 복구 뒤 반영까지=%s%n",
            firstFailureAfter, retriesByCause(), runningWhileSilent, recoveredIn);
        assertThat(runningWhileSilent).isTrue();
        assertThat(firstFailureAfter).isLessThan(Duration.ofSeconds(40));
        assertThat(jdbcTemplate.queryForObject("select count(*) from order_confirmation_receipt", Integer.class))
            .isEqualTo(1);
    }
}
