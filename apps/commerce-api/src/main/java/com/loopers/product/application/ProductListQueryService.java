package com.loopers.product.application;

import com.loopers.product.application.port.in.ProductInfo;
import com.loopers.product.application.port.out.ProductListCachePort;
import com.loopers.product.application.port.out.ProductListKey;
import com.loopers.product.application.port.out.ProductListPage;
import com.loopers.product.domain.ProductSort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 고객 상품 목록의 cache-aside (W4). 캐시를 먼저 보고, 없으면 DB에서 읽어 캐시에 넣는다.
 * <p>
 * 캐시는 빠르게 하려고 둔 복사본이라, Redis가 실패하면 원본(DB) 조회로 우회한다. 반대로 DB 조회의 실패는 감추지 않는다 —
 * 빈 목록이나 가격 0으로 바꾸지 않고 기존 오류 계약을 그대로 따른다. 그래서 try는 캐시 호출만 감싼다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ProductListQueryService {

    /** 앞 페이지(0~2)만 캐시한다. 뒤 페이지는 hit율이 낮고, 캐시하면 키 조합만 늘어난다. */
    private static final int CACHED_PAGES = 3;

    private final ProductQueryService productQueryService;
    private final ProductListCachePort productListCachePort;

    public Page<ProductInfo> getProducts(Long brandId, ProductSort sort, Pageable pageable) {
        if (insideWriteTransaction()) {
            // 쓰기 트랜잭션 안에서 읽은 값은 아직 commit 전일 수 있다. rollback되면 존재하지 않는 결과가 되므로 캐시를 거치지 않는다.
            return productQueryService.getProducts(brandId, sort, pageable);
        }
        if (pageable.getPageNumber() >= CACHED_PAGES) {
            return productQueryService.getProducts(brandId, sort, pageable);
        }
        ProductListKey key = ProductListKey.of(brandId, sort, pageable);
        // 세대는 DB를 읽기 전에 읽는다. 그 사이 변경이 commit되면 세대가 올라가, 아래에서 저장하는 결과는 옛 세대에 들어간다.
        OptionalLong generation = currentGeneration();
        if (generation.isPresent()) {
            Optional<ProductListPage> cached = find(generation.getAsLong(), key);
            // 캐시가 맡는 것은 순서(id 목록)·전체 건수·좋아요 수다 — 무효화가 실패하면 TTL 동안 옛 값일 수 있다(표시 신선도).
            // 비노출과 이름·가격은 신선도로 허용하지 않으므로, hit여도 담긴 상품을 원본에서 다시 읽는다. 하나라도 팔 수 없으면 miss로 다룬다.
            if (cached.isPresent()) {
                Optional<List<ProductInfo>> fromSource = productQueryService.refreshFromSource(cached.get().content());
                if (fromSource.isPresent()) {
                    return cached.get().withContent(fromSource.get()).toPage();
                }
            }
        }
        Page<ProductInfo> page = productQueryService.getProducts(brandId, sort, pageable);
        generation.ifPresent(current -> save(current, key, page));
        return page;
    }

    /** Redis를 쓸 수 없으면 비어 있다 — 이번 요청은 캐시를 읽지도 채우지도 않는다. */
    private OptionalLong currentGeneration() {
        try {
            return OptionalLong.of(productListCachePort.currentGeneration());
        } catch (RuntimeException e) {
            log.warn("상품 목록 캐시를 쓸 수 없어 DB에서 읽습니다.", e);
            return OptionalLong.empty();
        }
    }

    private Optional<ProductListPage> find(long generation, ProductListKey key) {
        try {
            return productListCachePort.find(generation, key);
        } catch (RuntimeException e) {
            log.warn("상품 목록 캐시 조회 실패 — DB에서 읽습니다.", e);
            return Optional.empty();
        }
    }

    private void save(long generation, ProductListKey key, Page<ProductInfo> page) {
        try {
            productListCachePort.save(generation, key, ProductListPage.from(page));
        } catch (RuntimeException e) {
            log.warn("상품 목록 캐시 저장 실패 — 응답은 DB 결과로 정상 반환합니다.", e);
        }
    }

    private static boolean insideWriteTransaction() {
        return TransactionSynchronizationManager.isActualTransactionActive()
            && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }
}
