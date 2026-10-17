package com.loopers.infrastructure.product;

import com.loopers.domain.common.Money;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
import com.loopers.support.fixture.ExposedProducts;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.ArrayList;
import java.util.List;

@SpringBootTest
class ProductRepositoryTest {

    @Autowired
    private ExposedProducts exposedProducts;

    @Autowired
    private ProductRepository productRepository;

    // 좋아요 수 집계도 함께 바뀌어야 해서 관계는 LikeService 로 만든다.
    @Autowired
    private LikeService likeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Product saveProduct(Long brandId, String name, int stock) {
        return productRepository.save(Product.create(brandId, name, new Money(89_000), new Stock(stock)));
    }

    private Product saveDeletedProduct(Long brandId, String name) {
        Product product = Product.create(brandId, name, new Money(89_000), new Stock(1));
        product.delete();
        return productRepository.save(product);
    }

    @DisplayName("상품을 저장하고 조회할 때,")
    @Nested
    class SaveAndFind {
        @DisplayName("저장한 브랜드·이름·가격·재고가 그대로 조회되고, 생성·수정 시각이 채워진다.")
        @Test
        void returnsSavedProduct_whenProductIsSaved() {
            // arrange
            Product saved = saveProduct(3L, "러닝화", 10);

            // act
            Optional<Product> found = productRepository.findActive(saved.getId());

            // assert
            assertThat(found).isPresent();
            assertThat(found.get().getBrandId()).isEqualTo(3L);
            assertThat(found.get().getName()).isEqualTo("러닝화");
            assertThat(found.get().getPrice()).isEqualTo(new Money(89_000));
            assertThat(found.get().getStock()).isEqualTo(new Stock(10));
            assertThat(found.get().getCreatedAt()).isNotNull();
            assertThat(found.get().getUpdatedAt()).isNotNull();
        }

        @DisplayName("삭제된 상품은 조회되지 않는다.")
        @Test
        void returnsEmpty_whenProductIsDeleted() {
            // arrange
            Product deleted = saveDeletedProduct(1L, "삭제됨");

            // act
            Optional<Product> found = productRepository.findActive(deleted.getId());

            // assert
            assertThat(found).isEmpty();
        }

        @DisplayName("존재하지 않는 id 이면 빈 결과를 반환한다.")
        @Test
        void returnsEmpty_whenProductDoesNotExist() {
            // act
            Optional<Product> found = productRepository.findActive(999L);

            // assert
            assertThat(found).isEmpty();
        }
    }

    @DisplayName("상품 목록을 조회할 때,")
    @Nested
    class FindAllActive {
        @DisplayName("삭제된 상품은 빼고 id 내림차순으로 반환한다.")
        @Test
        void excludesDeleted_andOrdersByIdDesc() {
            // arrange
            Product first = saveProduct(1L, "첫째", 1);
            saveDeletedProduct(1L, "삭제됨");
            Product third = saveProduct(2L, "셋째", 1);

            // act
            Page<Product> page = productRepository.findAllActive(PageRequest.of(0, 10));

            // assert
            assertThat(page.getContent()).extracting(Product::getId)
                .containsExactly(third.getId(), first.getId());
            assertThat(page.getTotalElements()).isEqualTo(2);
        }

        @DisplayName("페이지 크기만큼만 반환하고, 전체 페이지 수를 계산한다.")
        @Test
        void returnsOnlyPageSize_andCalculatesTotalPages() {
            // arrange
            saveProduct(1L, "하나", 1);
            saveProduct(1L, "둘", 1);
            saveProduct(1L, "셋", 1);

            // act
            Page<Product> secondPage = productRepository.findAllActive(PageRequest.of(1, 2));

            // assert
            assertThat(secondPage.getContent()).hasSize(1);
            assertThat(secondPage.getTotalPages()).isEqualTo(2);
            assertThat(secondPage.getTotalElements()).isEqualTo(3);
        }
    }

    @DisplayName("브랜드의 삭제되지 않은 상품을 잠그고 조회할 때,")
    @Nested
    class FindAllActiveByBrandIdForUpdate {
        // 잠금 조회는 트랜잭션 안에서만 호출할 수 있다. 운영에서는 Facade 의 @Transactional 이 그 역할을 한다.
        private List<Product> findForUpdate(Long brandId) {
            return new TransactionTemplate(transactionManager)
                .execute(status -> productRepository.findAllActiveByBrandIdForUpdate(brandId));
        }

