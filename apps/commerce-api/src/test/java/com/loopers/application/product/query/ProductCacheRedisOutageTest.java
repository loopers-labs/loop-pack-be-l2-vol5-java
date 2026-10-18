package com.loopers.application.product.query;

import com.loopers.application.product.ProductFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.support.fixture.Fixtures;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DR-37 Redis 장애. 공유 Redis 컨테이너를 멈추면 다른 테스트가 깨지므로 이 클래스만 전용 컨테이너를 쓴다.
 * 중단은 컨테이너 일시정지(연결은 살아 있고 응답만 없음), 재시작은 일시정지 해제 + FLUSHALL(재시작으로 메모리가 비는 것)로 흉내 낸다.
 * 명령 대기 상한은 datasource.redis.timeout(300ms)이다. 조회 하나는 GET·SET 두 번까지 기다린다.
 */
@SpringBootTest
class ProductCacheRedisOutageTest {
    private static final int REDIS_PORT = 6379;
    private static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(REDIS_PORT);
    private static final Duration BOUNDED_WAIT = Duration.ofSeconds(2);
    private static final PageQuery LATEST_FIRST_PAGE = PageQuery.of(0, 20);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS.getMappedPort(REDIS_PORT));
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS.getMappedPort(REDIS_PORT));
    }

    @Autowired
    private ProductReader productReader;
    @Autowired
    private ProductFacade productFacade;
    @MockitoSpyBean
    private ProductQueryRepository productQueryRepository;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private RedisCleanUp redisCleanUp;

    private UserModel user;
    private UserModel admin;
    private ProductModel product;

    @BeforeEach
    void setUp() {
        fixtures.catalogRevision();
        user = fixtures.user();
        admin = fixtures.admin();
        BrandModel brand = fixtures.brand("브랜드");
        product = fixtures.product(brand.getId(), "상품", 1000L, 10);
    }

    @AfterEach
    void tearDown() {
        resumeRedis();
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private void resumeRedis() {
        if (Boolean.TRUE.equals(REDIS.getCurrentContainerInfo().getState().getPaused())) {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    private ProductView.Summary detail() {
        return productReader.getProduct(user.getId(), product.getId());
    }

    private PageResult<ProductView.Summary> latestPage() {
        return productReader.listProducts(user.getId(), null, ProductSort.LATEST, LATEST_FIRST_PAGE);
    }

    /** 응답을 제한된 시간 안에 받았는지 재고 결과를 돌려준다. */
    private static <T> T withinBoundedWait(Supplier<T> call) {
        long started = System.nanoTime();
        T result = call.get();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(BOUNDED_WAIT);
        return result;
    }

    private void assertServedFromCache() {
        verify(productQueryRepository, never()).findActive(any());
        verify(productQueryRepository, never()).findActivePage(any(), any(), any());
    }

    @DisplayName("[DR-37] Redis 가 멈춰 있어도 상세·목록은 제한된 대기 뒤 DB 결과를 주고, 재시작으로 비워진 뒤에는 첫 조회가 다시 채우고 다음 조회는 캐시에서 읽는다.")
    @Test
    void outage_answersFromDb_thenRefillsAfterRestart() {
        detail();
        latestPage();

        pauseRedis();
        assertThat(withinBoundedWait(this::detail).name()).isEqualTo("상품");
        assertThat(withinBoundedWait(this::latestPage).items()).extracting(ProductView.Summary::id).containsExactly(product.getId());

        resumeRedis();
        redisCleanUp.truncateAll();
        clearInvocations(productQueryRepository);
        assertThat(detail().name()).isEqualTo("상품");
        assertThat(latestPage().totalCount()).isEqualTo(1);
        verify(productQueryRepository).findActive(product.getId());
        verify(productQueryRepository).findActivePage(null, ProductSort.LATEST, LATEST_FIRST_PAGE);

        clearInvocations(productQueryRepository);
        assertThat(detail().name()).isEqualTo("상품");
        assertThat(latestPage().totalCount()).isEqualTo(1);
        assertServedFromCache();
    }

    @DisplayName("[DR-37] 쓰기는 캐시를 지우지 않으므로 Redis 가 멈춰 있어도 수정이 커밋되고, 복구 뒤 옛 revision 의 키가 남아 있어도 새 값을 읽는다.")
    @Test
    void writeDuringOutage_commits_andNewValueIsShownAfterRecovery() {
        detail();
        latestPage();
        String staleKey = "product:detail:v1:" + product.getId() + ":0:0";
        assertThat(redisTemplate.hasKey(staleKey)).isTrue();

        pauseRedis();
        withinBoundedWait(() -> productFacade.updateProduct(admin.getId(), product.getId(), "새 이름", 2000L));
        assertThat(fixtures.reloadProduct(product.getId()).getName()).isEqualTo("새 이름");
        assertThat(withinBoundedWait(this::detail).name()).isEqualTo("새 이름");

        resumeRedis();
        assertThat(redisTemplate.opsForValue().get(staleKey)).contains("\"상품\"");
        assertThat(detail().price()).isEqualTo(2000L);
        assertThat(latestPage().items().get(0).name()).isEqualTo("새 이름");
    }
}
