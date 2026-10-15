package com.loopers.application.product;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderItemRequest;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.user.UserModel;
import com.loopers.domain.user.UserService;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.cache.CacheRedisConfig;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * C5 — Redis가 중지·재시작·멈춤(pause)일 때 docs/week4/design.md 8번 섹션 장애 계약대로 동작하는지 실제 컨테이너로 확인한다.
 * "Redis의 실패가 요청의 실패가 되지 않는다" — 조회는 DB로 응답하고, 쓰기는 무효화 실패를 로그로만 남기고 성공한다.
 *
 * <p>공용 테스트 Redis를 멈추면 다른 테스트가 깨지므로 이 클래스만 전용 컨테이너를 쓴다(CacheRedisTimeoutTest와 같은 방식).
 * 재시작 뒤에도 같은 호스트 포트여야 Lettuce가 같은 주소로 재연결하므로, 빈 포트를 미리 구해 고정 바인딩한다
 * (Testcontainers의 무작위 매핑은 docker start마다 포트가 바뀔 수 있다).
 *
 * <p>영속성 설정은 로컬 인프라(docker/infra-compose.yml의 redis-master)와 같게 AOF를 켠다. 그래서 재시작하면 데이터가
 * 사라지지 않고 AOF에서 복원된다 — 중지 중 무효화에 실패한 옛 값도 남는다. 만료 시각은 절대 시각으로 저장되므로
 * 남은 옛 값도 TTL(60초) 안에 사라진다(재시작 시나리오에서 확인).
 *
 * <p>모든 테스트가 끝나면 Redis를 다시 살려 둔다(@AfterEach) — 전역 리스너(RedisCacheCleanUpListener)가 다음 테스트 전에
 * 공용 연결로 FLUSHALL을 보내기 때문이다.
 */
@SpringBootTest
class ProductCacheRedisOutageTest {

