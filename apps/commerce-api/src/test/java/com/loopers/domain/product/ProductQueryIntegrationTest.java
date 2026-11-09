package com.loopers.domain.product;

import com.loopers.domain.like.LikeModel;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductQueryIntegrationTest {

    private static final long BRAND_ID = 1L;
    private static final long OTHER_BRAND_ID = 2L;
    private static final int SAME_VALUE_COUNT = 30;
    private static final int PAGE_SIZE = 10;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private LikeJpaRepository likeJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("가격 오름차순으로 목록을 조회할 때, ")
    @Nested
    class PriceAsc {
        @DisplayName("가격이 같은 상품은 id 오름차순으로 반환한다.")
        @Test
        void returnsSamePriceProductsInIdAscendingOrder() {
            // arrange
            Long expensive = saveProduct(2_000L);
            Long sameFirst = saveProduct(1_000L);
            Long cheap = saveProduct(500L);
            Long sameSecond = saveProduct(1_000L);
            Long sameThird = saveProduct(1_000L);

            // act
            List<Long> ids = getProductIds(ProductSortType.PRICE_ASC, 0, 5);

            // assert
            assertThat(ids).containsExactly(cheap, sameFirst, sameSecond, sameThird, expensive);
        }

        @DisplayName("가격이 같은 상품이 여러 페이지에 걸쳐도, 페이지 사이에 중복이나 누락 없이 id 오름차순으로 이어진다.")
        @Test
        void returnsSamePriceProductsAcrossPagesWithoutDuplicatesOrGaps() {
            // arrange
            List<Long> savedIds = new ArrayList<>();
            for (int i = 0; i < SAME_VALUE_COUNT; i++) {
                savedIds.add(saveProduct(5_900L));
            }

            // act
            List<Long> pagedIds = getAllPages(ProductSortType.PRICE_ASC);

            // assert
            assertThat(pagedIds).containsExactlyElementsOf(sorted(savedIds, Comparator.naturalOrder()));
        }
    }

    @DisplayName("최신순으로 목록을 조회할 때, ")
    @Nested
    class Latest {
        @DisplayName("등록 시각이 같은 상품이 여러 페이지에 걸쳐도, 페이지 사이에 중복이나 누락 없이 id 내림차순으로 이어진다.")
        @Test
        void returnsSameCreatedAtProductsAcrossPagesWithoutDuplicatesOrGaps() {
            // arrange
            List<Long> savedIds = new ArrayList<>();
            for (int i = 0; i < SAME_VALUE_COUNT; i++) {
                savedIds.add(saveProduct(5_900L + i));
            }
            jdbcTemplate.update("UPDATE product SET created_at = '2026-01-01 00:00:00' WHERE brand_id = ?", BRAND_ID);

            // act
            List<Long> pagedIds = getAllPages(ProductSortType.LATEST);

            // assert
            assertThat(pagedIds).containsExactlyElementsOf(sorted(savedIds, Comparator.reverseOrder()));
        }
    }

    @DisplayName("좋아요순으로 목록을 조회할 때, ")
    @Nested
    class LikesDesc {
        @DisplayName("좋아요가 많은 상품부터, 좋아요 수가 같으면 id 오름차순으로 반환한다.")
        @Test
        void returnsProductsByLikeCountDescendingThenIdAscending() {
            // arrange
            Long one = saveProduct(1_000L);
            Long threeFirst = saveProduct(1_000L);
            Long none = saveProduct(1_000L);
            Long threeSecond = saveProduct(1_000L);
            Long two = saveProduct(1_000L);
            like(one, 1);
            like(threeFirst, 3);
            like(threeSecond, 3);
            like(two, 2);

            // act
            List<Long> ids = getProductIds(ProductSortType.LIKES_DESC, 0, 5);

            // assert
            assertThat(ids).containsExactly(threeFirst, threeSecond, two, one, none);
        }

        @DisplayName("취소된 좋아요는 좋아요 수에 넣지 않는다.")
        @Test
        void excludesCancelledLikes() {
            // arrange
            Long twoActive = saveProduct(1_000L);
            Long oneActiveTwoCancelled = saveProduct(1_000L);
            like(twoActive, 2);
            like(oneActiveTwoCancelled, 3);
            cancelLikes(oneActiveTwoCancelled, 2);

            // act
            List<Long> ids = getProductIds(ProductSortType.LIKES_DESC, 0, 2);

            // assert
            assertThat(ids).containsExactly(twoActive, oneActiveTwoCancelled);
        }

        @DisplayName("전체 상품을 좋아요 수로 정렬한 뒤 페이지를 나누므로, 페이지 사이에 중복이나 누락이 없다.")
        @Test
        void sortsAllProductsBeforeSplittingPages() {
            // arrange
            int[] likeCounts = {2, 5, 0, 4, 1, 3};
            List<Long> ids = new ArrayList<>();
            for (int likeCount : likeCounts) {
                Long id = saveProduct(1_000L);
                like(id, likeCount);
                ids.add(id);
            }

            // act
            List<Long> pagedIds = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                pagedIds.addAll(getProductIds(ProductSortType.LIKES_DESC, page, 2));
            }

            // assert
            assertThat(pagedIds).containsExactly(ids.get(1), ids.get(3), ids.get(5), ids.get(0), ids.get(4), ids.get(2));
        }

        @DisplayName("브랜드를 지정하면, 그 브랜드의 삭제되지 않은 상품만 반환한다.")
        @Test
        void returnsOnlyActiveProductsOfBrand() {
            // arrange
            Long liked = saveProduct(BRAND_ID, 1_000L);
            Long deleted = saveProduct(BRAND_ID, 1_000L);
            Long otherBrand = saveProduct(OTHER_BRAND_ID, 1_000L);
            Long notLiked = saveProduct(BRAND_ID, 1_000L);
            like(liked, 1);
            like(deleted, 5);
            like(otherBrand, 9);
            deleteProduct(deleted);

            // act
            List<Long> ids = getProductIds(BRAND_ID, ProductSortType.LIKES_DESC, 0, 10);

            // assert
            assertThat(ids).containsExactly(liked, notLiked);
        }

        @DisplayName("브랜드를 지정하지 않으면, 모든 브랜드의 삭제되지 않은 상품을 반환한다.")
        @Test
        void returnsActiveProductsOfAllBrands_whenBrandIsNotGiven() {
            // arrange
            Long liked = saveProduct(BRAND_ID, 1_000L);
            Long deleted = saveProduct(BRAND_ID, 1_000L);
            Long otherBrand = saveProduct(OTHER_BRAND_ID, 1_000L);
            Long notLiked = saveProduct(BRAND_ID, 1_000L);
            like(liked, 1);
            like(deleted, 5);
            like(otherBrand, 9);
            deleteProduct(deleted);

            // act
            List<Long> ids = getProductIds(null, ProductSortType.LIKES_DESC, 0, 10);

            // assert
            assertThat(ids).containsExactly(otherBrand, liked, notLiked);
        }
    }

    private Long saveProduct(Long price) {
        return saveProduct(BRAND_ID, price);
    }

    private Long saveProduct(Long brandId, Long price) {
        return productJpaRepository.save(new ProductModel(brandId, "product", price, 10)).getId();
    }

    private void deleteProduct(Long productId) {
        ProductModel product = productJpaRepository.findById(productId).orElseThrow();
        product.delete();
        productJpaRepository.save(product);
    }

    private void like(Long productId, int count) {
        for (long userId = 1; userId <= count; userId++) {
            likeJpaRepository.save(new LikeModel(userId, productId));
        }
    }

    private void cancelLikes(Long productId, int count) {
        for (long userId = 1; userId <= count; userId++) {
            LikeModel like = likeJpaRepository.findByUserIdAndProductId(userId, productId).orElseThrow();
            like.delete();
            likeJpaRepository.save(like);
        }
    }

    private List<Long> getProductIds(ProductSortType sortType, int page, int size) {
        return getProductIds(BRAND_ID, sortType, page, size);
    }

    private List<Long> getProductIds(Long brandId, ProductSortType sortType, int page, int size) {
        return productService.getProducts(brandId, sortType, PageRequest.of(page, size)).getContent().stream()
            .map(ProductModel::getId)
            .toList();
    }

    private List<Long> getAllPages(ProductSortType sortType) {
        List<Long> ids = new ArrayList<>();
        for (int page = 0; page < SAME_VALUE_COUNT / PAGE_SIZE; page++) {
            ids.addAll(getProductIds(sortType, page, PAGE_SIZE));
        }
        return ids;
    }

    private List<Long> sorted(List<Long> ids, Comparator<Long> order) {
        return ids.stream().sorted(order).toList();
    }
}
