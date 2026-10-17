package com.loopers.interfaces.api.product;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.loopers.application.brand.BrandFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.product.Price;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductCacheAofRestartE2ETest {

    private static final int OWN_REDIS_PORT = 16380;
    private static final Duration RECOVERY_LIMIT = Duration.ofSeconds(20);
    private static final String PAGE_KEYS = "product:list:v1:brand=*";
    private static final Long USER = 1L;

    private static final ParameterizedTypeReference<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> PAGE =
        new ParameterizedTypeReference<>() {};

    private static final GenericContainer<?> AOF_REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
        .withCommand("redis-server", "--appendonly", "yes", "--appendfsync", "always", "--save", "")
        .withExposedPorts(6379)
        .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
            new PortBinding(Ports.Binding.bindPort(OWN_REDIS_PORT), new ExposedPort(6379))));

    static {
        AOF_REDIS.start();
    }

    @DynamicPropertySource
    static void useAofRedis(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", AOF_REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> OWN_REDIS_PORT);
        registry.add("datasource.redis.replicas[0].host", AOF_REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> OWN_REDIS_PORT);
    }

    @AfterAll
    static void stopAofRedis() {
        AOF_REDIS.stop();
    }

    private final TestRestTemplate testRestTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;

    @Autowired
    ProductCacheAofRestartE2ETest(
        TestRestTemplate testRestTemplate,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp,
        BrandFacade brandFacade,
        ProductFacade productFacade
    ) {
        this.testRestTemplate = testRestTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (!isRunning()) {
            DockerClientFactory.instance().client().startContainerCmd(AOF_REDIS.getContainerId()).exec();
        }
        redisCli("FLUSHALL");
        databaseCleanUp.truncateAllTables();
    }

    private static boolean isRunning() {
        return Boolean.TRUE.equals(DockerClientFactory.instance().client()
            .inspectContainerCmd(AOF_REDIS.getContainerId()).exec().getState().getRunning());
    }

    private static String redisCli(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "redis-cli";
        System.arraycopy(args, 0, command, 1, args.length);
        return AOF_REDIS.execInContainer(command).getStdout().trim();
    }

    private static long secondsLeft(String key) throws Exception {
        return Long.parseLong(redisCli("TTL", key));
    }

    private long listedPrice(Long brandId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(USER));
        ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response = testRestTemplate.exchange(
            "/api/v1/products?brandId=" + brandId + "&sort=price_asc", HttpMethod.GET, new HttpEntity<>(headers), PAGE);
        return response.getBody().data().items().get(0).price();
    }

    private ResponseEntity<String> changePriceAsAdmin(Long productId, long price) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return testRestTemplate.withBasicAuth("admin", "local-only-not-a-secret").exchange(
            "/api-admin/v1/products/" + productId, HttpMethod.PUT,
            new HttpEntity<>(Map.of("name", "셔츠", "price", price), headers), String.class);
    }

    private long awaitPrice(Long brandId, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + RECOVERY_LIMIT.toNanos();
        long price = listedPrice(brandId);
        while (price != expected && System.nanoTime() < deadline) {
            Thread.sleep(200);
            price = listedPrice(brandId);
        }
        return price;
    }

    @DisplayName("Q-5 · AOF 를 켠 Redis 가 멈춘 동안 무효화가 실패하면, 재시작 뒤 옛 키가 만료 시각과 함께 되살아나 옛 가격이 hit 로 보이고, 만료(키 삭제로 앞당김) 뒤 새 가격이 된다")
    @Test
    void staleKeySurvivesAofRestartOnlyUntilItsOriginalTtl() throws Exception {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        Long productId = productFacade.register(brandId, "셔츠", Price.of(10_000)).getId();
        listedPrice(brandId);
        String key = redisCli("--scan", "--pattern", PAGE_KEYS).lines().filter(k -> k.contains("brand=" + brandId)).findFirst().orElseThrow();
        long ttlBeforeStop = secondsLeft(key);

        DockerClientFactory.instance().client().stopContainerCmd(AOF_REDIS.getContainerId()).exec();
        ResponseEntity<String> write = changePriceAsAdmin(productId, 5_000);
        DockerClientFactory.instance().client().startContainerCmd(AOF_REDIS.getContainerId()).exec();

        long staleAfterRestart = awaitPrice(brandId, 10_000);
        long ttlAfterRestart = secondsLeft(key);
        redisCli("DEL", key);

        assertAll(
            () -> assertThat(write.getStatusCode().value()).as("커밋은 실패로 바뀌지 않는다").isEqualTo(200),
            () -> assertThat(jdbcTemplate.queryForObject("SELECT price FROM product WHERE id = ?", Long.class, productId))
                .isEqualTo(5_000L),
            () -> assertThat(staleAfterRestart).as("되살아난 옛 키가 hit 로 보인다").isEqualTo(10_000L),
            () -> assertThat(ttlAfterRestart).as("만료 시각도 함께 되살아나 TTL 이 처음부터 다시 시작되지 않는다")
                .isBetween(1L, ttlBeforeStop),
            () -> assertThat(listedPrice(brandId)).as("만료 뒤 첫 조회는 원본과 같다").isEqualTo(5_000L)
        );
    }
}
