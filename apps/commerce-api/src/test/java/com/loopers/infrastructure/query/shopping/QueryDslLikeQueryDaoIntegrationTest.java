package com.loopers.infrastructure.query.shopping;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.common.PageCriteria;
import com.loopers.application.common.PageResult;
import com.loopers.application.shopping.query.LikeQueryDao;
import com.loopers.application.shopping.query.LikedProductView;
import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.shopping.model.Like;
import com.loopers.domain.shopping.repository.LikeRepository;
import com.loopers.infrastructure.persistence.mall.entity.QProductJpaEntity;
import com.loopers.infrastructure.persistence.shopping.entity.QLikeJpaEntity;
import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class QueryDslLikeQueryDaoIntegrationTest {
    private static final QLikeJpaEntity LIKE = QLikeJpaEntity.likeJpaEntity;
    private static final QProductJpaEntity PRODUCT = QProductJpaEntity.productJpaEntity;

    @Autowired
    private LikeQueryDao likeQueryDao;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private LikeRepository likeRepository;
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

    @DisplayName("좋아요한 상품을 브랜드 정보·집계 값과 함께 최근 좋아요순으로 반환한다")
    @Test
    void returnsLikedProductsOrderedByLikedAtDescending() {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        Product older = productRepository.save(Product.create(brand.getId(), "오래된 상품", null, 1_000L, 5));
        Product newer = productRepository.save(Product.create(brand.getId(), "최근 상품", null, 2_000L, 5));
        insertLike(1L, older.getId(), "2026-01-01T00:00:00Z");
        insertLike(1L, newer.getId(), "2026-01-02T00:00:00Z");
        insertLikeCount(newer.getId(), 3L);

        PageResult<LikedProductView> result = likeQueryDao.findByUserId(1L, new PageCriteria(0, 20));

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.items()).extracting(LikedProductView::productId).containsExactly(newer.getId(), older.getId());
        LikedProductView newerItem = result.items().get(0);
        assertThat(newerItem.name()).isEqualTo("최근 상품");
        assertThat(newerItem.price()).isEqualTo(2_000L);
        assertThat(newerItem.brand().brandId()).isEqualTo(brand.getId());
        assertThat(newerItem.likeCount()).isEqualTo(3L);
        LikedProductView olderItem = result.items().get(1);
        assertThat(olderItem.likeCount()).isZero();
    }

    @DisplayName("좋아요 시각이 같으면 상품 ID 내림차순으로 정렬한다")
    @Test
    void ordersByProductIdDescending_whenLikedAtTies() {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        Product first = productRepository.save(Product.create(brand.getId(), "상품1", null, 1_000L, 5));
        Product second = productRepository.save(Product.create(brand.getId(), "상품2", null, 1_000L, 5));
        insertLike(1L, first.getId(), "2026-01-01T00:00:00Z");
        insertLike(1L, second.getId(), "2026-01-01T00:00:00Z");

        PageResult<LikedProductView> result = likeQueryDao.findByUserId(1L, new PageCriteria(0, 20));

        assertThat(result.items()).extracting(LikedProductView::productId)
            .containsExactly(Math.max(first.getId(), second.getId()), Math.min(first.getId(), second.getId()));
    }

    @DisplayName("삭제된 상품은 목록과 전체 개수에서 제외한다")
    @Test
    void excludesDeletedProducts() {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        Product deleted = productRepository.save(Product.create(brand.getId(), "삭제 상품", null, 1_000L, 5));
        deleted.delete();
        productRepository.save(deleted);
        insertLike(1L, deleted.getId(), "2026-01-01T00:00:00Z");

        PageResult<LikedProductView> result = likeQueryDao.findByUserId(1L, new PageCriteria(0, 20));

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }

    @DisplayName("페이지 크기만큼 잘라서 반환하고 전체 개수는 유지한다")
    @Test
    void paginatesResults() {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        for (int i = 0; i < 3; i++) {
            Product product = productRepository.save(Product.create(brand.getId(), "상품" + i, null, 1_000L, 5));
            insertLike(1L, product.getId(), "2026-01-0" + (i + 1) + "T00:00:00Z");
        }

        PageResult<LikedProductView> result = likeQueryDao.findByUserId(1L, new PageCriteria(0, 2));

        assertThat(result.items()).hasSize(2);
        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(2);
    }

    private void insertLike(long userId, long productId, String createdAt) {
        transactionTemplate.executeWithoutResult(status -> {
            likeRepository.save(Like.create(userId, productId));
            queryFactory.update(LIKE)
                .set(LIKE.createdAt, Instant.parse(createdAt))
                .where(LIKE.userId.eq(userId), LIKE.productId.eq(productId))
                .execute();
        });
    }

    private void insertLikeCount(long productId, long likeCount) {
        transactionTemplate.executeWithoutResult(status -> queryFactory.update(PRODUCT)
            .set(PRODUCT.likeCount, likeCount)
            .where(PRODUCT.id.eq(productId))
            .execute());
    }
}
