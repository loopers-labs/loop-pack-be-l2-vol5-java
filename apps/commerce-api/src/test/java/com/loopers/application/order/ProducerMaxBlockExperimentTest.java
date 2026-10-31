package com.loopers.application.order;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.testcontainers.KafkaTestBroker;
import com.loopers.utils.DatabaseCleanUp;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 브로커가 꺼졌을 때 주문 확정 요청이 send() 1단계(metadata 확보)에서 얼마나 묶이는지 잰다 (ADR 0051의 max.block.ms=5000).
 * <ul>
 *   <li>경우 1: 이미 한 번 보내 metadata를 기억하는 producer. 브로커를 끈 뒤에도 send()는 버퍼에 넣고 바로 돌아온다.</li>
 *   <li>경우 2: metadata가 없는 새 producer. send()가 max.block.ms 동안 기다린 뒤 실패한다. AFTER_COMMIT은 커넥션 반납 전이라
 *       그동안 DB 커넥션도 쥐고 있는지 Hikari의 사용 중 커넥션 수로 본다.</li>
 * </ul>
 * 시간을 재는 실험이라 평소 check에서 뺀다. 실행: {@code ./gradlew :apps:commerce-api:experimentTest --tests '*ProducerMaxBlockExperimentTest'}
 */
@Tag("experiment")
@ExtendWith(OutputCaptureExtension.class)
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
    void cachedMetadataReturnsImmediatelyButMissingMetadataBlocksTheRequestAndItsConnection(CapturedOutput output)
        throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER.bootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
        PG.reset();
        Long userId = registration.register().getId();
        Long brandId = brands.save(new BrandModel("max.block 실험 브랜드", null)).getId();
        Long productId = products.save(new ProductModel(brandId, "양말", 1_000L, 100)).getId();

        // 준비: 브로커가 살아 있을 때 한 건을 보내 producer가 metadata를 기억하게 한다.
        Measured warmUp = confirm(userId, productId, "TEST-TX-1");
        kafka.flush();
        assertThat(output.getAll()).contains("Order event published:");

        BROKER.close();

        // 경우 1: metadata가 있으면 send()는 바로 돌아온다. 실패는 나중에 2단계(delivery.timeout.ms)에서 드러난다.
        Measured cached = confirm(userId, productId, "TEST-TX-2");

        // 경우 2: producer를 새로 만들어 metadata가 없게 한다. send()가 max.block.ms(5초) 동안 기다린다.
        kafka.getProducerFactory().reset();
        Measured missing = confirm(userId, productId, "TEST-TX-3");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("warmUpMs", warmUp.elapsed.toMillis());
        report.put("cachedMetadataMs", cached.elapsed.toMillis());
        report.put("missingMetadataMs", missing.elapsed.toMillis());
        report.put("missingMetadataMaxActiveConnectionsAfterOneSecond", missing.maxActiveAfterOneSecond);
        report.put("cachedMetadataMaxActiveConnectionsAfterOneSecond", cached.maxActiveAfterOneSecond);
        report.put("poolSize", dataSource.getMaximumPoolSize());
        report.put("synchronousFailureLogged", output.getAll().contains("Order event publication failed after commit"));
        System.out.println("MAX_BLOCK_EXPERIMENT " + report);

        assertThat(cached.elapsed).isLessThan(Duration.ofSeconds(1));
        assertThat(missing.elapsed).isBetween(Duration.ofMillis(4_500), Duration.ofSeconds(8));
        assertThat(missing.maxActiveAfterOneSecond).isGreaterThanOrEqualTo(1);
        assertThat(output.getAll()).contains("Order event publication failed after commit");
        assertThat(orderRepository.findById(missing.orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    /** 실제 PG 결과 적용으로 확정하며 걸린 시간과, 시작 1초 뒤부터 끝날 때까지 본 사용 중 커넥션 수의 최댓값을 잰다. */
    private Measured confirm(Long userId, Long productId, String transactionKey) throws Exception {
        Long orderId = orders.create(userId, List.of(new OrderFacade.OrderLine(productId, 1))).id();
        orders.confirm(userId, orderId, new OrderFacade.PaymentCommand(
            "max-block-" + transactionKey, "TEST", "0000-0000-0000-0000"));
        PG.changeStatus(transactionKey, "SUCCESS");

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
            orders.reconcile(userId, orderId);
        } finally {
            running.set(false);
            sampler.join(1_000);
        }
        return new Measured(orderId, Duration.ofNanos(System.nanoTime() - started), maxActive.get());
    }

    private record Measured(Long orderId, Duration elapsed, int maxActiveAfterOneSecond) {}

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
