package com.loopers.infrastructure.like;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.like.LikedProduct;
import com.loopers.domain.product.Product;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class LikedProductQueryIntegrationTest {

    private static final long ME = 1L;
    private static final long OTHER = 2L;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Product saveProduct(Brand brand, String name) {
        return transactionTemplate.execute(status -> {
            Product product = new Product(entityManager.find(Brand.class, brand.getId()), name, 1_000L);
            entityManager.persist(product);
            return product;
        });
    }

    private Brand saveBrand() {
        return transactionTemplate.execute(status -> {
            Brand brand = new Brand("브랜드", null);
            entityManager.persist(brand);
            return brand;
        });
    }

    private void like(long userId, Product product) {
        transactionTemplate.executeWithoutResult(status -> entityManager.persist(new Like(userId, product.getId())));
    }

    private Page<LikedProduct> myLikes() {
        return likeRepository.findLikedProducts(ME, PageRequest.of(0, 20));
    }

    @DisplayName("내 관계만, 최근에 누른 순으로 돌려주고 좋아요 수는 모든 사용자의 관계에서 센다.")
    @Test
    void returnsOwnLikesByLikedAtDesc() {
        // arrange
        Brand brand = saveBrand();
        Product first = saveProduct(brand, "먼저 누른 상품");
        Product second = saveProduct(brand, "나중에 누른 상품");
        Product othersOnly = saveProduct(brand, "남만 누른 상품");
        like(ME, first);
        like(ME, second);
        like(OTHER, first);
        like(OTHER, othersOnly);

        // act
        Page<LikedProduct> page = myLikes();

        // assert
        assertAll(
            () -> assertThat(page.getContent()).extracting(LikedProduct::productId).containsExactly(second.getId(), first.getId()),
            () -> assertThat(page.getContent()).extracting(LikedProduct::likeCount).containsExactly(1L, 2L),
            () -> assertThat(page.getContent().get(0).brandName()).isEqualTo("브랜드"),
            () -> assertThat(page.getContent().get(0).likedAt()).isNotNull(),
            () -> assertThat(page.getTotalElements()).isEqualTo(2)
        );
    }

    @DisplayName("삭제된 상품의 관계는 목록과 개수에서 빠진다.")
    @Test
    void excludesDeletedProducts() {
        // arrange
        Brand brand = saveBrand();
        Product active = saveProduct(brand, "살아 있는 상품");
        Product deleted = saveProduct(brand, "삭제된 상품");
        like(ME, active);
        like(ME, deleted);
        transactionTemplate.executeWithoutResult(status -> entityManager.find(Product.class, deleted.getId()).delete());

        // act
        Page<LikedProduct> page = myLikes();

        // assert
        assertAll(
            () -> assertThat(page.getContent()).extracting(LikedProduct::productId).containsExactly(active.getId()),
            () -> assertThat(page.getTotalElements()).isEqualTo(1),
            () -> assertThat(likeRepository.find(ME, deleted.getId())).isPresent()
        );
    }
}
