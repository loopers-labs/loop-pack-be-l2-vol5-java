package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrandDeletionPolicyTest {

    private static final ZonedDateTime DELETED_AT = ZonedDateTime.parse("2026-01-01T00:00:00Z");

    private final BrandDeletionPolicy policy = new BrandDeletionPolicy();

    @Test
    void deletesBrandWithoutLoadingItsProducts() {
        Brand brand = Brand.create("Nike");

        policy.delete(brand, DELETED_AT);

        assertThat(brand.getDeletedAt()).isEqualTo(DELETED_AT);
    }

    @Test
    void rejectsAlreadyDeletedBrand() {
        Brand brand = Brand.create("Nike");
        brand.delete(DELETED_AT);

        CoreException exception = assertThrows(CoreException.class, () -> policy.delete(brand, DELETED_AT.plusMinutes(1)));

        assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
    }
}
