package com.loopers.application.product;

import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductListCache;
import com.loopers.domain.product.ProductListCacheException;
import com.loopers.domain.product.ProductListSnapshot;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductSort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductListSingleFlightServiceTest {
    private final ProductQueryRepository repository = mock(ProductQueryRepository.class);
    private final ProductListReader reader = mock(ProductListReader.class);
    private final ProductListCache cache = mock(ProductListCache.class);
    private final ProductQueryService queries = new ProductQueryService(repository, reader, cache,
        new ProductListSingleFlight(500, 64));
    private final ConcurrentHashMap<Long, ProductListSnapshot> stored = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(8);
    private final List<CountDownLatch> releases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(cache.isEnabled()).thenReturn(true);
        when(reader.currentRevision()).thenReturn(7L);
        when(reader.readSnapshot()).thenReturn(snapshot(7));
        when(cache.find(anyLong())).thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.<Long>getArgument(0))));
        doAnswer(invocation -> {
            ProductListSnapshot value = invocation.getArgument(0);
            stored.put(value.revision(), value);
            return null;
        }).when(cache).store(any());
    }

    @AfterEach
    void closeWorkers() throws InterruptedException {
        releases.forEach(CountDownLatch::countDown);
        workers.shutdownNow();
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void eightInitialMissesForOneRevisionComputeAndStoreOneSnapshot() throws Exception {
        CountDownLatch initialLookups = new CountDownLatch(8);
        CountDownLatch enteredSnapshot = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = release();
        var lookedUp = ConcurrentHashMap.<Thread>newKeySet();
        when(cache.find(7)).thenAnswer(invocation -> {
            if (lookedUp.add(Thread.currentThread())) {
                initialLookups.countDown();
                await(initialLookups);
                return Optional.empty();
            }
            return Optional.ofNullable(stored.get(7L));
        });
        when(reader.readSnapshot()).thenAnswer(invocation -> {
            enteredSnapshot.countDown();
            await(releaseSnapshot);
            return snapshot(7);
        });
        List<Future<PageResult<ProductInfo>>> results = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            results.add(workers.submit(this::read));
        }
        await(initialLookups);
        await(enteredSnapshot);
        releaseSnapshot.countDown();
        for (var result : results) {
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(page(7));
        }
        verify(reader).readSnapshot();
        verify(cache).store(snapshot(7));
    }

    @Test
    void delayedInitialMissRechecksAfterAnotherRequestHasAlreadyFilledAndFinished() throws Exception {
        AtomicBoolean first = new AtomicBoolean(true);
        CountDownLatch capturedMiss = new CountDownLatch(1);
        CountDownLatch releaseMiss = release();
        when(cache.find(7)).thenAnswer(invocation -> {
            if (first.compareAndSet(true, false)) {
                capturedMiss.countDown();
                await(releaseMiss);
                return Optional.empty();
            }
            return Optional.ofNullable(stored.get(7L));
        });
        Future<PageResult<ProductInfo>> delayed = workers.submit(this::read);
        await(capturedMiss);
        assertThat(read()).isEqualTo(page(7));
        releaseMiss.countDown();

        assertThat(delayed.get(5, TimeUnit.SECONDS)).isEqualTo(page(7));
        verify(reader).readSnapshot();
        verify(cache).store(snapshot(7));
    }

    @Test
    void newerRevisionDoesNotWaitForOldLoaderOrReceiveItsOldResult() throws Exception {
        AtomicLong current = new AtomicLong(7);
        ThreadLocal<Long> checked = new ThreadLocal<>();
        CountDownLatch oldSnapshot = new CountDownLatch(1);
        CountDownLatch releaseOld = release();
        when(reader.currentRevision()).thenAnswer(invocation -> {
            long revision = current.get();
            checked.set(revision);
            return revision;
        });
        when(reader.readSnapshot()).thenAnswer(invocation -> {
            long revision = checked.get();
            if (revision == 7) {
                oldSnapshot.countDown();
                await(releaseOld);
            }
            return snapshot(revision);
        });
        Future<PageResult<ProductInfo>> old = workers.submit(this::read);
        await(oldSnapshot);
        current.set(8);
        Future<PageResult<ProductInfo>> latest = workers.submit(this::read);
        assertThat(latest.get(2, TimeUnit.SECONDS)).isEqualTo(page(8));
        assertThat(stored.get(8L)).isEqualTo(snapshot(8));
        assertThat(old.isDone()).isFalse();
        releaseOld.countDown();
        assertThat(old.get(5, TimeUnit.SECONDS)).isEqualTo(page(7));
        assertThat(read()).isEqualTo(page(8));
        assertThat(stored.get(7L)).isEqualTo(snapshot(7));
        assertThat(stored.get(8L)).isEqualTo(snapshot(8));
    }

    @Test
    void recheckFailureFallsBackWithoutSnapshotOrAnotherRedisAttempt() {
        when(cache.find(7)).thenReturn(Optional.empty())
            .thenThrow(new ProductListCacheException("recheck failed", new IllegalStateException()));
        PageResult<ProductInfo> fallback = PageResult.of(List.of(), 0, 20, 0);
        when(reader.readList(null, 0, 20, ProductSort.LIKES_DESC)).thenReturn(fallback);

        assertThat(read()).isEqualTo(fallback);

        verify(reader).readList(null, 0, 20, ProductSort.LIKES_DESC);
        verify(reader, never()).readSnapshot();
        verify(cache, never()).store(any());
    }

    @Test
    void waitOrCapacityFallbackUsesDatabaseWithoutSnapshotOrStore() {
        ProductListSingleFlight unavailable = mock(ProductListSingleFlight.class);
        when(unavailable.isEnabled()).thenReturn(true);
        when(unavailable.load(anyLong(), any())).thenReturn(Optional.empty());
        PageResult<ProductInfo> fallback = page(8);
        when(reader.readList(null, 0, 20, ProductSort.LIKES_DESC)).thenReturn(fallback);
        ProductQueryService service = new ProductQueryService(repository, reader, cache, unavailable);

        assertThat(service.getList(null, 0, 20, ProductSort.LIKES_DESC)).isEqualTo(fallback);

        verify(reader).readList(null, 0, 20, ProductSort.LIKES_DESC);
        verify(cache).find(7);
        verify(reader, never()).readSnapshot();
        verify(cache, never()).store(any());
    }

    @Test
    void disabledSingleFlightPreservesTheOriginalCacheMissPath() {
        ProductQueryService service = new ProductQueryService(repository, reader, cache,
            new ProductListSingleFlight(500, 64, false));

        assertThat(service.getList(null, 0, 20, ProductSort.LIKES_DESC)).isEqualTo(page(7));

        verify(cache, times(1)).find(7);
        verify(reader).readSnapshot();
        verify(cache).store(snapshot(7));
    }

    private CountDownLatch release() {
        CountDownLatch latch = new CountDownLatch(1);
        releases.add(latch);
        return latch;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private PageResult<ProductInfo> read() {
        return queries.getList(null, 0, 20, ProductSort.LIKES_DESC);
    }

    private PageResult<ProductInfo> page(long revision) {
        return snapshot(revision).page().map(ProductInfo::from);
    }

    private ProductListSnapshot snapshot(long revision) {
        return new ProductListSnapshot(revision, PageResult.of(List.of(
            new ProductListSnapshot.Item(10L, "상품", 1000, 5, 1L, "브랜드", revision)), 0, 20, 1));
    }
}
