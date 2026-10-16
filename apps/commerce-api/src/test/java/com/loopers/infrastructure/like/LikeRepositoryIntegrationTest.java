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
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class LikeRepositoryIntegrationTest {

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

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

    @DisplayName("관계를 지우면, 그 사용자–상품 관계만 지우고 지운 행 수를 돌려준다. 이미 없으면 0 이다. (4주차 설계 3.2)")
    @Test
    void deletesOnlyThatRelation_andReturnsDeletedRows() {
        // arrange
        likeRepository.save(new Like(1L, 10L));
        likeRepository.save(new Like(2L, 10L));
        likeRepository.save(new Like(1L, 20L));

        // act
        Integer first = transactionTemplate.execute(status -> likeRepository.delete(1L, 10L));
        Integer second = transactionTemplate.execute(status -> likeRepository.delete(1L, 10L));

        // assert
        assertAll(
            () -> assertThat(first).isEqualTo(1),
            () -> assertThat(second).isZero(),
            () -> assertThat(likeRepository.find(1L, 10L)).isEmpty(),
            () -> assertThat(likeRepository.find(2L, 10L)).isPresent(),
            () -> assertThat(likeRepository.find(1L, 20L)).isPresent()
        );
    }
}