        @DisplayName("재고가 0인 상품도 포함해 id 오름차순으로 반환한다.")
        @Test
        void returnsActiveProducts_includingNoStock_orderedById() {
            // arrange
            Product first = saveProduct(1L, "러닝화", 10);
            Product second = saveProduct(1L, "품절", 0);
            Product third = saveProduct(1L, "양말", 3);

            // act
            List<Product> products = findForUpdate(1L);

            // assert
            assertThat(products).extracting(Product::getId)
                .containsExactly(first.getId(), second.getId(), third.getId());
        }

        @DisplayName("삭제된 상품과 다른 브랜드의 상품은 제외한다.")
        @Test
        void excludesDeletedAndOtherBrandProducts() {
            // arrange
            Product active = saveProduct(1L, "러닝화", 10);
            saveDeletedProduct(1L, "삭제됨");
            saveProduct(2L, "다른 브랜드", 10);

            // act
            List<Product> products = findForUpdate(1L);

            // assert
            assertThat(products).extracting(Product::getId).containsExactly(active.getId());
        }

        @DisplayName("연결된 상품이 없으면 빈 목록이다.")
        @Test
        void returnsEmpty_whenNoProducts() {
            assertThat(findForUpdate(1L)).isEmpty();
        }
    }

    @DisplayName("사용자가 좋아요한 상품 목록을 조회할 때,")
    @Nested
    class FindAllActiveLikedBy {
        private void like(Long userId, Product product) {
            likeService.like(userId, product.getId());
        }

        @DisplayName("내가 좋아요한 상품만, 최근에 좋아요한 순으로 반환한다.")
        @Test
        void returnsOnlyMyLikedProducts_newestLikeFirst() {
            // arrange
            Product first = saveProduct(1L, "첫째", 1);
            Product second = saveProduct(1L, "둘째", 1);
            Product third = saveProduct(1L, "셋째", 1);
            like(1L, third);   // 먼저 좋아요
            like(1L, first);   // 나중에 좋아요
            like(2L, second);  // 다른 사용자의 좋아요

            // act
            Page<Product> page = productRepository.findAllActiveLikedBy(1L, PageRequest.of(0, 10));

            // assert: 상품 id 순서(셋째, 첫째)가 아니라 좋아요한 시각의 역순이다.
            assertThat(page.getContent()).extracting(Product::getId)
                .containsExactly(first.getId(), third.getId());
            assertThat(page.getTotalElements()).isEqualTo(2);
        }

        @DisplayName("삭제된 상품은 제외한다.")
        @Test
        void excludesDeletedProducts() {
            // arrange
            Product active = saveProduct(1L, "판매 중", 1);
            Product deleted = saveDeletedProduct(1L, "삭제됨");
            like(1L, active);
            like(1L, deleted);

            // act
            Page<Product> page = productRepository.findAllActiveLikedBy(1L, PageRequest.of(0, 10));

            // assert
            assertThat(page.getContent()).extracting(Product::getId).containsExactly(active.getId());
            assertThat(page.getTotalElements()).isEqualTo(1);
        }

        @DisplayName("페이지 크기만큼만 반환하고, 총 개수와 전체 페이지 수를 계산한다.")
        @Test
        void returnsOnlyPageSize_andCalculatesTotals() {
            // arrange
            like(1L, saveProduct(1L, "하나", 1));
            like(1L, saveProduct(1L, "둘", 1));
            like(1L, saveProduct(1L, "셋", 1));

            // act
            Page<Product> secondPage = productRepository.findAllActiveLikedBy(1L, PageRequest.of(1, 2));

            // assert
            assertThat(secondPage.getContent()).hasSize(1);
            assertThat(secondPage.getTotalElements()).isEqualTo(3);
            assertThat(secondPage.getTotalPages()).isEqualTo(2);
        }

        @DisplayName("좋아요한 상품이 없으면 빈 목록이다.")
        @Test
        void returnsEmpty_whenUserHasNoLikes() {
            // arrange
            saveProduct(1L, "러닝화", 1);

            // act
            Page<Product> page = productRepository.findAllActiveLikedBy(1L, PageRequest.of(0, 10));

            // assert
            assertThat(page.getContent()).isEmpty();
            assertThat(page.getTotalElements()).isZero();
        }
    }

