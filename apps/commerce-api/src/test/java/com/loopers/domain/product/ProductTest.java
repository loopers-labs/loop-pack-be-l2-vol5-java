package com.loopers.domain.product;

import com.loopers.domain.brand.Brand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class ProductTest {

    @Test
    void createsProductWithTrimmedNameAndRetainsItsBrand() {
        Brand brand = new Brand("브랜드");
        Product product = new Product(brand, " \t 상품  이름 \n", Long.MAX_VALUE, Integer.MAX_VALUE);

        assertAll(
            () -> assertThat(product.getBrand()).isSameAs(brand),
            () -> assertThat(product.getName()).isEqualTo("상품  이름"),
            () -> assertThat(product.getPrice()).isEqualTo(Long.MAX_VALUE),
            () -> assertThat(product.getStockQuantity()).isEqualTo(Integer.MAX_VALUE),
            () -> assertThat(product.isDeleted()).isFalse()
        );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsEmptyNames(String name) {
        assertThatThrownBy(() -> new Product(new Brand("브랜드"), name, 1, 0))
            .isInstanceOfSatisfying(ProductException.class,
                error -> assertThat(error.getReason()).isEqualTo(ProductException.Reason.INVALID_NAME));
    }

    @ParameterizedTest
    @ValueSource(strings = {"한", "😀"})
    void countsNameLengthInUnicodeCodePoints(String character) {
        Product product = new Product(new Brand("브랜드"), " " + character.repeat(100) + " ", 1, 0);
        assertThat(product.getName()).isEqualTo(character.repeat(100));
        assertThatThrownBy(() -> product.update(character.repeat(101), 2))
            .isInstanceOf(ProductException.class);
        assertThat(product.getName()).isEqualTo(character.repeat(100));
        assertThat(product.getPrice()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsInvalidPriceAndPreservesAllUpdatedFields(long price) {
        Product product = new Product(new Brand("브랜드"), "원래 이름", 1000, 5);
        assertThatThrownBy(() -> product.update("새 이름", price))
            .isInstanceOfSatisfying(ProductException.class,
                error -> assertThat(error.getReason()).isEqualTo(ProductException.Reason.INVALID_PRICE));
        assertThat(product.getName()).isEqualTo("원래 이름");
        assertThat(product.getPrice()).isEqualTo(1000);
        assertThatThrownBy(() -> new Product(new Brand("브랜드"), "상품", price, 0))
            .isInstanceOf(ProductException.class);
    }

    @Test
    void updatesOnlyNameAndPriceAndUsesTheSameStockForSetAndDeduct() {
        Brand brand = new Brand("브랜드");
        Product product = new Product(brand, "원래 이름", 1000, 10);
        product.update(" 새 이름 ", 2000);
        assertThat(product.getBrand()).isSameAs(brand);
        assertThat(product.getStockQuantity()).isEqualTo(10);
        product.changeStockQuantityTo(3);
        product.deductStock(2);
        assertThat(product.getStockQuantity()).isEqualTo(1);
        assertThatThrownBy(() -> product.deductStock(2)).isInstanceOf(ProductStockException.class);
        assertThat(product.getStockQuantity()).isEqualTo(1);
        assertThatThrownBy(() -> product.changeStockQuantityTo(-1)).isInstanceOf(ProductStockException.class);
        assertThat(product.getStockQuantity()).isEqualTo(1);
    }

    @Test
    void repeatedDeletionPreservesTheOriginalTimestampAndPreventsAllChanges() {
        Product product = new Product(new Brand("브랜드"), "상품", 1000, 5);
        ZonedDateTime deletedAt = ZonedDateTime.now();
        product.delete(deletedAt);
        product.delete(deletedAt.plusDays(1));

        assertAll(
            () -> assertThat(product.getDeletedAt()).isEqualTo(deletedAt),
            () -> assertThatThrownBy(() -> product.update("변경", 2000)).isInstanceOf(ProductException.class),
            () -> assertThatThrownBy(() -> product.changeStockQuantityTo(0)).isInstanceOf(ProductException.class),
            () -> assertThatThrownBy(() -> product.deductStock(2))
                .isInstanceOfSatisfying(ProductException.class,
                    error -> assertThat(error.getReason()).isEqualTo(ProductException.Reason.DELETED_PRODUCT)),
            () -> assertThat(product.getName()).isEqualTo("상품"),
            () -> assertThat(product.getPrice()).isEqualTo(1000),
            () -> assertThat(product.getStockQuantity()).isEqualTo(5)
        );
    }

    @Test
    void rejectsNegativeInitialStockAndNullDeletionTime() {
        assertThatThrownBy(() -> new Product(new Brand("브랜드"), "상품", 1, -1))
            .isInstanceOf(ProductStockException.class);
        Product product = new Product(new Brand("브랜드"), "상품", 1, 0);
        assertThatThrownBy(() -> product.delete(null)).isInstanceOf(NullPointerException.class);
        assertThat(product.isDeleted()).isFalse();
    }

    @Test
    void validatesStockDeductionWithoutMutatingStockForAtomicOrderValidation() {
        Product product = new Product(new Brand("브랜드"), "상품", 1, 5);
        product.validateStockDeduction(5);
        assertThat(product.getStockQuantity()).isEqualTo(5);
        assertThatThrownBy(() -> product.validateStockDeduction(0))
            .isInstanceOfSatisfying(ProductStockException.class,
                error -> assertThat(error.getReason()).isEqualTo(ProductStockException.Reason.INVALID_DEDUCTION_QUANTITY));
        assertThatThrownBy(() -> product.validateStockDeduction(6))
            .isInstanceOfSatisfying(ProductStockException.class,
                error -> assertThat(error.getReason()).isEqualTo(ProductStockException.Reason.INSUFFICIENT_STOCK));
        product.delete(ZonedDateTime.now());
        assertThatThrownBy(() -> product.validateStockDeduction(2))
            .isInstanceOfSatisfying(ProductException.class,
                error -> assertThat(error.getReason()).isEqualTo(ProductException.Reason.DELETED_PRODUCT));
        assertThat(product.getStockQuantity()).isEqualTo(5);
    }
}
