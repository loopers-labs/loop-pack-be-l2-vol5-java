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
    @DisplayName("중복을 제거한 상품별 COUNT를 UNION ALL SQL 한 번으로 실행하고 ID를 바인딩한다.")
    void countsDistinctProductsWithOneParameterizedUnionQuery() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(
            new Object[]{10L, 2L},
            new Object[]{20L, 1L},
            new Object[]{30L, 0L}
        ));

        Map<Long, Long> counts = repository.countByProductIds(List.of(10L, 20L, 30L, 10L));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).isEqualTo(
            "SELECT :productId0 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = :productId0"
                + " UNION ALL "
                + "SELECT :productId1 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = :productId1"
                + " UNION ALL "
                + "SELECT :productId2 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = :productId2"
        );
        verify(query).setParameter("productId0", 10L);
        verify(query).setParameter("productId1", 20L);
        verify(query).setParameter("productId2", 30L);
        verify(query).getResultList();
        verifyNoMoreInteractions(entityManager, query);
        verifyNoInteractions(likeJpaRepository);
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(10L, 2L, 20L, 1L));
    }

    @Test
    @DisplayName("상품 하나는 UNION 연결 없이 COUNT 한 번으로 조회한다.")
    void countsOneProductWithOneBranch() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.<Object[]>of(new Object[]{10L, 3L}));

        Map<Long, Long> counts = repository.countByProductIds(List.of(10L));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).doesNotContain("UNION ALL");
        verify(query).setParameter("productId0", 10L);
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(10L, 3L));
    }

    @Test
    @DisplayName("상품 ID가 없으면 SQL을 실행하지 않는다.")
    void skipsQueryForEmptyProductIds() {
        assertThat(repository.countByProductIds(List.of())).isEmpty();

        verifyNoInteractions(entityManager, likeJpaRepository, query);
    }
}
