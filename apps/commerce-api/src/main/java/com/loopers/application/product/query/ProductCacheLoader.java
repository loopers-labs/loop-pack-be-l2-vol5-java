package com.loopers.application.product.query;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 캐시 miss 경로 (DR-37). revision 과 결과를 한 트랜잭션에서 읽어, 결과가 그 revision 시점의 것이게 한다.
 * MySQL 기본 격리 수준(REPEATABLE READ)은 트랜잭션의 첫 읽기에서 스냅샷을 정하므로 revision 을 먼저 읽는다.
 * Reader 가 트랜잭션 없이 이 빈을 불러야 프록시를 거쳐 트랜잭션이 열린다.
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

    public record LoadedDetail(long productRevision, long brandRevision, ProductView.Summary summary) {
    }
}
