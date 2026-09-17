package com.loopers.domain.brand;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrandTest {
    @Test
    @DisplayName("이름으로 생성한 브랜드는 저장 전 ID가 없고 삭제되지 않은 상태다")
    void createsBrand() {
        Brand brand = Brand.create("브랜드");

        assertThat(brand.getName()).isEqualTo("브랜드");
        assertThat(brand.getId()).isNull();
        assertThat(brand.isDeleted()).isFalse();
    }

    @ParameterizedTest(name = "이름=[{0}]")
    @ValueSource(strings = {"", " ", "\t\n", "　"})
    @DisplayName("이름이 비어 있거나 공백뿐이면 브랜드 생성을 거절한다")
    void rejectsBlankName(String name) {
        assertThatThrownBy(() -> Brand.create(name))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("100자 이름으로 브랜드를 생성할 수 있다")
    void acceptsMaximumNameLength() {
        String name = "가".repeat(100);
        assertThat(Brand.create(name).getName()).isEqualTo(name);
    }

    @Test
    @DisplayName("101자 이름이면 브랜드 생성을 거절한다")
    void rejectsTooLongName() {
        assertThatThrownBy(() -> Brand.create("가".repeat(101)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이름이 null이면 브랜드 생성을 거절한다")
    void rejectsNullName() {
        assertThatThrownBy(() -> Brand.create(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
