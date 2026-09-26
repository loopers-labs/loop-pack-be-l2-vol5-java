package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrandDeletionPolicyTest {

    private final BrandDeletionPolicy policy = new BrandDeletionPolicy();

    @Test
    void deletesBrandWhenNoActiveProductExists() {
        Brand brand = Brand.create("Nike");

        policy.delete(brand, false);

        assertThat(brand.getDeletedAt()).isNotNull();
    }

    @Test
    void keepsBrandWhenActiveProductExists() {
        Brand brand = Brand.create("Nike");

        CoreException exception = assertThrows(CoreException.class, () -> policy.delete(brand, true));

        assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        assertThat(brand.getDeletedAt()).isNull();
    }

    @Test
    void rejectsAlreadyDeletedBrand() {
        Brand brand = Brand.create("Nike");
        brand.delete();

        CoreException exception = assertThrows(CoreException.class, () -> policy.delete(brand, false));

        assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
    }
}
