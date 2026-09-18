package com.loopers.like.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LikeDuplicationCheckerTest {

    @DisplayName("[R-LIKE-02] 같은 고객은 같은 상품에 좋아요를 중복으로 보유할 수 없다.")
    @Nested
    class CheckDuplication {

        @DisplayName("[의사결정표] 같은 고객과 상품의 좋아요가 있으면 중복이다.")
        @Test
        void returnsTrue_whenLikeAlreadyExists() {
            LikeRepository repository = mock(LikeRepository.class);
            when(repository.findByUserIdAndProductId(1L, 2L))
                .thenReturn(Optional.of(new Like(1L, 2L)));
            LikeDuplicationChecker checker = new LikeDuplicationChecker(repository);

            assertThat(checker.isDuplicated(1L, 2L)).isTrue();
        }

        @DisplayName("[의사결정표] 같은 고객과 상품의 좋아요가 없으면 중복이 아니다.")
        @Test
        void returnsFalse_whenLikeDoesNotExist() {
            LikeRepository repository = mock(LikeRepository.class);
            when(repository.findByUserIdAndProductId(1L, 2L)).thenReturn(Optional.empty());
            LikeDuplicationChecker checker = new LikeDuplicationChecker(repository);

            assertThat(checker.isDuplicated(1L, 2L)).isFalse();
        }
    }
}
