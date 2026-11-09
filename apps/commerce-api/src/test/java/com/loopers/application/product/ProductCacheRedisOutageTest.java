package com.loopers.application.product;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderItemCommand;
import com.loopers.application.payment.PaymentFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductSortType;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.pg.PaymentTestSupport;
import com.loopers.support.pg.TestPgResetExtension;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 이 테스트만 쓰는 Redis 컨테이너를 실제로 멈췄다가 다시 시작한다.
 * 다른 테스트가 함께 쓰는 Redis 컨테이너는 건드리지 않고, 재시작해도 같은 주소로 다시 붙도록 호스트 포트를 고정한다.
 */
@SpringBootTest
@ExtendWith(TestPgResetExtension.class)
class ProductCacheRedisOutageTest {

    private static final long BUYER_ID = 1L;
    private static final int REDIS_PORT = freePort();
    private static final GenericContainer<?> OUTAGE_REDIS = new GenericContainer<>(DockerImageName.parse("redis:latest"))
        .withExposedPorts(6379)
        .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
            new PortBinding(Ports.Binding.bindPort(REDIS_PORT), new ExposedPort(6379))
        ));

    static {
        OUTAGE_REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", OUTAGE_REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS_PORT);
        registry.add("datasource.redis.replicas[0].host", OUTAGE_REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS_PORT);
    }

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final DockerClient docker = DockerClientFactory.instance().client();
    private Long nikeId;
    private Long runnerId;

    @BeforeEach
    void setUp() {
        nikeId = brandJpaRepository.save(new BrandModel("나이키")).getId();
        runnerId = productJpaRepository.save(new ProductModel(nikeId, "runner", 30_000L, 5)).getId();
        productJpaRepository.save(new ProductModel(nikeId, "slipper", 10_000L, 3));
    }

    @AfterEach
    void tearDown() {
        if (!isRedisRunning()) {
            docker.startContainerCmd(OUTAGE_REDIS.getContainerId()).exec();
        }
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("Redis가 멈추면 조회는 제한된 대기 뒤 DB 결과를 반환하고 쓰기·주문은 그대로 처리되며, 다시 시작하면 새 값으로 캐시를 다시 채운다.")
    @Test
    void fallsBackToDbWhileRedisIsDown_andRefillsAfterRestart() {
        // arrange — 정상 상태에서 miss 뒤 hit
        double hitsBeforeWarmUp = cacheCount("get", "hit");
        List<ProductInfo> beforeOutage = list();
        assertThat(list()).isEqualTo(beforeOutage);
        assertThat(cacheCount("get", "hit") - hitsBeforeWarmUp).as("정상 상태의 hit").isEqualTo(1);

        // act 1 — 테스트가 연결한 Redis(master와 replica가 같은 컨테이너)를 멈춘다
        docker.stopContainerCmd(OUTAGE_REDIS.getContainerId()).exec();
        double errorsBefore = cacheCount("get", "error");
        long startedAt = System.nanoTime();
        List<ProductInfo> duringOutage = list();
        Duration readDuringOutage = Duration.ofNanos(System.nanoTime() - startedAt);

        // act 2 — Redis가 멈춘 동안의 관리자 변경과 주문 확정
        productFacade.updateProduct(runnerId, "runner", 5_000L);
        List<ProductInfo> afterChangeDuringOutage = list();
        Long orderId = orderFacade.createOrder(BUYER_ID, List.of(new OrderItemCommand(runnerId, 2))).id();
        PaymentTestSupport.startPayment(paymentFacade, orderId, BUYER_ID);

        // assert 1
        assertAll(
            () -> assertThat(duringOutage).as("Redis가 멈춰도 DB 결과").isEqualTo(beforeOutage),
            () -> assertThat(readDuringOutage).as("Redis를 기다리는 시간은 제한된다").isLessThan(Duration.ofSeconds(1)),
            () -> assertThat(cacheCount("get", "error") - errorsBefore).as("miss가 아니라 Redis 오류로 기록").isGreaterThanOrEqualTo(1),
            () -> assertThat(priceOf(afterChangeDuringOutage, "runner")).as("변경은 commit되고 조회에 반영").isEqualTo(5_000L),
            () -> assertThat(productJpaRepository.findById(runnerId).orElseThrow().getStock()).as("결제 시작은 DB 규칙대로 재고 확보").isEqualTo(3)
        );

        // act 3 — 같은 Redis를 다시 시작하고, 다시 연결될 때까지 기다린다
        docker.startContainerCmd(OUTAGE_REDIS.getContainerId()).exec();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            double errors = cacheCount("get", "error");
            list();
            assertThat(cacheCount("get", "error")).as("Redis 조회가 다시 성공").isEqualTo(errors);
        });
        double hitsAfterRestart = cacheCount("get", "hit");
        List<ProductInfo> refilled = list();

        // assert 2
        assertAll(
            () -> assertThat(cacheCount("get", "hit") - hitsAfterRestart).as("재시작 뒤 다시 채운 값을 hit").isEqualTo(1),
            () -> assertThat(priceOf(refilled, "runner")).as("재시작 전 캐시가 아니라 새 값").isEqualTo(5_000L),
            () -> assertThat(stockOf(refilled, "runner")).isEqualTo(3)
        );
    }

    private List<ProductInfo> list() {
        return productFacade.getProducts(nikeId, ProductSortType.PRICE_ASC, PageRequest.of(0, 20)).getContent();
    }

    private Long priceOf(List<ProductInfo> products, String name) {
        return products.stream().filter(product -> product.name().equals(name)).findFirst().orElseThrow().price();
    }

    private int stockOf(List<ProductInfo> products, String name) {
        return products.stream().filter(product -> product.name().equals(name)).findFirst().orElseThrow().stock();
    }

    private double cacheCount(String operation, String result) {
        return meterRegistry.counter("product.list.cache", "op", operation, "result", result).count();
    }

    private boolean isRedisRunning() {
        return Boolean.TRUE.equals(docker.inspectContainerCmd(OUTAGE_REDIS.getContainerId()).exec().getState().getRunning());
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
