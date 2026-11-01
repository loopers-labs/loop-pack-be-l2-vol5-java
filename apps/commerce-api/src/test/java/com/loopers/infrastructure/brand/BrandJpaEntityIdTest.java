package com.loopers.infrastructure.brand;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BrandJpaEntityIdTest {

    @DisplayName("새 Brand 엔티티의 id는 저장 전까지 비어 있다.")
    @Test
    void generatedIdStartsUnset() {
        BrandJpaEntity entity = new BrandJpaEntity();

        assertThat(entity.getId()).isNull();
    }
}
