package com.loopers.application.brand;

import com.loopers.application.brand.dto.BrandInfo;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
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

@SpringBootTest
class AdminBrandFacadeTest {

    @Autowired
    private ExposedProducts exposedProducts;

    @Autowired
    private AdminBrandFacade adminBrandFacade;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

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

    // 삭제 상품은 집계 행이 없어야 하므로 저장소로 직접 저장한다.
    private Product saveProduct(Long brandId, int stock, boolean deleted) {
        Product product = Product.create(brandId, "러닝화", new Money(89_000), new Stock(stock));
        if (deleted) {
            product.delete();
            return productRepository.save(product);
        }
        return exposedProducts.save(product);
    }

    private long likeCountRows(Long productId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from product_like_counts where product_id = ?", Long.class, productId);
    }

    private String storedName(Long brandId) {
        return brandRepository.findActive(brandId).orElseThrow().getName();
    }

    private long storedCount() {
        return brandRepository.findAllActive(PageRequest.of(0, 10)).getTotalElements();
    }

    @DisplayName("브랜드를 등록할 때,")
    @Nested
    class Create {
        @DisplayName("공백을 제거한 이름으로 저장하고, 저장된 브랜드 정보를 반환한다.")
        @Test
        void returnsSavedInfo_andPersists() {
            // act
            BrandInfo info = adminBrandFacade.create("  루퍼스  ");

            // assert
            assertThat(info.name()).isEqualTo("루퍼스");
            assertThat(info.createdAt()).isNotNull();
            assertThat(info.updatedAt()).isNotNull();
            assertThat(storedName(info.id())).isEqualTo("루퍼스");
        }
    }

    @DisplayName("브랜드를 수정할 때,")
    @Nested
    class Update {
        @DisplayName("이름을 바꾸고, 바뀐 이름이 저장된다.")
        @Test
        void changesName_andPersists() {
            // arrange
            Brand brand = saveBrand("루퍼스");

            // act
            BrandInfo info = adminBrandFacade.update(brand.getId(), "  새이름 ");

            // assert
            assertThat(info.name()).isEqualTo("새이름");
            assertThat(storedName(brand.getId())).isEqualTo("새이름");
        }
    }

    @DisplayName("브랜드를 삭제할 때,")
    @Nested
    class Delete {
        @DisplayName("삭제 후에는 조회되지 않는다.")
        @Test
        void hidesBrand_afterDelete() {
            // arrange
            Brand brand = saveBrand("루퍼스");

            // act
            adminBrandFacade.delete(brand.getId());

            // assert
            assertThat(brandRepository.findActive(brand.getId())).isEmpty();
        }

        @DisplayName("삭제되지 않은 상품이 연결되어 있으면 재고가 0이어도 함께 삭제한다.")
        @Test
        void deletesLinkedProducts_includingNoStock() {
            // arrange
            Brand brand = saveBrand("루퍼스");
            Product inStock = saveProduct(brand.getId(), 10, false);
            Product soldOut = saveProduct(brand.getId(), 0, false);

            // act
            adminBrandFacade.delete(brand.getId());

            // assert
            assertThat(brandRepository.findActive(brand.getId())).isEmpty();
            assertThat(productRepository.findActive(inStock.getId())).isEmpty();
            assertThat(productRepository.findActive(soldOut.getId())).isEmpty();
        }

        @DisplayName("연결된 상품의 좋아요 수 집계도 함께 지우고, 다른 브랜드 상품의 집계는 남긴다.")
        @Test
        void removesLikeCountRowsOfLinkedProducts() {
            // arrange
            Brand brand = saveBrand("루퍼스");
            Brand other = saveBrand("다른 브랜드");
            Product linked = saveProduct(brand.getId(), 10, false);
            Product otherProduct = saveProduct(other.getId(), 10, false);

            // act
            adminBrandFacade.delete(brand.getId());

            // assert
            assertThat(likeCountRows(linked.getId())).isZero();
            assertThat(likeCountRows(otherProduct.getId())).isEqualTo(1);
        }

        @DisplayName("연결된 상품을 모두 삭제하면 브랜드를 삭제할 수 있다.")
        @Test
        void deletesBrand_whenAllProductsAreDeleted() {
            // arrange
            Brand brand = saveBrand("루퍼스");
            saveProduct(brand.getId(), 10, true);

            // act
            adminBrandFacade.delete(brand.getId());

            // assert
            assertThat(brandRepository.findActive(brand.getId())).isEmpty();
        }

        @DisplayName("다른 브랜드의 상품은 삭제하지 않는다.")
        @Test
        void keepsOtherBrandProducts() {
            // arrange
            Brand brand = saveBrand("루퍼스");
            Brand other = saveBrand("다른 브랜드");
            Product otherProduct = saveProduct(other.getId(), 10, false);

            // act
            adminBrandFacade.delete(brand.getId());

            // assert
            assertThat(brandRepository.findActive(brand.getId())).isEmpty();
            assertThat(brandRepository.findActive(other.getId())).isPresent();
            assertThat(productRepository.findActive(otherProduct.getId())).isPresent();
        }
    }

    @DisplayName("브랜드 상세를 조회할 때,")
    @Nested
    class Get {
        @DisplayName("이름과 생성·수정 시각을 반환한다.")
        @Test
        void returnsInfo() {
            // arrange
            Brand brand = saveBrand("루퍼스");

            // act
            BrandInfo info = adminBrandFacade.get(brand.getId());

            // assert
            assertThat(info.id()).isEqualTo(brand.getId());
            assertThat(info.name()).isEqualTo("루퍼스");
            assertThat(info.createdAt()).isNotNull();
            assertThat(info.updatedAt()).isNotNull();
        }
    }

    @DisplayName("브랜드 목록을 조회할 때,")
    @Nested
    class GetList {
        @DisplayName("삭제되지 않은 브랜드를 최신순으로 반환한다.")
        @Test
        void returnsActiveBrands_newestFirst() {
            // arrange
            Brand first = saveBrand("첫째");
            saveDeletedBrand("삭제됨");
            Brand third = saveBrand("셋째");

            // act
            Page<BrandInfo> page = adminBrandFacade.getList(0, 20);

            // assert
            assertThat(page.getContent()).extracting(BrandInfo::id)
                .containsExactly(third.getId(), first.getId());
            assertThat(page.getTotalElements()).isEqualTo(2);
        }
    }
}
