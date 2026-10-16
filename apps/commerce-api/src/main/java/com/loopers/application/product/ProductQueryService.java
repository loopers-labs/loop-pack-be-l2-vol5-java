package com.loopers.application.product;

import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductListCache;
import com.loopers.domain.product.ProductListCacheException;
import com.loopers.domain.product.ProductListSnapshot;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductSort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ProductQueryService {

    private final ProductQueryRepository repository;
    private final ProductListReader reader;
    private final ProductListCache cache;
    private final ProductListSingleFlight singleFlight;

    public ProductQueryService(ProductQueryRepository repository, ProductListReader reader, ProductListCache cache,
                               ProductListSingleFlight singleFlight) {
        this.repository = repository;
        this.reader = reader;
        this.cache = cache;
        this.singleFlight = singleFlight;
    }

    @Transactional(readOnly = true)
    public ProductInfo getDetail(long productId) {
        return repository.findDetail(productId, false).map(ProductInfo::from)
            .orElseThrow(() -> new ProductQueryException(ProductQueryException.Reason.PRODUCT_NOT_FOUND));
    }

    public PageResult<ProductInfo> getList(Long brandId, int page, int size, ProductSort sort) {
        if (brandId != null || page != 0 || size != 20 || sort != ProductSort.LIKES_DESC || !cache.isEnabled()
            || TransactionSynchronizationManager.isActualTransactionActive()) {
            // 외부 쓰기 트랜잭션의 미커밋 상태를 공통 캐시로 게시하지 않는다.
            return reader.readList(brandId, page, size, sort);
        }
        long revision = reader.currentRevision();
        try {
            var cached = cache.find(revision);
            if (cached.isPresent()) {
                return cached.get().page().map(ProductInfo::from);
            }
        } catch (ProductListCacheException exception) {
            // 장애에서 GET에 이어 SET까지 다시 대기하지 않는다.
            return reader.readList(brandId, page, size, sort);
        }
        if (!singleFlight.isEnabled()) {
            return readAndStoreSnapshot().page().map(ProductInfo::from);
        }
        try {
            // 첫 miss 뒤 다른 요청이 이미 채웠을 수 있으므로 리더가 한 번 재확인한다.
            return singleFlight.load(revision, () -> cache.find(revision).orElseGet(this::readAndStoreSnapshot))
                .map(snapshot -> snapshot.page().map(ProductInfo::from))
                .orElseGet(() -> reader.readList(brandId, page, size, sort));
        } catch (ProductListCacheException exception) {
            // 리더의 재확인 GET 실패도 DB로 우회하며 추가 Redis 요청을 하지 않는다.
            return reader.readList(brandId, page, size, sort);
        }
    }

    private ProductListSnapshot readAndStoreSnapshot() {
        ProductListSnapshot snapshot = reader.readSnapshot();
        try {
            // 최초 GET 이후 변경됐을 수 있으므로 같은 DB 스냅샷에서 얻은 버전을 사용한다.
            cache.store(snapshot);
        } catch (ProductListCacheException exception) {
            // 어댑터가 오류를 관찰하며, 올바른 DB 조회 결과는 유지한다.
        }
        return snapshot;
    }
}
