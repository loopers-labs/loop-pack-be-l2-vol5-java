package com.loopers.infrastructure.like;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LikeRepositoryImplTest {

    @Mock
    private LikeJpaRepository likeJpaRepository;

    @InjectMocks
    private LikeRepositoryImpl repository;

    @Test
    @DisplayName("중복을 제거한 상품 ID로 상품별 좋아요 수를 한 번에 집계한다.")
    void countsDistinctProductsWithOneGroupedQuery() {
        when(likeJpaRepository.countGroupByProductIdIn(List.of(10L, 20L, 30L))).thenReturn(List.of(
            new LikeCountRow(10L, 2L),
            new LikeCountRow(20L, 1L)
        ));

        Map<Long, Long> counts = repository.countByProductIds(List.of(10L, 20L, 30L, 10L));

        verify(likeJpaRepository).countGroupByProductIdIn(List.of(10L, 20L, 30L));
        verifyNoMoreInteractions(likeJpaRepository);
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(10L, 2L, 20L, 1L));
    }

    @Test
    @DisplayName("상품 ID가 없으면 SQL을 실행하지 않는다.")
    void skipsQueryForEmptyProductIds() {
        assertThat(repository.countByProductIds(List.of())).isEmpty();

        verifyNoInteractions(likeJpaRepository);
    }
}
