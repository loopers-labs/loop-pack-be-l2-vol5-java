package com.loopers.application.product;

import com.loopers.domain.product.ProductListSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Component
public class ProductListSingleFlight {
    private final ConcurrentHashMap<Long, CompletableFuture<ProductListSnapshot>> inFlight = new ConcurrentHashMap<>();
    private final Semaphore slots;
    private final long waitMillis;
    private final boolean enabled;

    public ProductListSingleFlight(long waitMillis, int capacity) {
        this(waitMillis, capacity, true);
    }

    @Autowired
    public ProductListSingleFlight(
        @Value("${commerce.product-list-cache.single-flight-wait-millis:500}") long waitMillis,
        @Value("${commerce.product-list-cache.single-flight-max-in-flight:64}") int capacity,
        @Value("${commerce.product-list-cache.single-flight-enabled:true}") boolean enabled) {
        if (waitMillis <= 0 || capacity <= 0) {
            throw new IllegalArgumentException("Single-flight wait and capacity must be positive");
        }
        this.waitMillis = waitMillis;
        this.slots = new Semaphore(capacity);
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<ProductListSnapshot> load(long lookupRevision, Supplier<ProductListSnapshot> loader) {
        CompletableFuture<ProductListSnapshot> current = inFlight.get(lookupRevision);
        if (current != null) {
            return await(current);
        }
        if (!slots.tryAcquire()) {
            // Permit 경쟁 중 같은 버전의 leader가 등록됐다면 새 슬롯 없이 합류한다.
            current = inFlight.get(lookupRevision);
            return current == null ? Optional.empty() : await(current);
        }
        CompletableFuture<ProductListSnapshot> own = new CompletableFuture<>();
        current = inFlight.putIfAbsent(lookupRevision, own);
        if (current != null) {
            slots.release();
            return await(current);
        }
        try {
            ProductListSnapshot snapshot = Objects.requireNonNull(loader.get(), "Single-flight loader result");
            own.complete(snapshot);
            return Optional.of(snapshot);
        } catch (RuntimeException | Error failure) {
            // get()의 CompletionException 자동 해제로 원래 loader 실패가 달라지는 것을 막는다.
            own.completeExceptionally(new SharedLoadFailure(failure));
            throw failure;
        } finally {
            // 완료 결과를 로컬 캐시로 남기지 않으며 다른 flight를 제거하지 않는다.
            inFlight.remove(lookupRevision, own);
            slots.release();
        }
    }

    private Optional<ProductListSnapshot> await(CompletableFuture<ProductListSnapshot> current) {
        try {
            return Optional.of(current.get(waitMillis, TimeUnit.MILLISECONDS));
        } catch (TimeoutException exception) {
            // 이 요청만 우회한다. 공유 future 취소/제거는 다른 대기자까지 깨뜨린다.
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for product list", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof SharedLoadFailure) {
                cause = cause.getCause();
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Unexpected product list loading failure", cause);
        }
    }

    private static final class SharedLoadFailure extends RuntimeException {
        private SharedLoadFailure(Throwable cause) {
            super(cause);
        }
    }
}
