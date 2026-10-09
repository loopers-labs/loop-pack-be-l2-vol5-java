package com.loopers.infrastructure.persistence.shopping.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.domain.shopping.model.User;
import com.loopers.domain.shopping.repository.UserRepository;
import com.loopers.fixtures.UserFixture;
import com.loopers.infrastructure.persistence.shopping.entity.UserJpaEntity;
import com.loopers.support.test.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
class UserRepositoryIntegrationTest {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @DisplayName("할당한 사용자 ID를 저장하고 영속성 컨텍스트를 비운 뒤 저장 상태를 확인한다")
    @Test
    @Transactional
    void savesAssignedId_andPreservesStoredStateAfterClear() {
        // arrange / act
        User saved = userRepository.save(UserFixture.firstUser());
        entityManager.flush();
        entityManager.clear();

        // assert
        assertThat(saved.getId()).isEqualTo(1L);
        assertThat(entityManager.find(UserJpaEntity.class, 1L).getId()).isEqualTo(1L);
        assertThat(entityManager.find(UserJpaEntity.class, 2L)).isNull();
    }
}
