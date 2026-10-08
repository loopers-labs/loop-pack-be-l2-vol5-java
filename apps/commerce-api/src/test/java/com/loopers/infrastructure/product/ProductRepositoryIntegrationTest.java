package com.loopers.infrastructure.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ProductRepositoryIntegrationTest {
    @Autowired private BrandFixture brands;
    @Autowired private ProductRepository products;
    @Autowired private ProductFixture fixture;
    @Autowired private DatabaseCleanUp cleanUp;

    @Test
    void 이전_재고를_가진_객체로_정보를_수정해도_차감_결과를_덮어쓰지_않는다() {
        // arrange
        Brand brand = brands.createBrand();
        Product product = fixture.createProduct(brand.getId(), "상품", 1_000, 5);
        Product previous = products.findById(product.getId()).orElseThrow();
        products.deductStock(product.getId(), 1);
        previous.update("변경 이름", 2_000);

        // act
        products.updateInformation(previous);

        // assert
        Product stored = products.findById(product.getId()).orElseThrow();
        assertThat(stored.getStock()).isEqualTo(4);
        assertThat(stored.getName()).isEqualTo("변경 이름");
        assertThat(stored.getPrice()).isEqualTo(2_000);
    }

    @Test
    void 상품을_삭제해도_최신_재고를_이전_값으로_덮어쓰지_않는다() {
        // arrange
        Brand brand = brands.createBrand();
        Product product = fixture.createProduct(brand.getId(), "상품", 1_000, 5);
        products.deductStock(product.getId(), 1);

        // act
        products.delete(product.getId());

        // assert
        Product stored = products.findById(product.getId()).orElseThrow();
        assertThat(stored.isDeleted()).isTrue();
        assertThat(stored.getStock()).isEqualTo(4);
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
