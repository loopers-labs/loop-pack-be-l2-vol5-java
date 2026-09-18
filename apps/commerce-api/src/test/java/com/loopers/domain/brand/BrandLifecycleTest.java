package com.loopers.domain.brand;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class BrandLifecycleTest {

    private static final ZonedDateTime DELETED_AT = ZonedDateTime.parse("2026-09-18T12:00:00+09:00");

    @DisplayName("BRAND-LIFECYCLE-01: 새 브랜드는 삭제되지 않은 상태다.")
    @Test
    void createsActiveBrand() {
        Brand brand = new Brand("Original Brand");

        assertAll(
            () -> assertThat(brand.isDeleted()).isFalse(),
            () -> assertThat(brand.getDeletedAt()).isNull()
        );
    }

    @DisplayName("BRAND-LIFECYCLE-02: 삭제 시각을 기록하고 이름을 보존한다.")
    @Test
    void recordsDeletionTimeWithoutChangingName() {
        Brand brand = new Brand("Original Brand");

        brand.delete(DELETED_AT);

        assertAll(
            () -> assertThat(brand.isDeleted()).isTrue(),
            () -> assertThat(brand.getDeletedAt()).isEqualTo(DELETED_AT),
            () -> assertThat(brand.getName()).isEqualTo("Original Brand")
        );
    }

    @DisplayName("BRAND-LIFECYCLE-03: 재삭제해도 최초 삭제 시각과 이름을 유지한다.")
    @Test
    void keepsFirstDeletionTimeWhenDeletedAgain() {
        Brand brand = new Brand("Original Brand");
        brand.delete(DELETED_AT);

        brand.delete(DELETED_AT.plusDays(1));

        assertAll(
            () -> assertThat(brand.isDeleted()).isTrue(),
            () -> assertThat(brand.getDeletedAt()).isEqualTo(DELETED_AT),
            () -> assertThat(brand.getName()).isEqualTo("Original Brand")
        );
    }

    @DisplayName("BRAND-LIFECYCLE-04: 삭제된 브랜드의 이름 변경은 거절하고 상태를 유지한다.")
    @ParameterizedTest
    @ValueSource(strings = {"New Brand", "Original Brand"})
    void rejectsRenamingDeletedBrand(String newName) {
        Brand brand = new Brand("Original Brand");
        brand.delete(DELETED_AT);

        assertAll(
            () -> assertThatThrownBy(() -> brand.rename(newName))
                .isInstanceOfSatisfying(BrandStateException.class,
                    error -> assertThat(error.getReason()).isEqualTo(BrandStateException.Reason.DELETED_BRAND)),
            () -> assertThat(brand.getName()).isEqualTo("Original Brand"),
            () -> assertThat(brand.isDeleted()).isTrue(),
            () -> assertThat(brand.getDeletedAt()).isEqualTo(DELETED_AT)
        );
    }

    @DisplayName("BRAND-LIFECYCLE-05: 삭제 전에 변경한 이름은 삭제 후에도 유지한다.")
    @Test
    void preservesLatestNameWhenDeleted() {
        Brand brand = new Brand("Original Brand");
        brand.rename(" Renamed Brand ");

        brand.delete(DELETED_AT);

        assertAll(
            () -> assertThat(brand.getName()).isEqualTo("Renamed Brand"),
            () -> assertThat(brand.isDeleted()).isTrue(),
            () -> assertThat(brand.getDeletedAt()).isEqualTo(DELETED_AT)
        );
    }

    @DisplayName("BRAND-LIFECYCLE-06: 첫 삭제에 필요한 서버 시각이 누락되면 상태를 변경하지 않는다.")
    @Test
    void rejectsMissingDeletionTimeWithoutChangingState() {
        Brand brand = new Brand("Original Brand");

        assertAll(
            () -> assertThatThrownBy(() -> brand.delete(null)).isInstanceOf(NullPointerException.class),
            () -> assertThat(brand.isDeleted()).isFalse(),
            () -> assertThat(brand.getDeletedAt()).isNull(),
            () -> assertThat(brand.getName()).isEqualTo("Original Brand")
        );
    }
}
