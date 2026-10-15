package com.loopers.application.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DisplayName("상품 목록 캐시 무효화")
class ProductListCacheInvalidationIntegrationTest {

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private BrandFacade brandFacade;

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @PersistenceContext
    private EntityManager entityManager;

    private Statistics statistics;
    private boolean statisticsWereEnabled;

    private Brand brand;
    private Brand otherBrand;
    private Product product;

    @BeforeEach
    void setUp() {
        redisCleanUp.truncateAll();
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statisticsWereEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        brand = brandRepository.save(Brand.create("Nike"));
        otherBrand = brandRepository.save(Brand.create("Adidas"));
        product = productRepository.save(Product.create(brand.getId(), "Air", 1_000L));
        productRepository.save(Product.create(otherBrand.getId(), "Boost", 2_000L));
    }

    @AfterEach
    void tearDown() {
        statistics.setStatisticsEnabled(statisticsWereEnabled);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @Test
    @DisplayName("상품 등록이 커밋되면 브랜드 목록과 전체 목록에 새 상품을 반환한다.")
    void showsRegisteredProduct_afterRegisterCommits() {
        warmUpBrandAndAllLists();

        ProductInfo registered = productFacade.register(brand.getId(), "New", 500L);

        assertThat(brandList()).extracting(CustomerProductInfo::id).contains(registered.id());
        assertThat(allList()).extracting(CustomerProductInfo::id).contains(registered.id());
    }

    @Test
    @DisplayName("상품 이름·가격 수정이 커밋되면 변경된 이름과 가격을 반환한다.")
    void showsUpdatedDetails_afterUpdateCommits() {
        warmUpBrandAndAllLists();

        productFacade.update(product.getId(), "Air Max", 9_000L);

        assertThat(findIn(brandList(), product.getId()))
            .extracting(CustomerProductInfo::name, CustomerProductInfo::price)
            .containsExactly("Air Max", 9_000L);
        assertThat(findIn(allList(), product.getId()))
            .extracting(CustomerProductInfo::name, CustomerProductInfo::price)
            .containsExactly("Air Max", 9_000L);
    }

    @Test
    @DisplayName("상품 삭제가 커밋되면 삭제된 상품을 반환하지 않는다.")
    void hidesDeletedProduct_afterDeleteCommits() {
        warmUpBrandAndAllLists();

        productFacade.delete(product.getId());

        assertThat(brandList()).extracting(CustomerProductInfo::id).doesNotContain(product.getId());
        assertThat(allList()).extracting(CustomerProductInfo::id).doesNotContain(product.getId());
    }

    @Test
    @DisplayName("브랜드 이름 수정이 커밋되면 변경된 브랜드 이름을 반환한다.")
    void showsRenamedBrand_afterBrandUpdateCommits() {
        warmUpBrandAndAllLists();

        brandFacade.update(brand.getId(), "Nike Korea");

        assertThat(findIn(brandList(), product.getId()).brandName()).isEqualTo("Nike Korea");
        assertThat(findIn(allList(), product.getId()).brandName()).isEqualTo("Nike Korea");
    }

    @Test
    @DisplayName("브랜드 삭제가 커밋되면 연결 상품을 반환하지 않는다.")
    void hidesBrandProducts_afterBrandDeleteCommits() {
        warmUpBrandAndAllLists();

        brandFacade.delete(brand.getId());

        assertThat(brandList()).isEmpty();
        assertThat(allList()).extracting(CustomerProductInfo::brandId).doesNotContain(brand.getId());
    }

    @Test
    @DisplayName("좋아요 등록이 커밋되면 증가한 좋아요 수를 반환한다.")
    void showsIncreasedLikeCount_afterLikeCommits() {
        User user = userRepository.save(User.create());
        warmUpBrandAndAllLists();

        likeFacade.add(user.getId(), product.getId());

        assertThat(findIn(brandList(), product.getId()).likeCount()).isEqualTo(1L);
        assertThat(findIn(allList(), product.getId()).likeCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("좋아요 취소가 커밋되면 감소한 좋아요 수를 반환한다.")
    void showsDecreasedLikeCount_afterCancelCommits() {
        User user = userRepository.save(User.create());
        likeRepository.save(Like.create(user.getId(), product.getId()));
        warmUpBrandAndAllLists();

        likeFacade.cancel(user.getId(), product.getId());

        assertThat(findIn(brandList(), product.getId()).likeCount()).isZero();
        assertThat(findIn(allList(), product.getId()).likeCount()).isZero();
    }

    @Test
    @DisplayName("한 브랜드의 변경은 다른 브랜드 목록 캐시를 무효화하지 않는다.")
    void keepsOtherBrandCache_whenOneBrandChanges() {
        productFacade.getCustomerList(otherBrand.getId(), 0, 20, "latest");

        productFacade.update(product.getId(), "Air Max", 9_000L);
        statistics.clear();
        productFacade.getCustomerList(otherBrand.getId(), 0, 20, "latest");

        assertThat(statistics.getPrepareStatementCount())
            .as("목록·브랜드·좋아요 조회 없이 삭제 확인 1회만 실행한다.")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("쓰기가 rollback되면 캐시를 무효화하지 않고 이전 결과를 그대로 반환한다.")
    void keepsCache_whenWriteRollsBack() {
        List<CustomerProductInfo> before = brandList();

        transactionTemplate.executeWithoutResult(status -> {
            productFacade.update(product.getId(), "Air Max", 9_000L);
            status.setRollbackOnly();
        });
        statistics.clear();

        assertThat(brandList()).isEqualTo(before);
        assertThat(statistics.getPrepareStatementCount())
            .as("목록·브랜드·좋아요 조회 없이 삭제 확인 1회만 실행한다.")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("쓰기가 실패하면 캐시를 무효화하지 않는다.")
    void keepsCache_whenWriteFails() {
        brandRepository.save(Brand.create("Puma"));
        List<CustomerProductInfo> before = brandList();

        assertThatThrownBy(() -> brandFacade.update(brand.getId(), "Puma")).isInstanceOf(CoreException.class);
        statistics.clear();

        assertThat(brandList()).isEqualTo(before);
        assertThat(statistics.getPrepareStatementCount())
            .as("목록·브랜드·좋아요 조회 없이 삭제 확인 1회만 실행한다.")
            .isEqualTo(1L);
    }

    private void warmUpBrandAndAllLists() {
        brandList();
        allList();
    }

    private List<CustomerProductInfo> brandList() {
        return productFacade.getCustomerList(brand.getId(), 0, 20, "latest");
    }

    private List<CustomerProductInfo> allList() {
        return productFacade.getCustomerList(null, 0, 20, "latest");
    }

    private CustomerProductInfo findIn(List<CustomerProductInfo> products, Long productId) {
        return products.stream()
            .filter(info -> info.id().equals(productId))
            .findFirst()
            .orElseThrow();
    }
}