    private static final Logger log = LoggerFactory.getLogger(ProductCacheRedisOutageTest.class);
    private static final int REDIS_PORT = 6379;
    private static final int HOST_PORT = findFreePort();
    private static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
        .withExposedPorts(REDIS_PORT)
        .withCommand("redis-server", "--appendonly", "yes", "--save", "")
        .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
            .withPortBindings(new PortBinding(Ports.Binding.bindPort(HOST_PORT), new ExposedPort(REDIS_PORT))));

    private static final Pageable FIRST_PAGE = PageRequest.of(0, 20);
    private static final long OLD_PRICE = 1_000L;
    private static final long NEW_PRICE = 1_500L;
    // 중지 중: 끊기면 즉시 거절이므로 50ms 타임아웃을 기다리지 않는다. 상한은 DB 시간과 Windows Docker 편차를 감안한 여유값.
    private static final long STOPPED_LIMIT_MILLIS = 500L;
    // 멈춤 중: 첫 명령의 50ms 타임아웃 후 남은 Redis 호출은 건너뛰므로 대략 50ms + DB 시간.
    private static final long PAUSED_LIMIT_MILLIS = 400L;
    private static final Duration RECONNECT_LIMIT = Duration.ofSeconds(30);

    static {
        redis.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", redis::getHost);
        registry.add("datasource.redis.master.port", () -> HOST_PORT);
        registry.add("datasource.redis.replicas[0].host", redis::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> HOST_PORT);
    }

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private ProductAdminFacade productAdminFacade;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private UserService userService;

    @MockitoSpyBean
    private ProductService productService;

    @Autowired
    @Qualifier(CacheRedisConfig.CACHE_REDIS_TEMPLATE)
    private RedisTemplate<String, String> cacheRedisTemplate;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private ProductModel airMax;
    private ProductModel jordan;

    @BeforeEach
    void setUp() {
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드", "신발/의류"));
        airMax = productJpaRepository.save(new ProductModel("에어맥스", OLD_PRICE, nike.getId(), 10));
        jordan = productJpaRepository.save(new ProductModel("조던", 2_000L, nike.getId(), 10));
    }

    @AfterEach
    void tearDown() {
        // Redis부터 살린다 — 정리(FLUSHALL)도 Redis가 응답해야 끝난다
        unpauseRedis();
        if (!Boolean.TRUE.equals(inspectRedis().getState().getRunning())) {
            startRedis();
        }
        awaitCacheReconnected();
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DockerClient docker() {
        return redis.getDockerClient();
    }

    private static InspectContainerResponse inspectRedis() {
        return docker().inspectContainerCmd(redis.getContainerId()).exec();
    }

    private static void stopRedis() {
        docker().stopContainerCmd(redis.getContainerId()).exec();
    }

    private static void startRedis() {
        docker().startContainerCmd(redis.getContainerId()).exec();
    }

    private static void pauseRedis() {
        docker().pauseContainerCmd(redis.getContainerId()).exec();
    }

    private static void unpauseRedis() {
        if (Boolean.TRUE.equals(inspectRedis().getState().getPaused())) {
            docker().unpauseContainerCmd(redis.getContainerId()).exec();
        }
    }

    // 재연결을 기다린다 — Awaitility가 없어 상한이 있는 반복+sleep으로 둔다. 걸린 시간(ms)을 돌려준다.
    private long awaitCacheReconnected() {
        long start = System.nanoTime();
        long deadline = start + RECONNECT_LIMIT.toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                cacheRedisTemplate.opsForValue().get("reconnect-probe");
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            } catch (RuntimeException e) {
                last = e;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("캐시 Redis에 " + RECONNECT_LIMIT + " 안에 재연결하지 못했다", last);
    }

    private boolean cached(String key) {
        return Boolean.TRUE.equals(cacheRedisTemplate.hasKey(key));
    }

    private static <T> Timed<T> timed(Supplier<T> call) {
        long start = System.nanoTime();
        T result = call.get();
        return new Timed<>(result, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    private record Timed<T>(T result, long millis) {
    }

    private Page<ProductDetailInfo> listAll() {
        return productFacade.getProductList(null, ProductSort.PRICE_ASC, FIRST_PAGE);
    }

    @DisplayName("Redis가 중지되면(docker stop),")
    @Nested
    class Stopped {
        @DisplayName("상세·목록 조회가 DB 결과로 정상 응답하고, 각 호출이 상한(500ms) 안에 끝난다.")
        @Test
        void readsFallBackToDb_withinLimit() {
            // arrange — 같은 경로를 한 번 실행해 첫 호출 비용(클래스 로딩 등)을 빼고, 캐시를 채운 뒤 중지한다
            productFacade.getProductDetail(airMax.getId());
            listAll();
            stopRedis();

            // act
            Timed<ProductDetailInfo> detail = timed(() -> productFacade.getProductDetail(airMax.getId()));
            Timed<Page<ProductDetailInfo>> list = timed(ProductCacheRedisOutageTest.this::listAll);

            // assert
            assertAll(
                () -> assertThat(detail.result().name()).isEqualTo("에어맥스"),
                () -> assertThat(detail.result().price()).isEqualTo(OLD_PRICE),
                () -> assertThat(detail.result().brand().name()).isEqualTo("나이키"),
                () -> assertThat(list.result().getContent()).extracting(ProductDetailInfo::id)
                    .containsExactly(airMax.getId(), jordan.getId()),
                () -> assertThat(list.result().getTotalElements()).isEqualTo(2L),
                () -> assertThat(detail.millis()).isLessThan(STOPPED_LIMIT_MILLIS),
                () -> assertThat(list.millis()).isLessThan(STOPPED_LIMIT_MILLIS)
            );
            log.info("[C5-중지 중 조회] 상세 {}ms, 목록 {}ms", detail.millis(), list.millis());
        }

        @DisplayName("관리자 가격 수정과 주문 확정이 예외 없이 성공하고 DB에 반영된다(무효화 실패는 로그만 남는다).")
        @Test
        void writesSucceed() {
            // arrange
            Long userId = userJpaRepository.save(new UserModel()).getId();
            userService.chargePoint(userId, 10_000L);
            Long orderId = orderFacade.createOrder(userId, List.of(new OrderItemRequest(jordan.getId(), 2))).id();
            productFacade.getProductDetail(airMax.getId());
            stopRedis();

            // act
            Timed<ProductInfo> update = timed(() -> productAdminFacade.updateProduct(airMax.getId(), "에어맥스", NEW_PRICE));
            assertThatCode(() -> orderFacade.confirmOrder(orderId, userId)).doesNotThrowAnyException();

            // assert
            assertAll(
                () -> assertThat(update.result().price()).isEqualTo(NEW_PRICE),
                () -> assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getPrice()).isEqualTo(NEW_PRICE),
                () -> assertThat(productJpaRepository.findById(jordan.getId()).orElseThrow().getRemainingStock()).isEqualTo(8),
                () -> assertThat(userService.getBalance(userId)).isEqualTo(10_000L - 2_000L * 2),
                () -> assertThat(update.millis()).isLessThan(STOPPED_LIMIT_MILLIS)
            );
            log.info("[C5-중지 중 쓰기] 가격 수정 {}ms", update.millis());
        }
    }

    @DisplayName("Redis가 재시작되면,")
    @Nested
    class Restarted {
        @DisplayName("재연결 뒤 조회가 다시 캐시를 채워 두 번째 조회는 hit이고, 중지 중 무효화에 실패한 옛 값은 AOF로 복원돼도 TTL(60초) 안에 사라진다.")
        @Test
        void recoversCache_andStaleValueBoundedByTtl() {
            // arrange — 에어맥스를 캐시에 채운 뒤 중지하고, 중지 중에 가격을 바꾼다(DEL 실패)
            productFacade.getProductDetail(airMax.getId());
            stopRedis();
            productAdminFacade.updateProduct(airMax.getId(), "에어맥스", NEW_PRICE);

            // act
            startRedis();
            long reconnectMillis = awaitCacheReconnected();
            clearInvocations(productService);
            ProductDetailInfo firstRead = productFacade.getProductDetail(jordan.getId());
            ProductDetailInfo secondRead = productFacade.getProductDetail(jordan.getId());

            // assert — 회복: 캐시에 없던 조던은 첫 조회만 DB를 읽는다
            assertAll(
                () -> assertThat(secondRead).isEqualTo(firstRead),
                () -> verify(productService, times(1)).getProduct(jordan.getId()),
                () -> assertThat(cached("product:" + jordan.getId())).isTrue()
            );

            // assert — 중지 전에 채운 에어맥스 옛 값은 AOF로 복원됐고, 남은 TTL이 60초 이하라 그 안에 사라진다
            Long staleTtl = cacheRedisTemplate.getExpire("product:" + airMax.getId(), TimeUnit.SECONDS);
            clearInvocations(productService);
            ProductDetailInfo staleRead = productFacade.getProductDetail(airMax.getId());
            assertAll(
                () -> assertThat(staleRead.price()).isEqualTo(OLD_PRICE),
                () -> verify(productService, never()).getProduct(anyLong()),
                () -> assertThat(staleTtl).isBetween(1L, 60L)
            );

            // act & assert — TTL 만료를 흉내 내면 DB의 새 가격이 보인다
            redisCleanUp.truncateAll();
            assertThat(productFacade.getProductDetail(airMax.getId()).price()).isEqualTo(NEW_PRICE);
            log.info("[C5-재시작] 재연결 대기 {}ms, 복원된 옛 값 남은 TTL {}s", reconnectMillis, staleTtl);
        }
    }

    @DisplayName("Redis가 응답 없이 멈추면(docker pause),")
    @Nested
    class Paused {
        @DisplayName("목록 조회가 정상 응답하고, 첫 명령의 타임아웃(50ms) 뒤 남은 Redis 호출을 건너뛰어 상한(400ms) 안에 끝난다.")
        @Test
        void listRespondsWithinLimit() {
            // arrange — 같은 경로를 한 번 실행해 첫 호출 비용을 빼고, 캐시가 채워진 상태에서 멈춘다
            listAll();
            pauseRedis();

            // act
            Timed<Page<ProductDetailInfo>> list;
            try {
                list = timed(ProductCacheRedisOutageTest.this::listAll);
            } finally {
                unpauseRedis();
            }

            // assert
            Timed<Page<ProductDetailInfo>> result = list;
            assertAll(
                () -> assertThat(result.result().getContent()).extracting(ProductDetailInfo::id)
                    .containsExactly(airMax.getId(), jordan.getId()),
                () -> assertThat(result.result().getContent()).extracting(info -> info.brand().name())
                    .containsExactly("나이키", "나이키"),
                () -> assertThat(result.result().getTotalElements()).isEqualTo(2L),
                () -> assertThat(result.millis()).isLessThan(PAUSED_LIMIT_MILLIS)
            );
            log.info("[C5-멈춤 중 목록] {}ms", result.millis());
        }
    }
}
