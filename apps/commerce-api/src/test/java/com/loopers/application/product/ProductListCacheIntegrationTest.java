package com.loopers.application.product;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DisplayName("고객 상품 목록 cache-aside")
class ProductListCacheIntegrationTest {

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private UserRepository userRepository;

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
    @DisplayName("miss면 DB 결과를 반환하고 유효 시간과 함께 저장한다.")
    void storesDbResultWithTtl_onMiss() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product cheap = productRepository.save(Product.create(brand.getId(), "Cheap", 1_000L));
        Product expensive = productRepository.save(Product.create(brand.getId(), "Expensive", 2_000L));
        addLikes(expensive, 2);

        List<CustomerProductInfo> results = productFacade.getCustomerList(brand.getId(), 0, 20, "price_asc");

        assertThat(results).containsExactly(
            new CustomerProductInfo(cheap.getId(), brand.getId(), "Nike", "Cheap", 1_000L, 0L),
            new CustomerProductInfo(expensive.getId(), brand.getId(), "Nike", "Expensive", 2_000L, 2L)
        );
        String key = new ProductListCacheKey(brand.getId(), ProductSort.PRICE_ASC, 0, 20, 0L).value();
        assertThat(redisTemplate.hasKey(key)).isTrue();
        assertThat(redisTemplate.getExpire(key)).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("hit면 같은 조회 조건의 저장된 응답을 반환하고 상품·브랜드·좋아요 SQL을 실행하지 않는다.")
    void returnsCachedResultWithoutQueries_onHit() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = productRepository.save(Product.create(brand.getId(), "Air", 1_000L));
        addLikes(product, 1);
        List<CustomerProductInfo> first = productFacade.getCustomerList(brand.getId(), 0, 20, "price_asc");
        jdbcTemplate.update("UPDATE products SET price = ? WHERE id = ?", 9_000L, product.getId());
        statistics.clear();

        List<CustomerProductInfo> second = productFacade.getCustomerList(brand.getId(), 0, 20, "price_asc");

        assertThat(second).isEqualTo(first);
        assertThat(second).extracting(CustomerProductInfo::price).containsExactly(1_000L);
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @ParameterizedTest(name = "처음 정렬={0}, 다음 정렬={1}")
    @CsvSource(value = {"NULL, latest", "'', latest", "latest, NULL"}, nullValues = "NULL")
    @DisplayName("정렬 기본값과 명시값은 같은 캐시를 사용한다.")
    void sharesCache_whenDefaultAndExplicitSortAreEquivalent(String firstSort, String secondSort) {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        productRepository.save(Product.create(brand.getId(), "Air", 1_000L));
        productFacade.getCustomerList(null, 0, 20, firstSort);
        statistics.clear();

        productFacade.getCustomerList(null, 0, 20, secondSort);

        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @Test
    @DisplayName("범위를 벗어난 빈 페이지도 같은 응답 형태로 저장하고 다시 사용한다.")
    void cachesEmptyPage() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        productRepository.save(Product.create(brand.getId(), "Air", 1_000L));
        assertThat(productFacade.getCustomerList(brand.getId(), 5, 20, "latest")).isEmpty();
        statistics.clear();

        List<CustomerProductInfo> results = productFacade.getCustomerList(brand.getId(), 5, 20, "latest");

        assertThat(results).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @ParameterizedTest(name = "page={0}, size={1}, sort={2}")
    @CsvSource({"-1, 20, latest", "0, 0, latest", "0, 101, latest", "0, 20, unknown"})
    @DisplayName("페이지·정렬 입력 오류는 400으로 거절하고 캐시에 저장하지 않는다.")
    void rejectsInvalidInputWithoutCaching(int page, int size, String sort) {
        assertThatThrownBy(() -> productFacade.getCustomerList(null, page, size, sort))
            .isInstanceOf(CoreException.class)
            .extracting(exception -> ((CoreException) exception).getErrorType())
            .isEqualTo(ErrorType.BAD_REQUEST);
        assertThat(redisTemplate.keys("product:list:v1:*")).isEmpty();
    }

    private void addLikes(Product product, int count) {
        for (int index = 0; index < count; index++) {
            User user = userRepository.save(User.create());
            likeRepository.save(Like.create(user.getId(), product.getId()));
        }
    }
}
