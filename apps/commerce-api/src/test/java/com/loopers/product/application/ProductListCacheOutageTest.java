package com.loopers.product.application;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.loopers.brand.application.port.in.BrandCommandUseCase;
import com.loopers.product.application.port.in.ProductCommandUseCase;
import com.loopers.product.application.port.in.ProductInfo;
import com.loopers.product.domain.ProductSort;
import com.loopers.support.persistence.SqlRecorder;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.loopers.support.persistence.SqlRecorder")
public class ProductListCacheOutageTest {

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);
    private static final int REDIS_PORT = freePort();
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
            .withCommand("redis-server", "--save", "", "--appendonly", "no")
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withPortBindings(new PortBinding(Ports.Binding.bindPort(REDIS_PORT), new ExposedPort(6379))));

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS_PORT);
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS_PORT);
    }

    @Autowired
    private ProductListQueryService productListQueryService;

    @Autowired
    private ProductQueryService productQueryService;

    @Autowired
    private BrandCommandUseCase brandCommandUseCase;

    @Autowired
    private ProductCommandUseCase productCommandUseCase;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        startRedis();
        databaseCleanUp.truncateAllTables();
    }

    @AfterAll
    static void stopContainer() {
        REDIS.stop();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void stopRedis() {
        REDIS.getDockerClient().stopContainerCmd(REDIS.getContainerId()).exec();
    }

    private Page<ProductInfo> list(Long brandId) {
        return productListQueryService.getProducts(brandId, ProductSort.PRICE_ASC, FIRST_PAGE);
    }

    private List<Long> prices(Page<ProductInfo> page) {
        return page.getContent().stream().map(ProductInfo::price).toList();
    }


    private static void startRedis() {
        boolean running = Boolean.TRUE.equals(
                REDIS.getDockerClient().inspectContainerCmd(REDIS.getContainerId()).exec().getState().getRunning()
        );
        if (!running) {
            REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    private boolean listIsServedFromCache(Long brandId) {
        list(brandId);
        SqlRecorder.clear();
        list(brandId);
        return SqlRecorder.statements().stream().noneMatch(sql -> sql.contains("from likes"));
    }

    @DisplayName("Redis가 멈추면 제한된 시간 안에 DB 결과를 돌려주고 쓰기도 성공하며, 다시 뜨면 새 결과로 캐시를 다시 채운다.")
    @Test
    void fallsBackToDbAndRefillsAfterRestart() throws Exception {
        Long brandId = brandCommandUseCase.create("브랜드", null).id();
        Long productId = productCommandUseCase.create(brandId, "A", 1_000, 10).id();
        assertThat(listIsServedFromCache(brandId)).isTrue();

        // act ① — Redis 중단
        stopRedis();
        long startedAt = System.nanoTime();
        Page<ProductInfo> duringOutage = CompletableFuture.supplyAsync(() -> list(brandId)).get(3, TimeUnit.SECONDS);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        productCommandUseCase.update(productId, "A", 2_000);

        // assert ① — 빈 목록·가격 0이 아니라 DB의 정상 결과, 대기는 제한된다
        assertThat(prices(duringOutage)).containsExactly(1_000L);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        assertThat(prices(productQueryService.getProducts(brandId, ProductSort.PRICE_ASC, FIRST_PAGE))).containsExactly(2_000L);
        assertThat(prices(list(brandId))).containsExactly(2_000L);


        // act ② — 같은 Redis 재시작
        startRedis();

        // assert ② — 다시 hit가 나고, 그 값은 장애 중에 바뀐 원본과 같다
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).until(() -> listIsServedFromCache(brandId));
        assertThat(prices(list(brandId))).containsExactly(2_000L);

    }

}
