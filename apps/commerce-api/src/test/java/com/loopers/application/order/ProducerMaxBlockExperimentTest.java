package com.loopers.application.order;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderOutbox;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.order.OrderOutboxJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.testcontainers.KafkaTestBroker;
import com.loopers.utils.DatabaseCleanUp;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 브로커가 꺼지고 producer에 metadata가 없을 때, send() 1단계 대기(ADR 0051의 max.block.ms=5000)가 어디에 걸리는지 잰다.
 * <p>
 * W6에서는 AFTER_COMMIT 직접 발행이라 주문 확정 요청이 약 5초 묶이고 그동안 DB 커넥션도 쥐고 있었다
 * (docs/week6/implementation-log.md의 당시 측정, W6 코드는 b0d84b4에서 실행). W7에서는 주문 확정이 Outbox 저장으로 끝나므로
 * 같은 조건에서 다음을 본다.
 * <ul>
 *   <li>주문 확정 요청: Kafka를 기다리지 않고 끝나며 Outbox가 PENDING으로 남는다.</li>
 *   <li>relay 한 회차: 대기를 relay가 대신 지고, 그동안 DB 커넥션을 쥐지 않는다. 실패 뒤 같은 행이 PENDING·오류 기록으로 남는다.</li>
 * </ul>
 * 시간을 재는 실험이라 평소 check에서 뺀다. 실행: {@code ./gradlew :apps:commerce-api:experimentTest --tests '*ProducerMaxBlockExperimentTest'}
 */
@Tag("experiment")
@SpringBootTest(properties = "commerce.order-events.enabled=true")
class ProducerMaxBlockExperimentTest {
    private static final KafkaTestBroker BROKER = new KafkaTestBroker();
    private static final FakePaymentServer PG = startPg();
    private static final String TOPIC = "order-events-max-block-" + UUID.randomUUID();

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
        registry.add("commerce.order-events.topic", () -> TOPIC);
        registry.add("spring.kafka.bootstrap-servers", BROKER::bootstrapServers);
    }

    @Autowired private UserRegistrationService registration;
    @Autowired private OrderFacade orders;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository products;
    @Autowired private OrderJpaRepository orderRepository;
    @Autowired private OrderOutboxJpaRepository outbox;
    @Autowired private OrderOutboxRelay relay;
    @Autowired private KafkaTemplate<Object, Object> kafka;
    @Autowired private HikariDataSource dataSource;
    @Autowired private DatabaseCleanUp cleanUp;

    @AfterEach
    void tearDown() {
        cleanUp.truncateAllTables();
    }

    @AfterAll
    static void closeResources() throws InterruptedException {
        try {
            PG.close();
        } finally {
            BROKER.close();
        }
    }

    @Test
    void brokerOutageDelaysRelayButNotOrderConfirmationAndHoldsNoConnection() throws Exception {
        PG.reset();
        Long userId = registration.register().getId();
        Long brandId = brands.save(new BrandModel("max.block 실험 브랜드", null)).getId();
        Long productId = products.save(new ProductModel(brandId, "양말", 1_000L, 100)).getId();

        // W6 경우 2와 같은 조건: 브로커를 끄고 producer를 새로 만들어 metadata가 없게 한다.
        BROKER.close();
        kafka.getProducerFactory().reset();

        Long orderId = orders.create(userId, List.of(new OrderFacade.OrderLine(productId, 1))).id();
        orders.confirm(userId, orderId, new OrderFacade.PaymentCommand(
            "max-block-TEST-TX-1", "TEST", "0000-0000-0000-0000"));
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        Measured confirmation = measure(() -> orders.reconcile(userId, orderId));

        OrderOutbox pending = outboxOf(orderId);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(pending.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(pending.getAttemptCount()).isZero();

        int[] published = new int[1];
        Measured relayRun = measure(() -> published[0] = relay.runOnce());

        OrderOutbox failed = outboxOf(orderId);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("confirmationMs", confirmation.elapsed.toMillis());
        report.put("confirmationMaxActiveConnectionsAfterOneSecond", confirmation.maxActiveAfterOneSecond);
        report.put("relayMs", relayRun.elapsed.toMillis());
        report.put("relayMaxActiveConnectionsAfterOneSecond", relayRun.maxActiveAfterOneSecond);
        report.put("poolSize", dataSource.getMaximumPoolSize());
        report.put("outboxStatus", failed.getStatus());
        report.put("attemptCount", failed.getAttemptCount());
        report.put("lastError", failed.getLastError());
        System.out.println("MAX_BLOCK_EXPERIMENT " + report);

        assertThat(confirmation.elapsed).as("주문 확정은 Kafka를 기다리지 않는다").isLessThan(Duration.ofSeconds(1));
        assertThat(relayRun.elapsed).as("max.block.ms 대기는 relay가 진다")
            .isBetween(Duration.ofMillis(4_500), Duration.ofSeconds(8));
        assertThat(relayRun.maxActiveAfterOneSecond).as("send 대기 중에는 DB 커넥션을 쥐지 않는다").isZero();
        assertThat(published[0]).isZero();
        assertThat(failed.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getLastError()).isNotBlank();
    }

    private OrderOutbox outboxOf(Long orderId) {
        return outbox.findByEventId("order-confirmed:v1:" + orderId).orElseThrow();
    }

    /** 걸린 시간과, 시작 1초 뒤부터 끝날 때까지 본 사용 중 커넥션 수의 최댓값을 잰다. */
    private Measured measure(Runnable action) throws InterruptedException {
        var running = new AtomicBoolean(true);
        var maxActive = new AtomicInteger();
        long started = System.nanoTime();
        Thread sampler = new Thread(() -> {
            while (running.get()) {
                if (System.nanoTime() - started > Duration.ofSeconds(1).toNanos()) {
                    maxActive.accumulateAndGet(dataSource.getHikariPoolMXBean().getActiveConnections(), Math::max);
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException stop) {
                    return;
                }
            }
        });
        sampler.start();
        try {
            action.run();
        } finally {
            running.set(false);
            sampler.join(1_000);
        }
        return new Measured(Duration.ofNanos(System.nanoTime() - started), maxActive.get());
    }

    private record Measured(Duration elapsed, int maxActiveAfterOneSecond) {}

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
