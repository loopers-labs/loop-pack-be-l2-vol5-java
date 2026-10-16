package com.loopers.interfaces.api.mall.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.mall.command.BrandCommand;
import com.loopers.application.mall.command.ProductCommand;
import com.loopers.application.mall.query.BrandSummaryView;
import com.loopers.application.mall.query.ProductDetailView;
import com.loopers.application.mall.query.ProductQueryDao;
import com.loopers.application.mall.usecase.DeleteBrandUseCase;
import com.loopers.application.mall.usecase.DeleteProductUseCase;
import com.loopers.application.mall.usecase.UpdateProductUseCase;
import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.infrastructure.query.mall.ProductDetailCache;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.test.E2ETest;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@E2ETest
@TestPropertySource(properties = {
    "query.product-detail-cache.enabled=true",
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "logging.level.org.hibernate.stat=OFF",
    "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF",
    "management.server.port=0",
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductDetailCacheHttpE2ETest {
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductQueryDao productQueryDao;
    @Autowired
    private ProductDetailCache cache;
    @Autowired
    private UpdateProductUseCase updateProductUseCase;
    @Autowired
    private DeleteProductUseCase deleteProductUseCase;
    @Autowired
    private DeleteBrandUseCase deleteBrandUseCase;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeEach
    void clearCache() {
        cache.invalidateAll();
    }

    @AfterEach
    void tearDown() {
        cache.invalidateAll();
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("실제 HTTP miss의 SQL 1회가 hit에서 0회가 되고 전체 응답이 같다")
    @Test
    void preservesWholeHttpResponseWithoutSqlOnHit() {
        ProductDetailView expected = createProduct();
        statistics().clear();

        ResponseEntity<ApiResponse<ProductDetailView>> miss = getProduct(expected.productId());
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1L);
        statistics().clear();
        ResponseEntity<ApiResponse<ProductDetailView>> hit = getProduct(expected.productId());

        assertThat(statistics().getPrepareStatementCount()).isZero();
        assertThat(miss.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(miss.getBody().data()).isEqualTo(expected);
        assertThat(hit.getBody()).isEqualTo(miss.getBody());
    }

    @DisplayName("미존재 404는 매번 원본을 읽고 잘못된 ID의 400은 SQL 없이 반환한다")
    @Test
    void preservesNotFoundAndInvalidIdContracts() {
        statistics().clear();
        ResponseEntity<ApiResponse<ProductDetailView>> first = getProduct(Long.MAX_VALUE);
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1L);
        statistics().clear();
        ResponseEntity<ApiResponse<ProductDetailView>> second = getProduct(Long.MAX_VALUE);
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1L);
        statistics().clear();
        ResponseEntity<ApiResponse<ProductDetailView>> invalid = getProduct(0L);

        assertThat(statistics().getPrepareStatementCount()).isZero();
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(first.getBody().data()).isNull();
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @DisplayName("상품과 브랜드 삭제 commit 뒤 캐시된 상세도 HTTP 404가 된다")
    @Test
    void hidesCachedProductsAfterProductAndBrandDeletion() {
        ProductDetailView product = createProduct();
        assertThat(getProduct(product.productId()).getStatusCode()).isEqualTo(HttpStatus.OK);

        deleteProductUseCase.execute(new ProductCommand.Delete(product.productId()));

        assertThat(getProduct(product.productId()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ProductDetailView brandProduct = createProduct();
        assertThat(getProduct(brandProduct.productId()).getStatusCode()).isEqualTo(HttpStatus.OK);

        deleteBrandUseCase.execute(new BrandCommand.Delete(brandProduct.brand().brandId()));

        assertThat(getProduct(brandProduct.productId()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @DisplayName("기존 쓰기 TX의 미커밋 조회는 캐시를 우회하고 rollback 뒤 기존 hit를 유지한다")
    @Test
    void bypassesCachedValueAndDoesNotPublishRolledBackValue() {
        ProductDetailView before = createProduct();
        assertThat(getProduct(before.productId()).getBody().data()).isEqualTo(before);

        transactionTemplate.executeWithoutResult(status -> {
            updateProductUseCase.execute(new ProductCommand.Update(before.productId(), "미커밋 상품", "수정 설명", 9_000L));
            assertThat(productQueryDao.findProduct(before.productId()).orElseThrow().name()).isEqualTo("미커밋 상품");
            status.setRollbackOnly();
        });
        statistics().clear();

        assertThat(getProduct(before.productId()).getBody().data()).isEqualTo(before);
        assertThat(statistics().getPrepareStatementCount()).isZero();
    }

    @DisplayName("기존 읽기 TX에서 조회한 결과도 캐시를 채우지 않는다")
    @Test
    void doesNotFillFromAnExistingTransaction() {
        ProductDetailView expected = createProduct();
        transactionTemplate.executeWithoutResult(status ->
            assertThat(productQueryDao.findProduct(expected.productId())).contains(expected));
        statistics().clear();

        assertThat(getProduct(expected.productId()).getBody().data()).isEqualTo(expected);
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1L);
        statistics().clear();
        getProduct(expected.productId());
        assertThat(statistics().getPrepareStatementCount()).isZero();
    }

    private ProductDetailView createProduct() {
        return transactionTemplate.execute(status -> {
            Brand brand = brandRepository.save(Brand.create("캐시 브랜드", "브랜드 설명"));
            Product product = productRepository.save(Product.create(brand.getId(), "캐시 상품", "상품 설명", 3_000L, 7));
            return new ProductDetailView(product.getId(), product.getName(), product.getPrice(),
                new BrandSummaryView(brand.getId(), brand.getName()), 0L, product.getDescription(), product.getStock());
        });
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private ResponseEntity<ApiResponse<ProductDetailView>> getProduct(long productId) {
        return restTemplate.exchange("/api/v1/products/" + productId, HttpMethod.GET, HttpEntity.EMPTY,
            new ParameterizedTypeReference<>() {});
    }
}
