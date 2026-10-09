package com.loopers.domain.like;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LikeServiceTest {

    @Mock
    private LikeRepository likeRepository;

    @Test
    void createsLikeOnlyWhenRelationshipDoesNotExist() {
        when(likeRepository.registerIfAbsent(any(LikeModel.class))).thenReturn(true);

        assertThat(new LikeService(likeRepository).register(1L, 2L)).isTrue();

        verify(likeRepository).registerIfAbsent(any(LikeModel.class));
    }

    @Test
    void keepsExistingLikeOnRepeatedRegister() {
        when(likeRepository.registerIfAbsent(any(LikeModel.class))).thenReturn(false);

        assertThat(new LikeService(likeRepository).register(1L, 2L)).isFalse();

        verify(likeRepository).registerIfAbsent(any(LikeModel.class));
    }

    @Test
    void cancelsExistingLikeAndIgnoresAbsentLike() {
        new LikeService(likeRepository).cancel(1L, 2L);

        verify(likeRepository).deleteRelationship(1L, 2L);
    }
}
