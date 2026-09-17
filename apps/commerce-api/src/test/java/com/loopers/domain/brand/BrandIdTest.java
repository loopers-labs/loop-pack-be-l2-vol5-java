package com.loopers.domain.brand;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BrandIdTest {
    @Test
    @DisplayName("동일한 식별자 값은 동등하고 해시값도 같다")
    void comparesIdsByValue() {
        BrandId id = new BrandId(1L);

        assertThat(id.value()).isEqualTo(1L);
        assertThat(id).isEqualTo(new BrandId(1L));
        assertThat(id.hashCode()).isEqualTo(new BrandId(1L).hashCode());
        assertThat(id).isNotEqualTo(new BrandId(2L));
    }
}