    @DisplayName("정렬 기준과 브랜드 조건으로 상품 목록을 조회할 때,")
    @Nested
    class FindAllActiveBySort {
        private Product saveWithPrice(Long brandId, String name, long price) {
            return exposedProducts.save(Product.create(brandId, name, new Money(price), new Stock(1)));
        }

        private void like(Long userId, Product product) {
            likeService.like(userId, product.getId());
        }

        private PageRequest firstPage() {
            return PageRequest.of(0, 10);
        }

        @DisplayName("latest 는 상품 id 내림차순이다.")
        @Test
        void ordersByIdDesc_whenLatest() {
            // arrange
            Product first = saveWithPrice(1L, "첫째", 1_000);
            Product second = saveWithPrice(1L, "둘째", 1_000);
            Product third = saveWithPrice(1L, "셋째", 1_000);

            // act
            Page<Product> page = productRepository.findAllActive(null, ProductSort.LATEST, firstPage());

            // assert
            assertThat(page.getContent()).extracting(Product::getId)
                .containsExactly(third.getId(), second.getId(), first.getId());
        }

        @DisplayName("price_asc 는 가격 오름차순이고, 가격이 같으면 id 내림차순이다.")
        @Test
        void ordersByPriceAsc_thenIdDesc() {
            // arrange
            Product expensive = saveWithPrice(1L, "비쌈", 3_000);
            Product cheapOld = saveWithPrice(1L, "싼 것(먼저)", 1_000);
            Product cheapNew = saveWithPrice(1L, "싼 것(나중)", 1_000);
            Product middle = saveWithPrice(1L, "중간", 2_000);

            // act
            Page<Product> page = productRepository.findAllActive(null, ProductSort.PRICE_ASC, firstPage());

            // assert
            assertThat(page.getContent()).extracting(Product::getId)
                .containsExactly(cheapNew.getId(), cheapOld.getId(), middle.getId(), expensive.getId());
        }

        @DisplayName("likes_desc 는 좋아요 수 내림차순이고, 같으면 id 내림차순이다. 좋아요가 없는 상품도 포함한다.")
        @Test
        void ordersByLikeCountDesc_thenIdDesc_includingProductsWithoutLikes() {
            // arrange
            Product popular = saveWithPrice(1L, "인기", 1_000);       // 좋아요 2
            Product none = saveWithPrice(1L, "좋아요 없음", 1_000);     // 좋아요 0
            Product tiedOld = saveWithPrice(1L, "동률(먼저)", 1_000);   // 좋아요 1
            Product tiedNew = saveWithPrice(1L, "동률(나중)", 1_000);   // 좋아요 1
            like(1L, popular);
            like(2L, popular);
            like(1L, tiedOld);
            like(1L, tiedNew);

            // act
            Page<Product> page = productRepository.findAllActive(null, ProductSort.LIKES_DESC, firstPage());

            // assert
            assertThat(page.getContent()).extracting(Product::getId)
                .containsExactly(popular.getId(), tiedNew.getId(), tiedOld.getId(), none.getId());
        }

        @DisplayName("어떤 정렬이든 삭제된 상품은 제외한다. 삭제된 상품에 좋아요가 많아도 마찬가지다.")
        @ParameterizedTest
        @EnumSource(ProductSort.class)
        void excludesDeletedProducts(ProductSort sort) {
            // arrange
            Product active = saveWithPrice(1L, "판매 중", 1_000);
            Product deleted = saveDeletedProduct(1L, "삭제됨");
            like(1L, deleted);
            like(2L, deleted);
            like(3L, deleted);

            // act
            Page<Product> page = productRepository.findAllActive(null, sort, firstPage());

            // assert
            assertThat(page.getContent()).extracting(Product::getId).containsExactly(active.getId());
            assertThat(page.getTotalElements()).isEqualTo(1);
        }

