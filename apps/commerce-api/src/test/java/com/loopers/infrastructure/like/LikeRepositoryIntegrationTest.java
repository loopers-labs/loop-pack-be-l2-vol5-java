package com.loopers.infrastructure.like;

import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class LikeRepositoryIntegrationTest {

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("같은 사용자–상품 관계를 두 번 저장하면, 유니크 키가 막는다. (LIK-02)")
    @Test
    void rejectsDuplicateRelation() {
        // arrange
        likeRepository.save(new Like(1L, 10L));

        // act & assert
        assertThatThrownBy(() -> likeRepository.save(new Like(1L, 10L)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("좋아요 수는 그 상품의 관계만 센다.")
    @Test
    void countsRelationsOfProduct() {
        // arrange
        likeRepository.save(new Like(1L, 10L));
        likeRepository.save(new Like(2L, 10L));
        likeRepository.save(new Like(1L, 20L));

        // act & assert
        assertAll(
            () -> assertThat(likeRepository.countByProductId(10L)).isEqualTo(2),
            () -> assertThat(likeRepository.countByProductId(20L)).isEqualTo(1),
            () -> assertThat(likeRepository.find(2L, 10L)).isPresent()
        );
    }
}
