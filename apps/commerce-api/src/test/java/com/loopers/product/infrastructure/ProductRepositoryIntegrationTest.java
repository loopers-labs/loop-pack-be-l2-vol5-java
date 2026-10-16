package com.loopers.product.infrastructure;

import com.loopers.like.domain.Like;
import com.loopers.like.domain.LikeRepository;
import com.loopers.product.domain.Product;
import com.loopers.product.domain.ProductRepository;
import com.loopers.product.domain.ProductSort;
import jakarta.persistence.EntityManager;
import org.springframework.test.util.ReflectionTestUtils;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @DisplayName("좋아요순 후보 필터를 위한 커버링 인덱스가 생성된다")
    @Test
    void createsLikesFilterIndex() {
        List<?> columns = entityManager.createNativeQuery("""
            select COLUMN_NAME from information_schema.STATISTICS
            where TABLE_SCHEMA = DATABASE() and TABLE_NAME = 'product'
              and INDEX_NAME = 'idx_product_likes_filter'
            order by SEQ_IN_INDEX
            """).getResultList();
        assertThat(columns).isEqualTo(List.of("deleted_at", "id", "brand_id"));
    }

    @DisplayName("최신순 인덱스는 필터 컬럼 뒤에 생성일 내림차순과 ID 오름차순을 둔다")
    @Test
    void createsLatestIndexesWithMatchingDirections() {
        for (String index : List.of("idx_product_latest", "idx_product_brand_latest")) {
            List<?> columns = entityManager.createNativeQuery("""
                select concat(COLUMN_NAME, ':', COLLATION) from information_schema.STATISTICS
                where TABLE_SCHEMA = DATABASE() and TABLE_NAME = 'product' and INDEX_NAME = :indexName
                order by SEQ_IN_INDEX
                """).setParameter("indexName", index).getResultList();
            assertThat(columns).isEqualTo(index.equals("idx_product_latest")
                ? List.of("deleted_at:A", "created_at:D", "id:A")
                : List.of("brand_id:A", "deleted_at:A", "created_at:D", "id:A"));
        }
    }

    @DisplayName("가격순 인덱스는 필터 컬럼 뒤에 가격과 ID 오름차순을 둔다")
    @Test
    void createsPriceIndexesWithMatchingDirections() {
        for (String index : List.of("idx_product_price", "idx_product_brand_price")) {
            List<?> columns = entityManager.createNativeQuery("""
                select concat(COLUMN_NAME, ':', COLLATION) from information_schema.STATISTICS
                where TABLE_SCHEMA = DATABASE() and TABLE_NAME = 'product' and INDEX_NAME = :indexName
                order by SEQ_IN_INDEX
                """).setParameter("indexName", index).getResultList();
            assertThat(columns).isEqualTo(index.equals("idx_product_price")
                ? List.of("deleted_at:A", "price:A", "id:A")
                : List.of("brand_id:A", "deleted_at:A", "price:A", "id:A"));
        }
    }

    @DisplayName("좋아요 집계 조인은 삭제·브랜드 필터, 0개·동점 정렬과 페이지 경계를 유지한다")
    @Test
    void preservesLikesQueryResultsAcrossFiltersAndPages() {
        Product zero = productRepository.save(new Product(1L, "Zero", 100L));
        Product first = productRepository.save(new Product(1L, "First liked", 100L));
        Product second = productRepository.save(new Product(1L, "Second liked", 100L));
        Product other = productRepository.save(new Product(2L, "Other brand", 100L));
        Product deleted = productRepository.save(new Product(1L, "Deleted liked", 100L));
        for (Product product : List.of(first, second, other, deleted)) {
            likeRepository.save(new Like(1L, product.getId()));
        }
        likeRepository.save(new Like(2L, other.getId()));
        likeRepository.save(new Like(2L, deleted.getId()));
        deleted.delete();
        productRepository.save(deleted);
        flushAndClear();

        assertThat(productRepository.findCustomerProducts(null, ProductSort.LIKES_DESC, 0, 20))
            .extracting(Product::getId)
            .containsExactly(other.getId(), first.getId(), second.getId(), zero.getId());
        assertThat(productRepository.findCustomerProducts(1L, ProductSort.LIKES_DESC, 0, 1))
            .extracting(Product::getId).containsExactly(first.getId());
        assertThat(productRepository.findCustomerProducts(1L, ProductSort.LIKES_DESC, 1, 1))
            .extracting(Product::getId).containsExactly(second.getId());
        assertThat(productRepository.findCustomerProducts(1L, ProductSort.LIKES_DESC, 2, 1))
            .extracting(Product::getId).containsExactly(zero.getId());
        assertThat(productRepository.findCustomerProducts(1L, ProductSort.LIKES_DESC, 3, 1)).isEmpty();
        assertThat(productRepository.findCustomerProducts(null, ProductSort.LIKES_DESC, 0, 2))
            .extracting(Product::getId).containsExactly(other.getId(), first.getId());
        assertThat(productRepository.findCustomerProducts(null, ProductSort.LIKES_DESC, 1, 2))
            .extracting(Product::getId).containsExactly(second.getId(), zero.getId());
        assertThat(productRepository.findCustomerProducts(null, ProductSort.LIKES_DESC, 2, 2)).isEmpty();

        Product nextZero = productRepository.save(new Product(1L, "Next zero", 100L));
        flushAndClear();
        assertThat(productRepository.findCustomerProducts(1L, ProductSort.LIKES_DESC, 0, 3))
            .extracting(Product::getId).containsExactly(first.getId(), second.getId(), zero.getId());
        assertThat(productRepository.findCustomerProducts(1L, ProductSort.LIKES_DESC, 1, 3))
            .extracting(Product::getId).containsExactly(nextZero.getId());
        assertThat(productRepository.findCustomerProducts(1L, ProductSort.LIKES_DESC, 1, 2))
            .extracting(Product::getId).containsExactly(zero.getId(), nextZero.getId());
        assertThat(productRepository.findCustomerProducts(1L, ProductSort.LIKES_DESC, 2, 2)).isEmpty();

        entityManager.createQuery("delete from Like").executeUpdate();
        flushAndClear();
        assertThat(productRepository.findCustomerProducts(1L, ProductSort.LIKES_DESC, 0, 2))
            .extracting(Product::getId).containsExactly(zero.getId(), first.getId());
        assertThat(productRepository.findCustomerProducts(1L, ProductSort.LIKES_DESC, 1, 2))
            .extracting(Product::getId).containsExactly(second.getId(), nextZero.getId());
        assertThat(productRepository.findCustomerProducts(1L, ProductSort.LIKES_DESC, 2, 2)).isEmpty();
        assertThat(productRepository.findCustomerProducts(99L, ProductSort.LIKES_DESC, 0, 2)).isEmpty();
        assertThat(productRepository.findCustomerProducts(null, ProductSort.LIKES_DESC, 0, 2))
            .extracting(Product::getId).containsExactly(zero.getId(), first.getId());
        assertThat(productRepository.findCustomerProducts(null, ProductSort.LIKES_DESC, 1, 2))
            .extracting(Product::getId).containsExactly(second.getId(), other.getId());
        assertThat(productRepository.findCustomerProducts(null, ProductSort.LIKES_DESC, 2, 2))
            .extracting(Product::getId).containsExactly(nextZero.getId());
        assertThat(productRepository.findCustomerProducts(null, ProductSort.LIKES_DESC, 3, 2)).isEmpty();
    }

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

        @DisplayName("[경계값 분석] 가격이 같으면 상품 식별자가 작은 상품부터 조회한다.")
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

    @DisplayName("DB에서 발견한 생성 시 이름 중복을 기존 도메인 오류로 반환한다")
    @Test
    void translatesDatabaseDuplicateOnInsert() {
        productRepository.save(new Product(1L, "동일 이름", 100L));
        assertThatThrownBy(() -> productRepository.save(new Product(1L, "동일 이름", 100L)))
            .isInstanceOfSatisfying(CoreException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_PRODUCT_NAME));
    }

    @DisplayName("수정 SQL의 이름 중복도 commit 이전에 기존 도메인 오류로 반환한다")
    @Test
    void translatesDatabaseDuplicateOnUpdate() {
        productRepository.save(new Product(1L, "동일 이름", 100L));
        Product target = productRepository.save(new Product(1L, "다른 이름", 100L));
        target.update("동일 이름", 100L, 1L);
        assertThatThrownBy(() -> productRepository.save(target))
            .isInstanceOfSatisfying(CoreException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_PRODUCT_NAME));
    }

    @DisplayName("이름 중복과 관계없는 DB 오류를 이름 중복으로 변환하지 않는다")
    @Test
    void doesNotTranslateOtherConstraintFailures() {
        Product invalid = new Product(1L, "정상 이름", 100L);
        ReflectionTestUtils.setField(invalid, "price", 0L);
        assertThatThrownBy(() -> productRepository.save(invalid))
            .isInstanceOf(jakarta.persistence.PersistenceException.class)
            .isNotInstanceOf(CoreException.class);
    }
}
