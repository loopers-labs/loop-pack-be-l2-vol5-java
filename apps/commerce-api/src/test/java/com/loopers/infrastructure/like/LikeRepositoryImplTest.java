package com.loopers.infrastructure.like;

import com.loopers.domain.like.Like;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LikeRepositoryImplTest {

    @Mock
    private LikeJpaRepository likeJpaRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private LikeRepositoryImpl repository;

    @Test
    void treatsOnlyTheUserProductDuplicateAsAnExistingRelation() {
        var sql = new SQLException("Duplicate entry '10-20' for key 'likes.uk_like_user_product'", "23000", 1062);
        when(jdbcTemplate.update(anyString(), eq(10L), eq(20L)))
            .thenThrow(new DuplicateKeyException("duplicate", sql));

        assertThat(repository.insertIfAbsent(Like.create(10L, 20L))).isFalse();
    }

    @Test
    void propagatesOtherDuplicateConstraints() {
        var sql = new SQLException("Duplicate entry '10-20' for key 'likes.uk_other'", "23000", 1062);
        var failure = new DuplicateKeyException("duplicate", sql);
        when(jdbcTemplate.update(anyString(), eq(10L), eq(20L))).thenThrow(failure);

        assertThatThrownBy(() -> repository.insertIfAbsent(Like.create(10L, 20L))).isSameAs(failure);
    }

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
