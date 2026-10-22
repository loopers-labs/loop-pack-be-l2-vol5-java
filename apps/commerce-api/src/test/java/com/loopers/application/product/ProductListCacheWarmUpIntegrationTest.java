package com.loopers.application.product;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("상품 목록 캐시 워밍업")
class ProductListCacheWarmUpIntegrationTest {

    @Autowired
    private ProductListCacheWarmer warmer;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @PersistenceContext
    private EntityManager entityManager;

    private Statistics statistics;
    private boolean statisticsWereEnabled;

    @BeforeEach
    void setUp() {
        redisCleanUp.truncateAll();
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statisticsWereEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
    }

    @AfterEach
    void tearDown() {
        statistics.setStatisticsEnabled(statisticsWereEnabled);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @Test
    @DisplayName("워밍업 뒤 정렬별 전체·활성 브랜드 첫 페이지는 첫 요청부터 hit이고, 삭제된 브랜드는 채우지 않는다.")
    void firstRequestsHitAfterWarmUp() {
        Brand nike = brandRepository.save(Brand.create("Nike"));
        Brand adidas = brandRepository.save(Brand.create("Adidas"));
        Brand deleted = brandRepository.save(Brand.create("Puma"));
        productRepository.save(Product.create(nike.getId(), "Air", 1_000L));
        productRepository.save(Product.create(adidas.getId(), "Boost", 2_000L));
        jdbcTemplate.update("UPDATE brands SET deleted_at = NOW(6) WHERE id = ?", deleted.getId());

        ProductListCacheWarmer.Result result = warmer.warmUp();

        assertThat(result.warmed()).isEqualTo(9);
        for (String sort : List.of("latest", "price_asc", "likes_desc")) {
            for (Long brandId : Arrays.asList(null, nike.getId(), adidas.getId())) {
                statistics.clear();
                productFacade.getCustomerList(brandId, 0, 20, sort);
                assertThat(statistics.getPrepareStatementCount())
                    .as("brandId=%s, sort=%s는 삭제 확인 1회만 실행한다.", brandId, sort)
                    .isEqualTo(1L);
            }
        }
        assertThat(redisTemplate.keys("product:list:v1:*:brand=" + deleted.getId() + ":*")).isEmpty();
    }

    @Test
    @DisplayName("test 프로필에서는 기동 시 워밍업을 실행하지 않는다.")
    void doesNotRegisterStartupRunnerInTestProfile() {
        assertThat(applicationContext.getBeanNamesForType(ProductListCacheWarmUpRunner.class)).isEmpty();
    }
}