        @DisplayName("브랜드를 지정하면 그 브랜드의 상품만 반환한다. 존재하지 않는 브랜드이면 빈 결과다.")
        @ParameterizedTest
        @EnumSource(ProductSort.class)
        void filtersByBrand(ProductSort sort) {
            // arrange
            Product brand1First = saveWithPrice(1L, "1번 브랜드(먼저)", 1_000);
            Product brand1Second = saveWithPrice(1L, "1번 브랜드(나중)", 1_000);
            saveWithPrice(2L, "2번 브랜드", 1_000);

            // act
            Page<Product> filtered = productRepository.findAllActive(1L, sort, firstPage());
            Page<Product> unknown = productRepository.findAllActive(999L, sort, firstPage());

            // assert
            assertThat(filtered.getContent()).extracting(Product::getId)
                .containsExactly(brand1Second.getId(), brand1First.getId());
            assertThat(filtered.getTotalElements()).isEqualTo(2);
            assertThat(unknown.getContent()).isEmpty();
            assertThat(unknown.getTotalElements()).isZero();
        }

        @DisplayName("likes_desc 의 총 개수는 좋아요 행이 아니라 상품 수다.")
        @Test
        void countsProducts_notLikeRows_whenLikesDesc() {
            // arrange: 상품 2개에 좋아요가 3개씩
            Product first = saveWithPrice(1L, "하나", 1_000);
            Product second = saveWithPrice(1L, "둘", 1_000);
            for (long userId = 1; userId <= 3; userId++) {
                like(userId, first);
                like(userId, second);
            }

            // act
            Page<Product> page = productRepository.findAllActive(null, ProductSort.LIKES_DESC, firstPage());

            // assert
            assertThat(page.getContent()).hasSize(2);
            assertThat(page.getTotalElements()).isEqualTo(2);
            assertThat(page.getTotalPages()).isEqualTo(1);
        }

        @DisplayName("페이지 크기만큼만 반환하고, 총 개수와 전체 페이지 수를 계산한다.")
        @ParameterizedTest
        @EnumSource(ProductSort.class)
        void returnsOnlyPageSize_andCalculatesTotals(ProductSort sort) {
            // arrange
            saveWithPrice(1L, "하나", 1_000);
            saveWithPrice(1L, "둘", 1_000);
            saveWithPrice(1L, "셋", 1_000);

            // act
            Page<Product> secondPage = productRepository.findAllActive(null, sort, PageRequest.of(1, 2));

            // assert
            assertThat(secondPage.getContent()).hasSize(1);
            assertThat(secondPage.getTotalElements()).isEqualTo(3);
            assertThat(secondPage.getTotalPages()).isEqualTo(2);
        }
    }

    @DisplayName("좋아요 수 집계(product_like_counts)는,")
    @Nested
    class LikeCountRows {
        // 상품 생성·삭제 유스케이스처럼 좋아요 수 집계 행도 함께 만들고 지운다(행의 생성·삭제 검증은 Admin Facade 테스트).
        private Product saveWithPrice(Long brandId, String name, long price) {
            return exposedProducts.save(Product.create(brandId, name, new Money(price), new Stock(1)));
        }

        private void like(Long userId, Product product) {
            likeService.like(userId, product.getId());
        }

        private void delete(Product product) {
            product.delete();
            productRepository.save(product);
            likeService.removeCounts(List.of(product.getId()));
        }

        @DisplayName("삭제된 상품의 행이 지워지면, 그 상품이 위에 있었어도 페이지는 크기를 채운다.")
        @Test
        void removesRow_andFillsPage_afterDelete() {
            // arrange: 좋아요 5·4·3·2·1개. 가장 많은 상품을 삭제한다.
            List<Product> products = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                products.add(saveWithPrice(1L, "상품" + i, 1_000));
            }
            for (int i = 0; i < 5; i++) {
                for (long userId = 1; userId <= 5 - i; userId++) {
                    like(userId, products.get(i));
                }
            }
            Product mostLiked = products.get(0);
            delete(mostLiked);

            // act
            Page<Product> page = productRepository.findAllActive(null, ProductSort.LIKES_DESC, PageRequest.of(0, 3));

            // assert
            assertThat(page.getContent()).extracting(Product::getId)
                .containsExactly(products.get(1).getId(), products.get(2).getId(), products.get(3).getId());
            assertThat(page.getTotalElements()).isEqualTo(4);
            assertThat(page.getTotalPages()).isEqualTo(2);
        }

