package com.loopers.domain.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.fixture.BrandFixture;
import com.loopers.fixture.ProductFixture;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("ProductService 는 관리자 명령으로 상품을 등록·수정·삭제하고 활성 상품을 목록으로 제공한다.")
@SpringBootTest
class AdminProductCommandIntegrationTest {

    @Autowired
    private ProductService productService;
    @Autowired
    private BrandFixture brandFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private ProductJpaRepository productJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("등록")
    @Nested
    class Create {
        @DisplayName("재고 0 인 상품을 브랜드에 연결해 저장한다.")
        @Test
        void savesProductWithZeroStock() {
            BrandModel nike = brandFixture.createBrand("나이키");

            ProductModel created = productService.create(nike.getId(), "  운동화  ", 89_000L);

            ProductModel saved = productJpaRepository.findById(created.getId()).orElseThrow();
            assertAll(
                () -> assertThat(saved.getBrandId()).isEqualTo(nike.getId()),
                () -> assertThat(saved.getName()).isEqualTo("운동화"),
                () -> assertThat(saved.getPrice().toWon()).isEqualTo(89_000L),
                () -> assertThat(saved.getStockQuantity()).isZero()
            );
        }

        @DisplayName("존재하지 않는 브랜드면 BRAND_NOT_FOUND 로 거절하고 상품을 만들지 않는다.")
        @Test
        void rejectsUnknownBrand() {
            assertThatThrownBy(() -> productService.create(999999L, "운동화", 10_000L))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.BRAND_NOT_FOUND);
            assertThat(productJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("삭제된 브랜드면 BRAND_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsDeletedBrand() {
            BrandModel deleted = brandFixture.createDeletedBrand("사라진브랜드");

            assertThatThrownBy(() -> productService.create(deleted.getId(), "운동화", 10_000L))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.BRAND_NOT_FOUND);
            assertThat(productJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("[잠정] 가격이 0 이면 INVALID_PRODUCT_PRICE 로 거절하고 저장하지 않는다.")
        @Test
        void rejectsZeroPrice() {
            BrandModel nike = brandFixture.createBrand("나이키");

            assertThatThrownBy(() -> productService.create(nike.getId(), "운동화", 0L))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.INVALID_PRODUCT_PRICE);
            assertThat(productJpaRepository.findAll()).isEmpty();
        }
    }

    @DisplayName("수정")
    @Nested
    class Update {
        @DisplayName("이름과 가격을 바꾸고 브랜드 관계는 유지한다.")
        @Test
        void changesNameAndPrice() {
            BrandModel nike = brandFixture.createBrand("나이키");
            ProductModel shoes = productFixture.createProduct(nike.getId(), "운동화", 89_000L, 5L);

            productService.update(shoes.getId(), "러닝화", 99_000L);

            ProductModel saved = productJpaRepository.findById(shoes.getId()).orElseThrow();
            assertAll(
                () -> assertThat(saved.getName()).isEqualTo("러닝화"),
                () -> assertThat(saved.getPrice().toWon()).isEqualTo(99_000L),
                () -> assertThat(saved.getBrandId()).isEqualTo(nike.getId()),
                () -> assertThat(saved.getStockQuantity()).isEqualTo(5L)
            );
        }

        @DisplayName("잘못된 이름은 INVALID_PRODUCT_NAME 으로 거절하고 기존 값을 유지한다.")
        @Test
        void rejectsInvalidName() {
            ProductModel shoes = productFixture.createProduct("운동화", 89_000L, 5L);

            assertThatThrownBy(() -> productService.update(shoes.getId(), " ", 99_000L))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.INVALID_PRODUCT_NAME);

            ProductModel saved = productJpaRepository.findById(shoes.getId()).orElseThrow();
            assertAll(
                () -> assertThat(saved.getName()).isEqualTo("운동화"),
                () -> assertThat(saved.getPrice().toWon()).isEqualTo(89_000L)
            );
        }

        @DisplayName("삭제된 상품은 PRODUCT_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsDeletedProduct() {
            ProductModel deleted = productFixture.createDeletedProduct("단종 운동화", 10_000L, 3L);

            assertThatThrownBy(() -> productService.update(deleted.getId(), "새이름", 20_000L))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.PRODUCT_NOT_FOUND);
        }
    }

    @DisplayName("삭제")
    @Nested
    class Delete {
        @DisplayName("삭제 시각을 기록한다.")
        @Test
        void softDeletesProduct() {
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 3L);

            productService.delete(shoes.getId());

            assertThat(productJpaRepository.findById(shoes.getId()).orElseThrow().getDeletedAt()).isNotNull();
        }

        @DisplayName("이미 삭제된 상품은 PRODUCT_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsDeletedProduct() {
            ProductModel deleted = productFixture.createDeletedProduct("단종 운동화", 10_000L, 3L);

            assertThatThrownBy(() -> productService.delete(deleted.getId()))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.PRODUCT_NOT_FOUND);
        }
    }

    @DisplayName("관리자 목록 조회")
    @Nested
    class GetAllProducts {
        @DisplayName("[잠정] 삭제된 상품을 제외하고 latest 는 최신 등록순으로 반환한다.")
        @Test
        void returnsActiveProductsLatestFirst() {
            productFixture.createProduct("첫째", 1_000L, 1L);
            productFixture.createProduct("둘째", 2_000L, 2L);
            productFixture.createDeletedProduct("삭제됨", 3_000L, 3L);
            productFixture.createProduct("셋째", 3_000L, 3L);

            PageResult<ProductQueryResult> result =
                productService.getAllProducts(PageCommand.of(null, null), ListSort.LATEST);

            assertAll(
                () -> assertThat(result.totalElements()).isEqualTo(3L),
                () -> assertThat(result.items()).extracting(ProductQueryResult::name)
                    .containsExactly("셋째", "둘째", "첫째")
            );
        }

        @DisplayName("oldest 는 등록 순서대로 페이지를 끊어 반환한다.")
        @Test
        void returnsOldestPage() {
            productFixture.createProduct("첫째", 1_000L, 1L);
            productFixture.createProduct("둘째", 2_000L, 2L);
            productFixture.createProduct("셋째", 3_000L, 3L);

            PageResult<ProductQueryResult> result =
                productService.getAllProducts(PageCommand.of(0, 2), ListSort.OLDEST);

            assertAll(
                () -> assertThat(result.totalElements()).isEqualTo(3L),
                () -> assertThat(result.totalPages()).isEqualTo(2),
                () -> assertThat(result.items()).extracting(ProductQueryResult::name)
                    .containsExactly("첫째", "둘째")
            );
        }

        @DisplayName("브랜드명과 현재 재고 수량을 함께 반환한다.")
        @Test
        void includesBrandNameAndStock() {
            BrandModel nike = brandFixture.createBrand("나이키");
            productFixture.createProduct(nike.getId(), "운동화", 89_000L, 7L);

            PageResult<ProductQueryResult> result =
                productService.getAllProducts(PageCommand.of(null, null), ListSort.LATEST);

            ProductQueryResult item = result.items().get(0);
            assertAll(
                () -> assertThat(item.brandName()).isEqualTo("나이키"),
                () -> assertThat(item.stockQuantity()).isEqualTo(7L)
            );
        }
    }
}
