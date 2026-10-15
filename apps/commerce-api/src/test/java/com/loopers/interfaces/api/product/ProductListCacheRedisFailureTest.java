package com.loopers.interfaces.api.product;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 이 테스트만 쓰는 Redis 컨테이너를 따로 띄워 멈춘다. 공용 static 컨테이너는 건드리지 않고 FLUSHALL도 하지 않는다.
// docker pause는 연결을 유지한 채 응답만 멈추므로, 명령 타임아웃(1초) 뒤 DB로 응답하는 경로를 확인할 수 있다.
@SpringBootTest
@AutoConfigureMockMvc
class ProductListCacheRedisFailureTest {
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:latest"))
        .withExposedPorts(6379);
    // 명령 타임아웃 1초 기준으로 Redis 호출이 몇 번 겹쳐도 넉넉한 상한. Lettuce 기본값(60초)이면 넘는다.
    private static final long MAX_DEGRADED_MILLIS = 8000;
    // 이 클래스에만 적용하는 짧은 목록 TTL. 캐시를 채운 뒤 무효화 실패·복구·조회가 이 안에 끝나야 낡은 값을 볼 수 있다.
    private static final Duration LIST_TTL = Duration.ofSeconds(3);

    @Autowired private MockMvc mvc;
    @Autowired private BrandApplicationService brandApplicationService;
    @Autowired private ProductApplicationService productApplicationService;
    @Autowired private UserJpaRepository users;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private long productId;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        REDIS.start();
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS.getMappedPort(6379));
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS.getMappedPort(6379));
        registry.add("commerce.product-list-cache.ttl", () -> LIST_TTL.toSeconds() + "s");
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @BeforeEach
    void prepare() {
        users.save(new UserJpaEntity(1));
        long brandId = brandApplicationService.create("브랜드").id().value();
        productId = productApplicationService.create(brandId, "상품", 1000, 3).id();
    }

    @AfterEach
    void clean() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("Redis가 응답하지 않아도 상품 변경은 성공하고 목록은 타임아웃 뒤 DB 값으로 200 응답한다")
    void fallsBackToDatabaseWhenRedisHangs() throws Exception {
        mvc.perform(get("/api/v1/products").header("X-USER-ID", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("상품"));

        DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            long started = System.currentTimeMillis();
            productApplicationService.change(productId, "바뀐 상품", 500);
            mvc.perform(get("/api/v1/products").header("X-USER-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(productId))
                .andExpect(jsonPath("$.data[0].name").value("바뀐 상품"))
                .andExpect(jsonPath("$.data[0].price").value(500))
                .andExpect(jsonPath("$.data[0].stock").value(3));
            assertThat(System.currentTimeMillis() - started).isLessThan(MAX_DEGRADED_MILLIS);
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        mvc.perform(get("/api/v1/products").header("X-USER-ID", "1")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("무효화가 실패하면 복구 직후엔 낡은 가격을 허용하고 재고는 최신이며, TTL이 지나면 DB 가격과 같아진다")
    void recoversStaleListAfterTtlWhenInvalidationFails() throws Exception {
        mvc.perform(get("/api/v1/products").header("X-USER-ID", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].price").value(1000));

        // pause는 타임아웃된 INCR을 Redis 수신 버퍼에 남겨 unpause 후 실행시키므로 실패가 아니라 지연이 된다.
        // 메모리 한도를 넘겨 쓰기 명령을 OOM으로 즉시 거절시키면 INCR이 확실히 실패하고 읽기는 계속된다.
        String versionBefore = redisCli("get", "products:list:version");
        String originalMaxMemory = redisCli("config", "get", "maxmemory").split("\\s+")[1];
        assertThat(redisCli("config", "get", "maxmemory-policy")).contains("noeviction");
        redisCli("config", "set", "maxmemory", "1");
        try {
            mvc.perform(put("/api-admin/v1/products/{id}", productId).with(user("admin").roles("ADMIN")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"상품\",\"price\":5000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.price").value(5000));
            assertThat(redisCli("incr", "probe:oom")).contains("OOM");
        } finally {
            redisCli("config", "set", "maxmemory", originalMaxMemory);
        }
        assertThat(redisCli("get", "products:list:version")).isEqualTo(versionBefore);
        productApplicationService.setStock(productId, 1);

        // commit 후 INCR이 실패해 버전이 그대로라 옛 캐시를 읽는다(설계 19절: 최대 TTL 동안 허용). 재고는 캐시하지 않아 최신이다.
        mvc.perform(get("/api/v1/products").header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].price").value(1000))
            .andExpect(jsonPath("$.data[0].stock").value(1));

        await().atMost(LIST_TTL.multipliedBy(3)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
            mvc.perform(get("/api/v1/products").header("X-USER-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].price").value(5000))
                .andExpect(jsonPath("$.data[0].stock").value(1)));
    }

    private static String redisCli(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "redis-cli";
        System.arraycopy(args, 0, command, 1, args.length);
        return REDIS.execInContainer(command).getStdout().trim();
    }
}