        @DisplayName("삭제 상품의 행이 경쟁 등으로 남아 있어도 목록에는 나오지 않는다.")
        @Test
        void skipsStaleRowOfDeletedProduct() {
            // arrange: 삭제된 상품에 집계 행이 잘못 남은 상태를 만든다.
            Product active = saveWithPrice(1L, "판매 중", 1_000);
            Product deleted = saveWithPrice(1L, "삭제됨", 1_000);
            delete(deleted);
            jdbcTemplate.update("insert into product_like_counts (product_id, like_count) values (?, 999)", deleted.getId());

            // act
            Page<Product> page = productRepository.findAllActive(null, ProductSort.LIKES_DESC, PageRequest.of(0, 10));

            // assert
            assertThat(page.getContent()).extracting(Product::getId).containsExactly(active.getId());
            assertThat(page.getTotalElements()).isEqualTo(1);
        }

        @DisplayName("브랜드를 지정한 좋아요순도 집계로 정렬하고, 좋아요가 없는 상품도 포함하며 다른 브랜드는 제외한다.")
        @Test
        void ordersByCount_whenBrandIsGiven() {
            // arrange
            Product none = saveWithPrice(1L, "없음", 1_000);
            Product few = saveWithPrice(1L, "조금", 1_000);
            Product many = saveWithPrice(1L, "많이", 1_000);
            Product otherBrand = saveWithPrice(2L, "다른 브랜드", 1_000);
            like(1L, few);
            like(1L, many);
            like(2L, many);
            like(1L, otherBrand);
            like(2L, otherBrand);
            like(3L, otherBrand);

            // act
            Page<Product> page = productRepository.findAllActive(1L, ProductSort.LIKES_DESC, PageRequest.of(0, 10));

            // assert
            assertThat(page.getContent()).extracting(Product::getId)
                .containsExactly(many.getId(), few.getId(), none.getId());
            assertThat(page.getTotalElements()).isEqualTo(3);
        }
    }

    @DisplayName("상품 테이블 스키마는,")
    @Nested
    class Schema {
        @DisplayName("브랜드별 상품 조회(목록 필터, 브랜드 삭제 확인)를 위해 brand_id 로 시작하는 (brand_id, deleted_at, price, id DESC) 인덱스를 가진다.")
        @Test
        void hasBrandPriceIndexWithDescendingId() {
            // act: 컬럼을 인덱스 안의 순서대로, 정렬 방향(A 오름차순, D 내림차순)과 함께 읽는다.
            List<String> columns = jdbcTemplate.queryForList(
                "select concat(column_name, ':', collation) from information_schema.statistics "
                    + "where table_schema = database() and table_name = 'products' "
                    + "and index_name = 'idx_products_brand_price_id' order by seq_in_index",
                String.class
            );

            // assert: price asc, id desc 정렬을 인덱스만으로 얻으려면 id 가 내림차순이어야 한다.
            assertThat(columns).containsExactly("brand_id:A", "deleted_at:A", "price:A", "id:D");
        }
    }

    @DisplayName("id 목록으로 상품을 한 번에 조회할 때,")
    @Nested
    class FindAllActiveByIds {
        @DisplayName("삭제되지 않은 상품만 조회되고, 삭제됐거나 없는 id 는 결과에서 빠진다.")
        @Test
        void returnsOnlyActiveProducts() {
            // arrange
            Product first = saveProduct(1L, "러닝화", 10);
            Product second = saveProduct(1L, "샌들", 10);
            Product deleted = saveDeletedProduct(1L, "삭제됨");
            saveProduct(1L, "요청하지 않은 상품", 10);

            // act
            List<Product> found = productRepository.findAllActiveByIds(
                List.of(first.getId(), second.getId(), deleted.getId(), 999_999L)
            );

            // assert
            assertThat(found).extracting(Product::getId).containsExactlyInAnyOrder(first.getId(), second.getId());
        }

        @DisplayName("id 목록이 비어 있으면 빈 결과를 돌려준다.")
        @Test
        void returnsEmpty_whenIdsAreEmpty() {
            saveProduct(1L, "러닝화", 10);

            assertThat(productRepository.findAllActiveByIds(List.of())).isEmpty();
        }
    }
}
