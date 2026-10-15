package com.loopers.infrastructure.like;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LikeRepositoryImplTest {

    @Mock
    private LikeJpaRepository likeJpaRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query query;

    @InjectMocks
    private LikeRepositoryImpl repository;

    @Test
    @DisplayName("중복을 제거한 상품 ID를 IN 조건으로 바인딩하고 상품별 COUNT를 GROUP BY SQL 한 번으로 조회한다.")
    void countsDistinctProductsWithOneGroupByQuery() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(
            new Object[]{10L, 2L},
            new Object[]{20L, 1L}
        ));

        Map<Long, Long> counts = repository.countByProductIds(List.of(10L, 20L, 30L, 10L));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).isEqualTo(
            "SELECT l.product_id, COUNT(l.id) AS like_count FROM likes l"
                + " WHERE l.product_id IN (:productIds) GROUP BY l.product_id"
        );
        verify(query).setParameter("productIds", List.of(10L, 20L, 30L));
        verify(query).getResultList();
        verifyNoMoreInteractions(entityManager, query);
        verifyNoInteractions(likeJpaRepository);
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(10L, 2L, 20L, 1L));
    }

    @Test
    @DisplayName("상품 ID가 없으면 SQL을 실행하지 않는다.")
    void skipsQueryForEmptyProductIds() {
        assertThat(repository.countByProductIds(List.of())).isEmpty();

        verifyNoInteractions(entityManager, likeJpaRepository, query);
    }
}
