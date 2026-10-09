package com.loopers.infrastructure.query.mall;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.common.PageCriteria;
import com.loopers.application.common.PageResult;
import com.loopers.application.mall.query.ProductCriteria;
import com.loopers.application.mall.query.ProductQueryDao;
import com.loopers.application.mall.query.ProductSort;
import com.loopers.application.mall.query.ProductSummaryView;
import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.infrastructure.persistence.mall.entity.QProductJpaEntity;
import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class QueryDslProductQueryDaoIntegrationTest {
    private static final QProductJpaEntity PRODUCT = QProductJpaEntity.productJpaEntity;

    @Autowired
    private ProductQueryDao productQueryDao;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("좋아요순은 좋아요 수 내림차순, 동점은 상품 id 내림차순이며 좋아요가 없는 상품도 포함한다")
    @Test
    void sortsByLikeCountThenIdDescending() {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        Product few = saveProduct(brand, "적은 상품", 1L);
        Product tieOlder = saveProduct(brand, "동점 먼저", 5L);
        Product tieNewer = saveProduct(brand, "동점 나중", 5L);
        Product none = saveProduct(brand, "좋아요 없음", 0L);

        PageResult<ProductSummaryView> result = productQueryDao.findProducts(criteria(null));

        assertThat(ids(result)).containsExactly(tieNewer.getId(), tieOlder.getId(), few.getId(), none.getId());
        assertThat(result.items().get(0).likeCount()).isEqualTo(5L);
        assertThat(result.items().get(3).likeCount()).isZero();
        assertThat(result.totalElements()).isEqualTo(4);
    }

    @DisplayName("삭제된 상품은 좋아요가 많아도 목록과 전체 개수에서 제외한다")
    @Test
    void excludesDeletedProducts() {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        Product popular = saveProduct(brand, "삭제될 인기 상품", 100L);
        Product alive = saveProduct(brand, "남은 상품", 1L);
        popular.delete();
        productRepository.save(popular);

        PageResult<ProductSummaryView> result = productQueryDao.findProducts(criteria(null));

        assertThat(ids(result)).containsExactly(alive.getId());
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @DisplayName("브랜드 필터는 해당 브랜드의 상품만 같은 규칙으로 정렬하고 개수도 그 브랜드로 센다")
    @Test
    void filtersByBrand() {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        Brand other = brandRepository.save(Brand.create("다른 브랜드", null));
        Product low = saveProduct(brand, "낮음", 1L);
        Product high = saveProduct(brand, "높음", 9L);
        saveProduct(other, "다른 브랜드 인기 상품", 50L);

        PageResult<ProductSummaryView> result = productQueryDao.findProducts(criteria(brand.getId()));

        assertThat(ids(result)).containsExactly(high.getId(), low.getId());
        assertThat(result.totalElements()).isEqualTo(2);
    }

    private ProductCriteria criteria(Long brandId) {
        return new ProductCriteria(brandId, ProductSort.LIKES_DESC, new PageCriteria(0, 20));
    }

    private List<Long> ids(PageResult<ProductSummaryView> result) {
        return result.items().stream().map(ProductSummaryView::productId).toList();
    }

    private Product saveProduct(Brand brand, String name, long likeCount) {
        Product product = productRepository.save(Product.create(brand.getId(), name, null, 1_000L, 5));
        transactionTemplate.executeWithoutResult(status -> queryFactory.update(PRODUCT)
            .set(PRODUCT.likeCount, likeCount)
            .where(PRODUCT.id.eq(product.getId()))
            .execute());
        return product;
    }
}
