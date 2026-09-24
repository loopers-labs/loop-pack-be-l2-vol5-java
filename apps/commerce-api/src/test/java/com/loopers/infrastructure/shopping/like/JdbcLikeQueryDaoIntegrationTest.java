package com.loopers.infrastructure.shopping.like;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.common.PageCriteria;
import com.loopers.application.common.PageResult;
import com.loopers.application.mall.brand.BrandCommand;
import com.loopers.application.mall.brand.DeleteBrandUseCase;
import com.loopers.application.shopping.like.LikeItem;
import com.loopers.application.shopping.like.LikeQueryDao;
import com.loopers.domain.mall.brand.Brand;
import com.loopers.domain.mall.brand.BrandRepository;
import com.loopers.domain.mall.product.Product;
import com.loopers.domain.mall.product.ProductRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class JdbcLikeQueryDaoIntegrationTest {
    @Autowired
    private LikeQueryDao likeQueryDao;
    @Autowired
    private DeleteBrandUseCase deleteBrandUseCase;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private JdbcClient jdbcClient;
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
        insertLike(1L, older.getId(), "2026-01-01 00:00:00");
        insertLike(1L, newer.getId(), "2026-01-02 00:00:00");
        insertLikeCount(newer.getId(), 3L);

        PageResult<LikeItem> result = likeQueryDao.findByUserId(1L, new PageCriteria(0, 20));

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.items()).extracting(LikeItem::productId).containsExactly(newer.getId(), older.getId());
        LikeItem newerItem = result.items().get(0);
        assertThat(newerItem.name()).isEqualTo("최근 상품");
        assertThat(newerItem.price()).isEqualTo(2_000L);
        assertThat(newerItem.brand().brandId()).isEqualTo(brand.getId());
        assertThat(newerItem.likeCount()).isEqualTo(3L);
        LikeItem olderItem = result.items().get(1);
        assertThat(olderItem.likeCount()).isZero();
    }

    @DisplayName("좋아요 시각이 같으면 상품 ID 내림차순으로 정렬한다")
    @Test
    void ordersByProductIdDescending_whenLikedAtTies() {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        Product first = productRepository.save(Product.create(brand.getId(), "상품1", null, 1_000L, 5));
        Product second = productRepository.save(Product.create(brand.getId(), "상품2", null, 1_000L, 5));
        insertLike(1L, first.getId(), "2026-01-01 00:00:00");
        insertLike(1L, second.getId(), "2026-01-01 00:00:00");

        PageResult<LikeItem> result = likeQueryDao.findByUserId(1L, new PageCriteria(0, 20));

        assertThat(result.items()).extracting(LikeItem::productId)
            .containsExactly(Math.max(first.getId(), second.getId()), Math.min(first.getId(), second.getId()));
    }

    @DisplayName("삭제된 상품은 목록과 전체 개수에서 제외한다")
    @Test
    void excludesDeletedProducts() {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        Product deleted = productRepository.save(Product.create(brand.getId(), "삭제 상품", null, 1_000L, 5));
        deleted.delete();
        productRepository.save(deleted);
        insertLike(1L, deleted.getId(), "2026-01-01 00:00:00");

        PageResult<LikeItem> result = likeQueryDao.findByUserId(1L, new PageCriteria(0, 20));

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }

    @DisplayName("브랜드 일괄 삭제로 상품이 삭제되면 좋아요 목록에서 제외한다")
    @Test
    void excludesProducts_deletedViaBrandBulkDelete() {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        Product product = productRepository.save(Product.create(brand.getId(), "상품", null, 1_000L, 5));
        insertLike(1L, product.getId(), "2026-01-01 00:00:00");

        deleteBrandUseCase.execute(new BrandCommand.Delete(brand.getId()));

        PageResult<LikeItem> result = likeQueryDao.findByUserId(1L, new PageCriteria(0, 20));

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }

    @DisplayName("페이지 크기만큼 잘라서 반환하고 전체 개수는 유지한다")
    @Test
    void paginatesResults() {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        for (int i = 0; i < 3; i++) {
            Product product = productRepository.save(Product.create(brand.getId(), "상품" + i, null, 1_000L, 5));
            insertLike(1L, product.getId(), "2026-01-0" + (i + 1) + " 00:00:00");
        }

        PageResult<LikeItem> result = likeQueryDao.findByUserId(1L, new PageCriteria(0, 2));

        assertThat(result.items()).hasSize(2);
        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(2);
    }

    private void insertLike(long userId, long productId, String createdAt) {
        jdbcClient.sql("INSERT INTO product_likes (user_id, product_id, created_at) VALUES (:userId, :productId, :createdAt)")
            .param("userId", userId)
            .param("productId", productId)
            .param("createdAt", createdAt)
            .update();
    }

    private void insertLikeCount(long productId, long likeCount) {
        jdbcClient.sql("INSERT INTO product_like_counts (product_id, like_count) VALUES (:productId, :likeCount)")
            .param("productId", productId)
            .param("likeCount", likeCount)
            .update();
    }
}
