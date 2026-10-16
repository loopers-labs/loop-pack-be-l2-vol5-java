package com.loopers.interfaces.api.product;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.loopers.application.brand.BrandFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.product.Price;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductCacheOutageE2ETest {

    private static final int OWN_REDIS_PORT = 16379;
    private static final Duration BOUNDED_WAIT = Duration.ofSeconds(2);
    private static final Duration RECOVERY_LIMIT = Duration.ofSeconds(15);
    private static final Duration NO_REDIS_WAIT = Duration.ofMillis(300);
    private static final String CUSTOMER = "/api/v1/products";
    private static final Long USER = 1L;

    private static final ParameterizedTypeReference<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> PAGE =
        new ParameterizedTypeReference<>() {};

    private static final GenericContainer<?> OWN_REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
        .withCommand("redis-server", "--save", "", "--appendonly", "no")
        .withExposedPorts(6379)
        .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
            new PortBinding(Ports.Binding.bindPort(OWN_REDIS_PORT), new ExposedPort(6379))));

    static {
        OWN_REDIS.start();
    }

    @DynamicPropertySource
    static void useOwnRedis(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", OWN_REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> OWN_REDIS_PORT);
        registry.add("datasource.redis.replicas[0].host", OWN_REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> OWN_REDIS_PORT);
    }

    @AfterAll
    static void stopOwnRedis() {
        OWN_REDIS.stop();
    }

    private final TestRestTemplate testRestTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;

    private Long brandId;

    @Autowired
    ProductCacheOutageE2ETest(
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

    @BeforeEach
    void setUp() {
        brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (isPaused()) {
            DockerClientFactory.instance().client().unpauseContainerCmd(OWN_REDIS.getContainerId()).exec();
        }
        awaitCacheWritable();
        OWN_REDIS.execInContainer("redis-cli", "FLUSHALL");
        databaseCleanUp.truncateAllTables();
    }

    private void awaitCacheWritable() throws Exception {
        long deadline = System.nanoTime() + RECOVERY_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            list(firstPriceAscPage());
            if (!redisCli("--scan", "--pattern", "product:list:v1:brand=*").isEmpty()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Redis 가 다시 쓰이지 않았다");
    }

    private static boolean isPaused() {
        return Boolean.TRUE.equals(DockerClientFactory.instance().client()
            .inspectContainerCmd(OWN_REDIS.getContainerId()).exec().getState().getPaused());
    }

    private static void pauseRedis() {
        DockerClientFactory.instance().client().pauseContainerCmd(OWN_REDIS.getContainerId()).exec();
    }

    private static void restartRedis() {
        DockerClientFactory.instance().client().restartContainerCmd(OWN_REDIS.getContainerId()).exec();
    }

    private static String redisCli(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "redis-cli";
        System.arraycopy(args, 0, command, 1, args.length);
        return OWN_REDIS.execInContainer(command).getStdout().trim();
    }

    private Long product(String name, long price) {
        return productFacade.register(brandId, name, Price.of(price)).getId();
    }

    private ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> list(String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(USER));
        return testRestTemplate.exchange(CUSTOMER + query, HttpMethod.GET, new HttpEntity<>(headers), PAGE);
    }

    private ResponseEntity<String> changePriceAsAdmin(Long productId, long price) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return testRestTemplate.withBasicAuth("admin", "local-only-not-a-secret").exchange(
            "/api-admin/v1/products/" + productId, HttpMethod.PUT,
            new HttpEntity<>(Map.of("name", "셔츠", "price", price), headers), String.class);
    }

    private String firstPriceAscPage() {
        return "?brandId=" + brandId + "&sort=price_asc";
    }

    private record Timed<T>(T value, Duration elapsed) {}

    private static <T> Timed<T> timed(java.util.function.Supplier<T> call) {
        long started = System.nanoTime();
        T value = call.get();
        return new Timed<>(value, Duration.ofNanos(System.nanoTime() - started));
    }

    @DisplayName("CACHE-010 · Redis 가 멈추면 캐시 대상 조회는 제한된 시간만 기다리고 DB 결과를 준다 — 멈춘 동안 바뀐 원본도 보인다")
    @Test
    void servesFromDatabaseWithinBoundedWaitWhileRedisIsDown() {
        Long productId = product("셔츠", 10_000);
        list(firstPriceAscPage());
        pauseRedis();
        jdbcTemplate.update("UPDATE product SET price = ? WHERE id = ?", 5_000, productId);

        Timed<ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>>> during =
            timed(() -> list(firstPriceAscPage()));

        assertAll(
            () -> assertThat(during.value().getStatusCode().value()).isEqualTo(200),
            () -> assertThat(during.value().getBody().data().items().get(0).price()).as("캐시가 아니라 원본").isEqualTo(5_000L),
            () -> assertThat(during.elapsed()).as("Redis 대기가 제한된다").isLessThan(BOUNDED_WAIT)
        );
    }

    @DisplayName("Q-7 · Redis 가 응답 없이 멈춘 동안 캐시 대상 조회가 계속 몰려도, 요청마다 Redis 를 기다리지 않아 처리량이 유지된다")
    @Test
    void keepsBoundedLatencyUnderSustainedLoadWhileRedisIsDown() throws Exception {
        product("셔츠", 10_000);
        list(firstPriceAscPage());
        pauseRedis();

        int workers = 20;
        long until = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<List<Duration>>> results = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            results.add(executor.submit(() -> {
                List<Duration> elapsed = new ArrayList<>();
                while (System.nanoTime() < until) {
                    Timed<ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>>> call = timed(() -> list(firstPriceAscPage()));
                    assertThat(call.value().getStatusCode().value()).isEqualTo(200);
                    elapsed.add(call.elapsed());
                }
                return elapsed;
            }));
        }
        List<Duration> all = new ArrayList<>();
        for (Future<List<Duration>> result : results) {
            all.addAll(result.get(60, TimeUnit.SECONDS));
        }
        executor.shutdown();
        Duration slowest = all.stream().max(Duration::compareTo).orElseThrow();

        assertAll(
            () -> assertThat(slowest).as("가장 느린 요청도 Redis 대기 한도 안이다").isLessThan(BOUNDED_WAIT),
            () -> assertThat(all.size()).as("요청마다 Redis 대기 한도를 기다리지 않는다 — 6 초 동안 스레드마다 50 건 넘게").isGreaterThan(workers * 50)
        );
    }

    @DisplayName("CACHE-010 · Redis 가 멈춰도 캐시를 거치지 않는 조회는 기다리지 않는다")
    @Test
    void uncachedRequestsDoNotWaitForRedis() {
        product("셔츠", 10_000);
        pauseRedis();

        Timed<ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>>> likesDesc =
            timed(() -> list("?brandId=" + brandId + "&sort=likes_desc"));

        assertAll(
            () -> assertThat(likesDesc.value().getStatusCode().value()).isEqualTo(200),
            () -> assertThat(likesDesc.elapsed()).isLessThan(Duration.ofMillis(500))
        );
    }

    @DisplayName("Q-5 · Redis 가 멈춘 동안의 관리자 수정은 커밋되고, 무효화를 포함해 제한된 시간 안에 응답한다")
    @Test
    void adminWriteCommitsWithinBoundedTimeWhileRedisIsDown() {
        Long productId = product("셔츠", 10_000);
        pauseRedis();

        Timed<ResponseEntity<String>> write = timed(() -> changePriceAsAdmin(productId, 5_000));

        assertAll(
            () -> assertThat(write.value().getStatusCode().value()).isEqualTo(200),
            () -> assertThat(jdbcTemplate.queryForObject("SELECT price FROM product WHERE id = ?", Long.class, productId))
                .isEqualTo(5_000L),
            () -> assertThat(write.elapsed()).isLessThan(BOUNDED_WAIT)
        );
    }

    @DisplayName("Q-7 · Redis 가 멈춰 캐시를 건너뛰는 동안의 관리자 수정은 무효화를 위해 Redis 를 기다리지 않는다")
    @Test
    void adminWriteDoesNotWaitForRedisWhileSkippingIt() {
        Long productId = product("셔츠", 10_000);
        changePriceAsAdmin(productId, 9_000);
        pauseRedis();
        list(firstPriceAscPage());

        Timed<ResponseEntity<String>> write = timed(() -> changePriceAsAdmin(productId, 5_000));

        assertAll(
            () -> assertThat(write.value().getStatusCode().value()).isEqualTo(200),
            () -> assertThat(jdbcTemplate.queryForObject("SELECT price FROM product WHERE id = ?", Long.class, productId))
                .isEqualTo(5_000L),
            () -> assertThat(write.elapsed()).as("건너뛰는 동안은 Redis 대기 한도(200 ms)를 기다리지 않는다").isLessThan(NO_REDIS_WAIT)
        );
    }

    @DisplayName("CACHE-009 · Redis 가 멈춰도 주문 확정은 DB 의 재고 · 포인트 규칙대로 처리되고, 마지막 재고를 팔아 무효화가 일어나도 제한된 시간 안에 응답한다")
    @Test
    void confirmationIsUnaffectedWhileRedisIsDown() {
        Long productId = product("셔츠", 10_000);
        productFacade.adjustStock(productId, Quantity.of(1));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", String.valueOf(USER));
        testRestTemplate.exchange("/api/v1/points/charge", HttpMethod.POST,
            new HttpEntity<>(Map.of("amount", 10_000), headers), String.class);
        ResponseEntity<Map> placed = testRestTemplate.exchange("/api/v1/orders", HttpMethod.POST,
            new HttpEntity<>(Map.of("lines", List.of(Map.of("productId", productId, "quantity", 1))), headers), Map.class);
        Object orderId = ((Map<?, ?>) placed.getBody().get("data")).get("id");
        pauseRedis();

        Timed<ResponseEntity<String>> confirm = timed(() -> testRestTemplate.exchange(
            "/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST, new HttpEntity<>(null, headers), String.class));

        assertAll(
            () -> assertThat(confirm.value().getStatusCode().value()).isEqualTo(200),
            () -> assertThat(confirm.elapsed()).isLessThan(BOUNDED_WAIT),
            () -> assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM product WHERE id = ?", Integer.class, productId))
                .as("재고는 DB 에서 차감").isZero(),
            () -> assertThat(jdbcTemplate.queryForObject("SELECT balance FROM user_point WHERE user_id = ?", Long.class, USER))
                .as("포인트는 DB 에서 차감").isZero()
        );
    }

    @DisplayName("CACHE-010 · Redis 가 재시작되면(데이터 유실) 다시 연결되어 정확한 값으로 다시 채운다")
    @Test
    void refillsCorrectlyAfterRedisRestart() throws Exception {
        Long productId = product("셔츠", 10_000);
        list(firstPriceAscPage());
        String keysBefore = redisCli("--scan", "--pattern", "product:list:v1:brand=*");

        restartRedis();
        jdbcTemplate.update("UPDATE product SET price = ? WHERE id = ?", 5_000, productId);

        long deadline = System.nanoTime() + RECOVERY_LIMIT.toNanos();
        String keysAfter = "";
        long price = -1;
        while (System.nanoTime() < deadline && keysAfter.isEmpty()) {
            ResponseEntity<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> response = list(firstPriceAscPage());
            price = response.getBody().data().items().get(0).price();
            keysAfter = redisCli("--scan", "--pattern", "product:list:v1:brand=*");
        }
        long recoveredPrice = price;
        String refilled = keysAfter;

        assertAll(
            () -> assertThat(keysBefore).as("재시작 전에 캐시가 있었다").isNotEmpty(),
            () -> assertThat(refilled).as("재시작 뒤 다시 채워졌다").isNotEmpty(),
            () -> assertThat(recoveredPrice).as("다시 채운 값은 원본과 같다").isEqualTo(5_000L)
        );
    }
}
