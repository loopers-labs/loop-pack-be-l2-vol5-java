package com.loopers.infrastructure.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.like.Like;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.ProductView;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class ProductViewQueryIntegrationTest {

    @Autowired
    private ProductRepository productRepository;

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

    private Brand saveBrand(String name) {
        return transactionTemplate.execute(status -> {
            Brand brand = new Brand(name, name + " 설명");
            entityManager.persist(brand);
            return brand;
        });
    }

    private Product saveProduct(Brand brand, String name, long price, int stock) {
        return transactionTemplate.execute(status -> {
            Product product = new Product(entityManager.find(Brand.class, brand.getId()), name, price);
            product.changeStock(stock);
            entityManager.persist(product);
            return product;
        });
    }

    private void like(Product product, long... userIds) {
        transactionTemplate.executeWithoutResult(status -> {
            for (long userId : userIds) {
                entityManager.persist(new Like(userId, product.getId()));
            }
        });
    }

    private void delete(Product product) {
        transactionTemplate.executeWithoutResult(status -> entityManager.find(Product.class, product.getId()).delete());
    }

    private Page<ProductView> views(Long brandId, ProductSort sort) {
        return productRepository.findActiveViews(brandId, sort, PageRequest.of(0, 20));
    }

    @DisplayName("좋아요가 없는 상품도 좋아요 수 0 으로 포함되고, 좋아요 수는 관계에서 센다.")
    @Test
    void includesProductsWithoutLikes() {
        // arrange
        Brand brand = saveBrand("브랜드");
        Product liked = saveProduct(brand, "좋아요 받은 상품", 1_000L, 1);
        Product notLiked = saveProduct(brand, "좋아요 없는 상품", 1_000L, 1);
        like(liked, 1L, 2L);

        // act
        Page<ProductView> page = views(null, ProductSort.LATEST);

        // assert
        assertAll(
            () -> assertThat(page.getContent()).extracting(ProductView::id).containsExactly(notLiked.getId(), liked.getId()),
            () -> assertThat(page.getContent()).extracting(ProductView::likeCount).containsExactly(0L, 2L),
            () -> assertThat(page.getTotalElements()).isEqualTo(2)
        );
    }

    @DisplayName("정렬할 때, ")
    @Nested
    class Sort {
        @DisplayName("likes_desc 는 좋아요 수 역순이고, 같으면 식별자 역순이다.")
        @Test
        void sortsByLikesDescThenIdDesc() {
            // arrange
            Brand brand = saveBrand("브랜드");
            Product one = saveProduct(brand, "좋아요 1 (먼저 생성)", 1_000L, 1);
            Product two = saveProduct(brand, "좋아요 2", 1_000L, 1);
            Product anotherOne = saveProduct(brand, "좋아요 1 (나중 생성)", 1_000L, 1);
            Product zero = saveProduct(brand, "좋아요 0", 1_000L, 1);
            like(one, 1L);
            like(two, 1L, 2L);
            like(anotherOne, 2L);

            // act & assert
            assertThat(views(null, ProductSort.LIKES_DESC).getContent()).extracting(ProductView::id)
                .containsExactly(two.getId(), anotherOne.getId(), one.getId(), zero.getId());
        }

        @DisplayName("price_asc 는 가격 오름차순이고, 같으면 식별자 역순이다.")
        @Test
        void sortsByPriceAscThenIdDesc() {
            // arrange
            Brand brand = saveBrand("브랜드");
            Product expensive = saveProduct(brand, "비싼 상품", 3_000L, 1);
            Product cheapFirst = saveProduct(brand, "싼 상품 (먼저 생성)", 1_000L, 1);
            Product cheapLater = saveProduct(brand, "싼 상품 (나중 생성)", 1_000L, 1);

            // act & assert
            assertThat(views(null, ProductSort.PRICE_ASC).getContent()).extracting(ProductView::id)
                .containsExactly(cheapLater.getId(), cheapFirst.getId(), expensive.getId());
        }

        @DisplayName("latest 는 생성 시각 역순이다.")
        @Test
        void sortsByLatest() {
            // arrange
            Brand brand = saveBrand("브랜드");
            Product first = saveProduct(brand, "첫 번째", 1_000L, 1);
            Product second = saveProduct(brand, "두 번째", 1_000L, 1);

            // act & assert
            assertThat(views(null, ProductSort.LATEST).getContent()).extracting(ProductView::id)
                .containsExactly(second.getId(), first.getId());
        }
    }

    @DisplayName("브랜드로 거르면 그 브랜드의 상품만 돌려주고, 없는 브랜드면 빈 결과다.")
    @Test
    void filtersByBrand() {
        // arrange
        Brand brand = saveBrand("브랜드");
        Brand other = saveBrand("다른 브랜드");
        Product product = saveProduct(brand, "상품", 1_000L, 1);
        saveProduct(other, "다른 상품", 1_000L, 1);

        // act & assert
        assertAll(
            () -> assertThat(views(brand.getId(), ProductSort.LATEST).getContent()).extracting(ProductView::id)
                .containsExactly(product.getId()),
            () -> assertThat(views(999_999L, ProductSort.LATEST).getContent()).isEmpty(),
            () -> assertThat(views(999_999L, ProductSort.LATEST).getTotalElements()).isZero()
        );
    }

    @DisplayName("삭제된 상품은 목록 · 개수 · 상세에서 빠진다.")
    @Test
    void excludesDeletedProducts() {
        // arrange
        Brand brand = saveBrand("브랜드");
        Product active = saveProduct(brand, "살아 있는 상품", 1_000L, 1);
        Product deleted = saveProduct(brand, "삭제된 상품", 1_000L, 1);
        like(deleted, 1L);
        delete(deleted);

        // act
        Page<ProductView> page = views(null, ProductSort.LIKES_DESC);

        // assert
        assertAll(
            () -> assertThat(page.getContent()).extracting(ProductView::id).containsExactly(active.getId()),
            () -> assertThat(page.getTotalElements()).isEqualTo(1),
            () -> assertThat(productRepository.findActiveView(deleted.getId())).isEmpty()
        );
    }

    @DisplayName("페이지를 나누면, 요청한 페이지의 상품과 전체 개수를 돌려준다.")
    @Test
    void paginates() {
        // arrange
        Brand brand = saveBrand("브랜드");
        Product first = saveProduct(brand, "첫 번째", 1_000L, 1);
        saveProduct(brand, "두 번째", 1_000L, 1);
        saveProduct(brand, "세 번째", 1_000L, 1);

        // act
        Page<ProductView> secondPage = productRepository.findActiveViews(null, ProductSort.LATEST, PageRequest.of(1, 2));

        // assert
        assertAll(
            () -> assertThat(secondPage.getContent()).extracting(ProductView::id).containsExactly(first.getId()),
            () -> assertThat(secondPage.getTotalElements()).isEqualTo(3),
            () -> assertThat(secondPage.getTotalPages()).isEqualTo(2)
        );
    }

    @DisplayName("상세는 브랜드 설명 · 좋아요 수 · 재고 수량을 담는다.")
    @Test
    void readsDetail() {
        // arrange
        Brand brand = saveBrand("브랜드");
        Product product = saveProduct(brand, "상품", 2_000L, 0);
        like(product, 1L, 2L, 3L);

        // act
        ProductView view = productRepository.findActiveView(product.getId()).orElseThrow();

        // assert
        assertAll(
            () -> assertThat(view.name()).isEqualTo("상품"),
            () -> assertThat(view.price()).isEqualTo(2_000L),
            () -> assertThat(view.stock()).isZero(),
            () -> assertThat(view.likeCount()).isEqualTo(3),
            () -> assertThat(view.brandName()).isEqualTo("브랜드"),
            () -> assertThat(view.brandDescription()).isEqualTo("브랜드 설명")
        );
    }
}
