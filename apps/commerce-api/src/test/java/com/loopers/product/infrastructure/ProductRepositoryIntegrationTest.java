package com.loopers.product.infrastructure;

import com.loopers.like.domain.Like;
import com.loopers.like.domain.LikeRepository;
import com.loopers.product.domain.Product;
import com.loopers.product.domain.ProductRepository;
import com.loopers.product.domain.ProductSort;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import com.loopers.testcontainers.MySqlTestContainersConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
@Transactional
class ProductRepositoryIntegrationTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private EntityManager entityManager;

    @DisplayName("[R-ADMIN-12] 삭제된 상품은 고객 조회에서 제외한다.")
    @Nested
    class ExcludeDeletedProduct {

        @DisplayName("[상태 전이] 삭제한 상품은 flush·clear 후 고객 상품 목록에 나타나지 않는다.")
        @Test
        void excludesDeletedProductAfterReload() {
            Product active = productRepository.save(new Product(1L, "Active", 1_000L));
            Product deleted = productRepository.save(new Product(1L, "Deleted", 2_000L));
            deleted.delete();
            productRepository.save(deleted);
            flushAndClear();

            List<Product> result = productRepository.findCustomerProducts(
                null,
                ProductSort.LATEST,
                0,
                20
            );

            assertThat(result).extracting(Product::getId).containsExactly(active.getId());
        }
    }

    @DisplayName("[R-CATALOG-04] 특정 브랜드의 상품만 페이지 단위로 조회할 수 있다.")
    @Nested
    class FilterAndPageProducts {

        @DisplayName("[동등 클래스 분할] 브랜드 1의 첫 페이지 크기 1에는 해당 브랜드 상품 하나만 있다.")
        @Test
        void filtersByBrandAndLimitsPageSize() {
            Product first = productRepository.save(new Product(1L, "First", 1_000L));
            productRepository.save(new Product(1L, "Second", 2_000L));
            productRepository.save(new Product(2L, "Other", 3_000L));
            flushAndClear();

            List<Product> result = productRepository.findCustomerProducts(
                1L,
                ProductSort.PRICE_ASC,
                0,
                1
            );

            assertThat(result).extracting(Product::getId).containsExactly(first.getId());
        }
    }

    @DisplayName("[R-CATALOG-05] 상품 목록을 최신순·가격 오름차순·좋아요 내림차순으로 정렬할 수 있다.")
    @Nested
    class SortProducts {

        @DisplayName("[동등 클래스 분할] 각 지원 정렬은 계약에 맞는 첫 상품을 돌려준다.")
        @Test
        void supportsEveryProductSort() {
            Product cheap = productRepository.save(new Product(1L, "Cheap", 1_000L));
            Product liked = productRepository.save(new Product(1L, "Liked", 2_000L));
            Product latest = productRepository.save(new Product(1L, "Latest", 3_000L));
            likeRepository.save(new Like(1L, liked.getId()));
            likeRepository.save(new Like(2L, liked.getId()));
            flushAndClear();

            List<Product> byLatest = productRepository.findCustomerProducts(
                null,
                ProductSort.LATEST,
                0,
                20
            );
            List<Product> byPrice = productRepository.findCustomerProducts(
                null,
                ProductSort.PRICE_ASC,
                0,
                20
            );
            List<Product> byLikes = productRepository.findCustomerProducts(
                null,
                ProductSort.LIKES_DESC,
                0,
                20
            );

            assertThat(byLatest.getFirst().getId()).isEqualTo(latest.getId());
            assertThat(byPrice.getFirst().getId()).isEqualTo(cheap.getId());
            assertThat(byLikes.getFirst().getId()).isEqualTo(liked.getId());
        }
    }

    @DisplayName("[R-CATALOG-06] 정렬값이 같은 상품에는 보조 정렬 기준을 적용한다.")
    @Nested
    class StableSecondarySort {

        @DisplayName("[오류 추측] 같은 가격의 상품을 두 페이지로 조회해도 중복되거나 빠지지 않는다.")
        @Test
        void keepsEqualValuesStableAcrossPages() {
            Product first = productRepository.save(new Product(1L, "First", 1_000L));
            Product second = productRepository.save(new Product(1L, "Second", 1_000L));
            flushAndClear();

            List<Product> page0 = productRepository.findCustomerProducts(
                null,
                ProductSort.PRICE_ASC,
                0,
                1
            );
            List<Product> page1 = productRepository.findCustomerProducts(
                null,
                ProductSort.PRICE_ASC,
                1,
                1
            );

            assertThat(List.of(page0.getFirst().getId(), page1.getFirst().getId()))
                .containsExactly(first.getId(), second.getId());
        }
    }

    @DisplayName("[P-CATALOG-02] 상품 목록의 보조 정렬은 상품 식별자 오름차순이다.")
    @Nested
    class SortTiesByIdAscending {

        @DisplayName("[의사결정표] 가격이 같으면 상품 식별자가 작은 상품부터 조회한다.")
        @Test
        void sortsSamePriceByIdAscending() {
            Product smallerId = productRepository.save(new Product(1L, "First", 1_000L));
            Product largerId = productRepository.save(new Product(1L, "Second", 1_000L));
            flushAndClear();

            List<Product> result = productRepository.findCustomerProducts(
                null,
                ProductSort.PRICE_ASC,
                0,
                20
            );

            assertThat(result).extracting(Product::getId)
                .containsExactly(smallerId.getId(), largerId.getId());
        }
    }

    @DisplayName("[P-CATALOG-04] 최신순은 상품을 등록한 시점을 기준으로 한다.")
    @Nested
    class SortLatestByCreatedAt {

        @DisplayName("[상태 전이] 먼저 등록한 상품을 수정해도 나중에 등록한 상품이 최신순에서 앞선다.")
        @Test
        void updateDoesNotMakeProductLatest() {
            Product older = productRepository.save(new Product(1L, "Older", 1_000L));
            Product newer = productRepository.save(new Product(1L, "Newer", 2_000L));
            older.update("Updated", 1_500L, 1L);
            productRepository.save(older);
            flushAndClear();

            List<Product> result = productRepository.findCustomerProducts(
                null,
                ProductSort.LATEST,
                0,
                20
            );

            assertThat(result.getFirst().getId()).isEqualTo(newer.getId());
        }
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
