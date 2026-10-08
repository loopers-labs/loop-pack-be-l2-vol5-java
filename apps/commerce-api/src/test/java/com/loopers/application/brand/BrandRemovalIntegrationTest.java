package com.loopers.application.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.application.order.fixture.OrderFixture;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.infrastructure.user.fixture.UserFixture;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

@SpringBootTest
class BrandRemovalIntegrationTest {
    @Autowired private DeleteBrandFacade deleteBrand;
    @Autowired private BrandFixture brandFixture;
    @Autowired private ProductFixture productFixture;
    @Autowired private OrderFixture orderFixture;
    @Autowired private UserFixture userFixture;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoSpyBean private BrandRepository brands;

    @Test
    void 브랜드와_모든_연결_상품을_삭제하고_다른_대상과_과거_주문은_보존한다() {
        // arrange
        Brand target = brandFixture.createBrand("삭제할 브랜드");
        Product inStock = productFixture.createProduct(target.getId(), "재고 있음", 3_000, 5);
        Product soldOut = productFixture.createProduct(target.getId(), "재고 없음", 1_000, 0);
        Brand other = brandFixture.createBrand("다른 브랜드");
        Product otherInStock = productFixture.createProduct(other.getId(), "다른 상품", 4_000, 7);
        Order history = createConfirmedOrderHistory(inStock, soldOut);

        // act
        deleteBrand.delete(target.getId());

        // assert
        assertThat(brandFixture.brand(target.getId()).isDeleted()).isTrue();
        assertThat(productFixture.product(inStock.getId()).isDeleted()).isTrue();
        assertThat(productFixture.product(soldOut.getId()).isDeleted()).isTrue();
        assertThat(brandFixture.brand(other.getId())).usingRecursiveComparison().isEqualTo(other);
        assertThat(productFixture.product(otherInStock.getId()))
                .usingRecursiveComparison()
                .isEqualTo(otherInStock);
        assertThat(orderFixture.order(history.getId()))
                .usingRecursiveComparison()
                .isEqualTo(history);
    }

    @Test
    void 상품을_삭제한_뒤_브랜드_저장에_실패하면_모든_대상과_과거_주문을_보존한다() {
        // arrange
        Brand target = brandFixture.createBrand("삭제할 브랜드");
        Product inStock = productFixture.createProduct(target.getId(), "재고 있음", 3_000, 5);
        Product soldOut = productFixture.createProduct(target.getId(), "재고 없음", 1_000, 0);
        Brand other = brandFixture.createBrand("다른 브랜드");
        Product otherInStock = productFixture.createProduct(other.getId(), "다른 상품", 4_000, 7);
        Order history = createConfirmedOrderHistory(inStock, soldOut);
        DataAccessException failure = new DataAccessResourceFailureException("브랜드 저장 실패");
        doThrow(failure).when(brands).save(any(Brand.class));

        // act
        DataAccessException error =
                assertThrows(DataAccessException.class, () -> deleteBrand.delete(target.getId()));

        // assert
        assertThat(error).isSameAs(failure);
        assertThat(brandFixture.brand(target.getId())).usingRecursiveComparison().isEqualTo(target);
        assertThat(productFixture.product(inStock.getId()))
                .usingRecursiveComparison()
                .isEqualTo(inStock);
        assertThat(productFixture.product(soldOut.getId()))
                .usingRecursiveComparison()
                .isEqualTo(soldOut);
        assertThat(brandFixture.brand(other.getId())).usingRecursiveComparison().isEqualTo(other);
        assertThat(productFixture.product(otherInStock.getId()))
                .usingRecursiveComparison()
                .isEqualTo(otherInStock);
        assertThat(orderFixture.order(history.getId()))
                .usingRecursiveComparison()
                .isEqualTo(history);
    }

    private Order createConfirmedOrderHistory(Product inStock, Product soldOut) {
        userFixture.createUser(1);
        return orderFixture.createConfirmedOrder(
                1,
                List.of(
                        new Order.RequestedItem(inStock.getId(), 2, 2_000),
                        new Order.RequestedItem(soldOut.getId(), 1, 1_000)));
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
