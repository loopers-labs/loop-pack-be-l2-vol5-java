package com.loopers.application.product;

import com.loopers.application.brand.BrandInfo;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductListCache;
import com.loopers.domain.product.ProductListCacheException;
import com.loopers.domain.product.ProductListSnapshot;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductSort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductListCacheCoordinatorTest {
    private final ProductQueryRepository repository = mock(ProductQueryRepository.class);
    private final ProductListReader reader = mock(ProductListReader.class);
    private final ProductListCache cache = mock(ProductListCache.class);
    private final ProductQueryService queries = new ProductQueryService(repository, reader, cache,
        new ProductListSingleFlight(500, 64));

    @BeforeEach
    void setUp() {
        when(cache.isEnabled()).thenReturn(true);
        when(reader.currentRevision()).thenReturn(7L);
        when(repository.findPage(any(), anyInt(), anyInt(), any(), eq(false)))
            .thenReturn(PageResult.of(List.of(), 0, 20, 0));
    }

    @Test
    void usesOnlyTheCurrentRevisionOnHitWithoutLoadingTheExpensiveSnapshot() {
        when(cache.find(7)).thenReturn(Optional.of(snapshot(7)));

        assertThat(read()).isEqualTo(expected());

        verify(reader).currentRevision();
        verify(reader, never()).readSnapshot();
        verify(cache, never()).store(any());
        verifyNoInteractions(repository);
    }

    @Test
    void storesUnderTheRevisionReadWithTheSnapshotRatherThanTheEarlierLookup() {
        when(cache.find(7)).thenReturn(Optional.empty());
        when(reader.readSnapshot()).thenReturn(snapshot(8));

        assertThat(read()).isEqualTo(expected());

        verify(cache).store(snapshot(8));
        verifyNoInteractions(repository);
    }

    @Test
    void fallsBackToDatabaseWithoutASecondRedisAttemptAfterGetFailure() {
        when(cache.find(7)).thenThrow(new ProductListCacheException("GET failed", new IllegalStateException()));
        when(reader.readList(null, 0, 20, ProductSort.LIKES_DESC)).thenReturn(expected());

        assertThat(read()).isEqualTo(expected());

        verify(cache, never()).store(any());
        verify(reader, never()).readSnapshot();
    }

    @Test
    void returnsTheDatabaseResultWhenCacheStorageFails() {
        when(cache.find(7)).thenReturn(Optional.empty());
        when(reader.readSnapshot()).thenReturn(snapshot(8));
        doThrow(new ProductListCacheException("SET failed", new IllegalStateException())).when(cache).store(any());

        assertThat(read()).isEqualTo(expected());
    }

    @Test
    void bypassesCacheForOtherPagesSizesFiltersAndSorts() {
        for (ProductSort sort : List.of(ProductSort.LATEST, ProductSort.PRICE_ASC)) {
            when(reader.readList(null, 0, 20, sort)).thenReturn(expected());
            assertThat(queries.getList(null, 0, 20, sort)).isEqualTo(expected());
        }
        when(reader.readList(1L, 0, 20, ProductSort.LIKES_DESC)).thenReturn(expected());
        when(reader.readList(null, 1, 20, ProductSort.LIKES_DESC)).thenReturn(expected());
        when(reader.readList(null, 0, 10, ProductSort.LIKES_DESC)).thenReturn(expected());
        assertThat(queries.getList(1L, 0, 20, ProductSort.LIKES_DESC)).isEqualTo(expected());
        assertThat(queries.getList(null, 1, 20, ProductSort.LIKES_DESC)).isEqualTo(expected());
        assertThat(queries.getList(null, 0, 10, ProductSort.LIKES_DESC)).isEqualTo(expected());
        verify(reader, never()).currentRevision();
        verify(cache, never()).find(anyLong());
        verify(cache, never()).store(any());
    }

    @Test
    void disabledCachePreservesTheDatabaseOnlyPath() {
        when(cache.isEnabled()).thenReturn(false);
        when(reader.readList(null, 0, 20, ProductSort.LIKES_DESC)).thenReturn(expected());

        assertThat(read()).isEqualTo(expected());

        verify(reader, never()).currentRevision();
        verify(cache, never()).find(anyLong());
        verify(cache, never()).store(any());
    }

    @Test
    void neverPublishesResultsFromAnExistingTransaction() {
        when(reader.readList(null, 0, 20, ProductSort.LIKES_DESC)).thenReturn(expected());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(read()).isEqualTo(expected());
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(reader, never()).currentRevision();
        verify(cache, never()).find(anyLong());
        verify(cache, never()).store(any());
    }

    @Test
    void doesNotHideDatabaseFailureByReturningAnUnverifiedCacheEntry() {
        when(reader.currentRevision()).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class)
            .hasMessage("database unavailable");

        verify(cache, never()).find(anyLong());
        verify(cache, never()).store(any());
    }

    private PageResult<ProductInfo> read() {
        return queries.getList(null, 0, 20, ProductSort.LIKES_DESC);
    }

    private ProductListSnapshot snapshot(long revision) {
        return new ProductListSnapshot(revision, PageResult.of(List.of(
            new ProductListSnapshot.Item(10L, "상품", 1000, 5, 1L, "브랜드", 3)), 0, 20, 1));
    }

    private PageResult<ProductInfo> expected() {
        return PageResult.of(List.of(new ProductInfo(10L, "상품", 1000, 5, new BrandInfo(1L, "브랜드"), 3)), 0, 20, 1);
    }
}
