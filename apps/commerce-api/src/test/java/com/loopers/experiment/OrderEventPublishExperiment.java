package com.loopers.experiment;

import com.loopers.application.order.facade.OrderPaymentFacade;
import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.vo.OrderLine;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.product.entity.Product;
import com.loopers.support.pg.TestPgServer;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6주차 D-75 의 max.block.ms 잠정값(1s)을 다시 정하기 위한 측정. 제품 코드가 아니며 기술 글 · 결정의 근거로 씀.
 * <ul>
 *   <li>첫 send: metadata 가 없는 새 producer 의 send() 호출이 막히는 시간 (하한 근거)</li>
 *   <li>broker 정지(docker pause): metadata 가 캐시된 producer 와, 재시작처럼 producer 를 새로 만든 경우의 reconcile 응답 시간</li>
 *   <li>그 동안 Hikari active 커넥션 최댓값: AFTER_COMMIT listener 가 막히는 동안 DB 커넥션을 쥐고 있는지</li>
 * </ul>
 * 시간 값은 매번 달라 assertion 은 걸지 않음. 결과는 build/experiment/order-event-publish.md 에 표로 남김.
 * 실행: RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*OrderEventPublishExperiment'
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
class OrderEventPublishExperiment {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublishExperiment.class);
    private static final Long USER_ID = 1L;
    private static final int REPEAT = 5;
    private static final Path REPORT = Path.of("build", "experiment", "order-event-publish.md");

    private record Sample(String scene, long millis, int maxActiveConnections, long activeMillis) {}

    @Autowired
    private OrderPaymentFacade orderPaymentFacade;

    @Autowired
    private ProducerFactory<Object, Object> producerFactory;

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Value("${order-event.topic}")
    private String topic;

    private final TestPgServer pg = TestPgServer.shared();

    @AfterEach
    void tearDown() {
        KafkaTestContainersConfig.container().ifPresent(this::unpause);
        databaseCleanUp.truncateAllTables();
    }

    private void pause(GenericContainer<?> container) {
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
    }

    private void unpause(GenericContainer<?> container) {
        Boolean paused = container.getDockerClient().inspectContainerCmd(container.getContainerId()).exec().getState().getPaused();
        if (Boolean.TRUE.equals(paused)) {
            container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
        }
    }

    /** reconcile 하면 SUCCESS 로 확정되는 PG 결제 대기 주문 */
    private Order pendingSuccess() {
        Order order = transactionTemplate.execute(status -> {
            Brand brand = new Brand("브랜드", null);
            entityManager.persist(brand);
            Product product = new Product(brand, "상품", 1_000L);
            product.changeStock(10);
            entityManager.persist(product);
            Order draft = Order.draft(USER_ID, List.of(new OrderLine(product.getId(), product.getName(), product.getPrice(), 1)),
                ZonedDateTime.now());
            entityManager.persist(draft);
            return draft;
        });
        orderPaymentFacade.confirm(USER_ID, order.getId(), new ConfirmCommand("attempt-" + order.getId(), "TEST", "0000-0000-0000-0000"));
        pg.complete(pg.transactionsOf(Payment.providerOrderIdOf(order.getId())).get(0).transactionKey(), "SUCCESS");
        return order;
    }

    /**
     * reconcile 하나의 응답 시간과, 그 동안 5ms 간격으로 본 active 커넥션 최댓값 · active 가 1 이상이던 시간.
     * 최댓값만으로는 TX 의 커넥션과 구분되지 않으므로, 막힌 1초 동안에도 쥐고 있었는지는 active 시간으로 봄
     */
    private Sample measureReconcile(String scene, Order order) throws InterruptedException, SQLException {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger maxActive = new AtomicInteger();
        AtomicLong activeNanos = new AtomicLong();
        Thread probe = new Thread(() -> {
            long last = System.nanoTime();
            while (running.get()) {
                int active = hikari.getHikariPoolMXBean().getActiveConnections();
                long now = System.nanoTime();
                maxActive.accumulateAndGet(active, Math::max);
                if (active > 0) {
                    activeNanos.addAndGet(now - last);
                }
                last = now;
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        probe.start();
        long started = System.nanoTime();
        orderPaymentFacade.reconcile(USER_ID, order.getId());
        long millis = (System.nanoTime() - started) / 1_000_000;
        running.set(false);
        probe.join();
        return new Sample(scene, millis, maxActive.get(), activeNanos.get() / 1_000_000);
    }

    private long firstSendBlockMillis() {
        Map<String, Object> config = Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.kafka.bootstrap-servers"),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        );
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            long started = System.nanoTime();
            producer.send(new ProducerRecord<>(topic, "probe", "probe"));
            return (System.nanoTime() - started) / 1_000_000;
        }
    }

    private long laterSendBlockMillis() {
        long started = System.nanoTime();
        kafkaTemplate.send(topic, "probe", "probe");
        return (System.nanoTime() - started) / 1_000_000;
    }

    @DisplayName("max.block.ms 재검토 측정")
    @Test
    void measure() throws Exception {
        GenericContainer<?> container = KafkaTestContainersConfig.container().orElseThrow();
        List<Sample> samples = new ArrayList<>();
        List<Long> firstSends = new ArrayList<>();
        List<Long> laterSends = new ArrayList<>();

        // 첫 send 의 막힘(새 producer, metadata 없음)과 그 뒤 send 의 막힘
        laterSendBlockMillis();
        for (int i = 0; i < REPEAT; i++) {
            firstSends.add(firstSendBlockMillis());
            laterSends.add(laterSendBlockMillis());
        }
        kafkaTemplate.flush();

        // broker 정상
        for (int i = 0; i < REPEAT; i++) {
            samples.add(measureReconcile("broker 정상", pendingSuccess()));
        }

        // broker 정지 · producer 의 metadata 가 캐시돼 있음 (실행 중 broker 장애)
        List<Order> warm = new ArrayList<>();
        for (int i = 0; i < REPEAT; i++) {
            warm.add(pendingSuccess());
        }
        pause(container);
        for (Order order : warm) {
            samples.add(measureReconcile("broker 정지 · metadata 캐시됨", order));
        }
        unpause(container);
        kafkaTemplate.flush();

        // broker 정지 · producer 를 새로 만듦 (broker 가 죽은 채 앱이 재시작한 경우)
        List<Order> cold = new ArrayList<>();
        for (int i = 0; i < REPEAT; i++) {
            cold.add(pendingSuccess());
        }
        pause(container);
        for (Order order : cold) {
            ((DefaultKafkaProducerFactory<Object, Object>) producerFactory).reset();
            samples.add(measureReconcile("broker 정지 · producer 새로 만듦", order));
        }
        unpause(container);

        writeReport(firstSends, laterSends, samples);
        assertThat(samples).hasSize(REPEAT * 3);
    }

    private void writeReport(List<Long> firstSends, List<Long> laterSends, List<Sample> samples) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("# 주문 확정 이벤트 발행 측정\n\n");
        report.append("max.block.ms=").append(producerFactory.getConfigurationProperties().get(ProducerConfig.MAX_BLOCK_MS_CONFIG)).append("\n\n");
        report.append("| 항목 | 값(ms) |\n|---|---|\n");
        report.append("| 새 producer 의 첫 send() 막힘 | ").append(firstSends).append(" |\n");
        report.append("| 같은 producer 의 다음 send() 막힘 | ").append(laterSends).append(" |\n\n");
        report.append("| 장면 | reconcile 응답(ms) | Hikari active 최대 | active 1 이상이던 시간(ms) |\n|---|---|---|---|\n");
        for (Sample sample : samples) {
            report.append("| ").append(sample.scene()).append(" | ").append(sample.millis()).append(" | ").append(sample.maxActiveConnections())
                .append(" | ").append(sample.activeMillis()).append(" |\n");
        }
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report.toString());
        log.info("[측정]\n{}", report);
    }
}
