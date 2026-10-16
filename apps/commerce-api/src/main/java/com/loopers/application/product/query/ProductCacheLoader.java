package com.loopers.application.product.query;

import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 한 트랜잭션에서 읽어야 하는 고객 상품 조회 (DR-37). 캐시 miss 경로는 revision 과 결과를 한 트랜잭션에서 읽어,
 * 결과가 그 revision 시점의 것이게 한다. MySQL 기본 격리 수준(REPEATABLE READ)은 트랜잭션의 첫 읽기에서 스냅샷을 정하므로
 * revision 을 먼저 읽는다. Reader 는 hit 경로에서 커넥션을 잡지 않도록 트랜잭션이 없고, 이 빈을 불러야 프록시를 거쳐 트랜잭션이 열린다.
 */
@RequiredArgsConstructor
@Component
public class ProductCacheLoader {
    private final ProductQueryRepository productQueryRepository;

    /** 존재하고 삭제되지 않은 상품의 상세와 그 시점의 상품·브랜드 revision. */
    @Transactional(readOnly = true)
    public Optional<LoadedDetail> loadDetail(Long productId) {
        return productQueryRepository.findDetailState(productId)
            .flatMap(state -> productQueryRepository.findActive(productId)
                .map(summary -> new LoadedDetail(state.productRevision(), state.brandRevision(), summary)));
    }

    /** latest 첫 페이지와 그 시점의 목록 revision. */
    @Transactional(readOnly = true)
    public LoadedLatestPage loadLatestPage(PageQuery query) {
        long listRevision = productQueryRepository.findListRevision();
        return new LoadedLatestPage(listRevision, productQueryRepository.findActivePage(null, ProductSort.LATEST, query));
    }

    /** 캐시 대상이 아닌 목록. 목록과 totalCount 를 한 트랜잭션에서 읽는다. */
    @Transactional(readOnly = true)
    public PageResult<ProductView.Summary> loadPage(Long brandId, ProductSort sort, PageQuery query) {
        return productQueryRepository.findActivePage(brandId, sort, query);
    }

    public record LoadedLatestPage(long listRevision, PageResult<ProductView.Summary> page) {
    }

    public record LoadedDetail(long productRevision, long brandRevision, ProductView.Summary summary) {
    }
}
