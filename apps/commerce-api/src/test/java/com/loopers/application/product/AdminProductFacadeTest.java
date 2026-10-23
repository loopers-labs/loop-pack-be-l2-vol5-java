package com.loopers.application.product;

import com.loopers.application.product.dto.ProductInfo;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.Stock;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.ExposedProducts;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class AdminProductFacadeTest {

    @Autowired
    private ExposedProducts exposedProducts;

    @Autowired
    private AdminProductFacade adminProductFacade;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private LikeService likeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Brand saveBrand(String name) {
        return brandRepository.save(Brand.create(name));
    }

    private Brand saveDeletedBrand(String name) {
        Brand brand = Brand.create(name);
        brand.delete();
        return brandRepository.save(brand);
    }

    private Product saveProduct(Long brandId, String name) {
        return exposedProducts.save(Product.create(brandId, name, new Money(89_000), new Stock(10)));
    }

    private Product saveDeletedProduct(Long brandId, String name) {
        Product product = Product.create(brandId, name, new Money(89_000), new Stock(10));
        product.delete();
        return productRepository.save(product);
    }

    private long likeCountRows(Long productId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from product_like_counts where product_id = ?", Long.class, productId);
    }

    private Product stored(Long productId) {
        return productRepository.findActive(productId).orElseThrow();
    }

    private long storedCount() {
        return productRepository.findAllActive(PageRequest.of(0, 10)).getTotalElements();
    }

    private ErrorType errorTypeOf(Runnable action) {
        return assertThrows(CoreException.class, action::run).getErrorType();
    }

    @DisplayName("상품을 등록할 때,")
    @Nested
    class Create {
        @DisplayName("공백을 제거한 이름으로 저장하고, 저장된 상품 정보를 반환한다.")
        @Test
        void returnsSavedInfo_andPersists() {
            // arrange
            Brand brand = saveBrand("루퍼스");

            // act
            ProductInfo info = adminProductFacade.create(brand.getId(), "  러닝화  ", 89_000, 10);

            // assert
            assertThat(info.brandId()).isEqualTo(brand.getId());
            assertThat(info.name()).isEqualTo("러닝화");
            assertThat(info.price()).isEqualTo(89_000);
            assertThat(info.stock()).isEqualTo(10);
            assertThat(info.createdAt()).isNotNull();
            assertThat(info.updatedAt()).isNotNull();
            assertThat(stored(info.id()).getName()).isEqualTo("러닝화");
            assertThat(stored(info.id()).getStock()).isEqualTo(new Stock(10));
        }

        @DisplayName("좋아요 수 집계 행을 0으로 함께 만들어, 브랜드 없는 좋아요순 목록에 나온다.")
        @Test
        void createsLikeCountRowWithZero() {
            // arrange
            Brand brand = saveBrand("루퍼스");

            // act
            ProductInfo info = adminProductFacade.create(brand.getId(), "러닝화", 89_000, 10);

            // assert
            assertThat(likeCountRows(info.id())).isEqualTo(1);
            assertThat(likeService.count(info.id())).isZero();
            assertThat(productRepository.findAllActive(null, ProductSort.LIKES_DESC, PageRequest.of(0, 10)).getContent())
                .extracting(Product::getId).containsExactly(info.id());
        }

        @DisplayName("없거나 삭제된 브랜드이면 NOT_FOUND 로 거절하고, 저장하지 않는다.")
        @Test
        void throwsNotFound_andSavesNothing_whenBrandDoesNotExistOrIsDeleted() {
            // arrange
            Brand deleted = saveDeletedBrand("삭제됨");

            // act & assert
            assertThat(errorTypeOf(() -> adminProductFacade.create(999L, "러닝화", 89_000, 10)))
                .isEqualTo(ErrorType.NOT_FOUND);
            assertThat(errorTypeOf(() -> adminProductFacade.create(deleted.getId(), "러닝화", 89_000, 10)))
                .isEqualTo(ErrorType.NOT_FOUND);
            assertThat(storedCount()).isZero();
        }
    }

    @DisplayName("상품을 수정할 때,")
    @Nested
    class Update {
        @DisplayName("이름과 가격을 바꾸고, 브랜드와 재고는 그대로 저장된다.")
        @Test
        void changesNameAndPrice_andPersists() {
            // arrange
            Product product = saveProduct(1L, "러닝화");

            // act
            ProductInfo info = adminProductFacade.update(product.getId(), "  새 러닝화 ", 99_000);

            // assert
            assertThat(info.name()).isEqualTo("새 러닝화");
            assertThat(info.price()).isEqualTo(99_000);
            Product found = stored(product.getId());
            assertThat(found.getName()).isEqualTo("새 러닝화");
            assertThat(found.getPrice()).isEqualTo(new Money(99_000));
            assertThat(found.getBrandId()).isEqualTo(1L);
            assertThat(found.getStock()).isEqualTo(new Stock(10));
        }
    }

    @DisplayName("재고를 변경할 때,")
    @Nested
    class ChangeStock {
        @DisplayName("최종 수량으로 바꾸고, 바뀐 재고가 저장된다. 0도 허용한다.")
        @Test
        void changesStock_andPersists() {
            // arrange
            Product product = saveProduct(1L, "러닝화");

            // act
            ProductInfo info = adminProductFacade.changeStock(product.getId(), 0);

            // assert
            assertThat(info.stock()).isZero();
            assertThat(stored(product.getId()).getStock()).isEqualTo(new Stock(0));
        }
    }

    @DisplayName("상품을 삭제할 때,")
    @Nested
    class Delete {
        @DisplayName("삭제 후에는 조회되지 않는다.")
        @Test
        void hidesProduct_afterDelete() {
            // arrange
            Product product = saveProduct(1L, "러닝화");

            // act
            adminProductFacade.delete(product.getId());

            // assert
            assertThat(productRepository.findActive(product.getId())).isEmpty();
        }

        @DisplayName("삭제하면 좋아요 수 집계에서도 빠진다. 다른 상품의 집계는 남는다.")
        @Test
        void removesLikeCountRow_afterDelete() {
            // arrange
            Product product = saveProduct(1L, "러닝화");
            Product other = saveProduct(1L, "다른 상품");
            assertThat(likeCountRows(product.getId())).isEqualTo(1);

            // act
            adminProductFacade.delete(product.getId());

            // assert
            assertThat(likeCountRows(product.getId())).isZero();
            assertThat(likeCountRows(other.getId())).isEqualTo(1);
        }

        @DisplayName("등록·좋아요·취소·삭제를 거친 뒤 집계 행 수는 노출 상품 수와 같고, 행 값은 좋아요 관계 수와 같다.")
        @Test
        void likeCountRowsMatchActiveProductsAndLikes() {
            // arrange
            Brand brand = saveBrand("루퍼스");
            Long first = adminProductFacade.create(brand.getId(), "하나", 1_000, 1).id();
            Long second = adminProductFacade.create(brand.getId(), "둘", 1_000, 1).id();
            Long third = adminProductFacade.create(brand.getId(), "셋", 1_000, 1).id();
            likeService.like(1L, first);
            likeService.like(2L, first);
            likeService.like(1L, second);
            likeService.unlike(1L, first);

            // act
            adminProductFacade.delete(third);

            // assert
            assertThat(jdbcTemplate.queryForObject("select count(*) from product_like_counts", Long.class))
                .isEqualTo(jdbcTemplate.queryForObject(
                    "select count(*) from products where deleted_at is null", Long.class));
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from product_like_counts c where c.like_count <> "
                    + "(select count(*) from likes l where l.product_id = c.product_id)", Long.class)).isZero();
        }
    }

    @DisplayName("상품 상세를 조회할 때,")
    @Nested
    class Get {
        @DisplayName("브랜드·이름·가격·재고와 생성·수정 시각을 반환한다.")
        @Test
        void returnsInfo() {
            // arrange
            Product product = saveProduct(3L, "러닝화");

            // act
            ProductInfo info = adminProductFacade.get(product.getId());

            // assert
            assertThat(info.id()).isEqualTo(product.getId());
            assertThat(info.brandId()).isEqualTo(3L);
            assertThat(info.name()).isEqualTo("러닝화");
            assertThat(info.price()).isEqualTo(89_000);
            assertThat(info.stock()).isEqualTo(10);
            assertThat(info.createdAt()).isNotNull();
            assertThat(info.updatedAt()).isNotNull();
        }
    }

    @DisplayName("상품 목록을 조회할 때,")
    @Nested
    class GetList {
        @DisplayName("삭제되지 않은 상품을 최신순으로 반환한다.")
        @Test
        void returnsActiveProducts_newestFirst() {
            // arrange
            Product first = saveProduct(1L, "첫째");
            saveDeletedProduct(1L, "삭제됨");
            Product third = saveProduct(2L, "셋째");

            // act
            Page<ProductInfo> page = adminProductFacade.getList(0, 20);

            // assert
            assertThat(page.getContent()).extracting(ProductInfo::id)
                .containsExactly(third.getId(), first.getId());
            assertThat(page.getTotalElements()).isEqualTo(2);
        }
    }
}
