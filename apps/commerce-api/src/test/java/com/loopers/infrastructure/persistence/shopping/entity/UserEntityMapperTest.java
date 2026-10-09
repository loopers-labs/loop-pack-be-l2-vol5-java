package com.loopers.infrastructure.persistence.shopping.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.domain.shopping.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserEntityMapperTest {
    private final UserEntityMapper mapper = new UserEntityMapper();

    @DisplayName("도메인과 Entity를 변환해도 사용자 ID를 보존한다")
    @Test
    void preservesId_whenMappingRoundTrip() {
        User user = User.create(1L);

        UserJpaEntity entity = mapper.toEntity(user);
        User restored = mapper.toDomain(entity);

        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(restored.getId()).isEqualTo(1L);
    }
}
