package com.loopers.domain.catalog;

import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;

/** AG-02 브랜드. INV-14, ST-01. */
class BrandModelTest {

    @DisplayName("[INV-14] 이름 1~100자면 생성된다. 생성 직후 상태는 ACTIVE.")
    @Test
    void create_succeeds_withValidName() {
        BrandModel brand = new BrandModel("a".repeat(100));

        assertThat(brand.getName()).hasSize(100);
        assertThat(brand.isDeleted()).isFalse();
    }

    @DisplayName("[INV-14][ER-17 INVALID_BRAND] 이름 누락·빈 값·공백은 거부된다.")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void create_throwsInvalidBrand_whenNameBlank(String name) {
        assertThrowsErrorType(() -> new BrandModel(name), ErrorType.INVALID_BRAND);
    }

    @DisplayName("[INV-14][ER-17 INVALID_BRAND] 이름 101자는 거부된다.")
    @Test
    void create_throwsInvalidBrand_whenNameTooLong() {
        assertThrowsErrorType(() -> new BrandModel("a".repeat(101)), ErrorType.INVALID_BRAND);
    }

    @DisplayName("[INV-14] 수정 시에도 같은 규칙. 실패하면 기존 값 유지.")
    @Test
    void update_keepsOldName_whenInvalid() {
        BrandModel brand = new BrandModel("원래 이름");

        assertThrowsErrorType(() -> brand.update(""), ErrorType.INVALID_BRAND);

        assertThat(brand.getName()).isEqualTo("원래 이름");
    }

    @DisplayName("[ST-01] delete() 로 ACTIVE → DELETED.")
    @Test
    void delete_marksDeleted() {
        BrandModel brand = new BrandModel("브랜드");

        brand.delete();

        assertThat(brand.isDeleted()).isTrue();
        assertThat(brand.getDeletedAt()).isNotNull();
    }
}
