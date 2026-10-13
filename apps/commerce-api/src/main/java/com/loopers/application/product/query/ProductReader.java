package com.loopers.application.product.query;

import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 상품 조회 유스케이스 (DR-31). 요청자 확인(BC-01)은 Service 로, 카탈로그 조회는 조회 Repository 한 번으로.
 * 고객 상세와 latest 첫 페이지 목록은 revision 기반 캐시를 거친다 (DR-37).
 */
@RequiredArgsConstructor
@Component
public class ProductReader {
    private final UserService userService;
    private final ProductQueryRepository productQueryRepository;
    private final ProductCache productCache;
    private final ProductCacheLoader productCacheLoader;

    /**
     * FR-PRODUCT-01 상품 목록 조회 (고객). 삭제되지 않은 상품만, 정렬 하나 (ASM-08).
     * brandId 가 null 이면 전체, 있으면 그 브랜드만. 없는·삭제된 브랜드는 확인하지 않고 빈 목록 (DR-35).
     */
    @Transactional(readOnly = true)
    public PageResult<ProductView.Summary> listProducts(Long requesterId, Long brandId, ProductSort sort, PageQuery query) {
        userService.getUser(requesterId);
        return productQueryRepository.findActivePage(brandId, sort, query);
    }

    /**
     * FR-PRODUCT-02 상품 상세 조회 (고객). 없음·삭제됨 모두 ER-04 PRODUCT_NOT_FOUND.
     * 캐시 (DR-37): DB 의 현재 상품·브랜드 revision 에 대응하는 키만 읽는다. 좋아요 수는 캐시에 없어 revision 과 함께 읽은 값을 붙인다.
     * miss 면 Loader 가 한 트랜잭션에서 다시 읽은 revision 의 키에 채운다. hit 경로는 트랜잭션 없이 문장마다 읽는다.
     */
    public ProductView.Summary getProduct(Long requesterId, Long productId) {
        userService.getUser(requesterId);
        ProductView.DetailState state = productQueryRepository.findDetailState(productId)
            .orElseThrow(() -> productNotFound(productId));
        Optional<ProductCacheValue.Item> cached = productCache.findDetail(productId, state.productRevision(), state.brandRevision());
        if (cached.isPresent()) {
            return cached.get().withLikeCount(state.likeCount());
        }
        ProductCacheLoader.LoadedDetail loaded = productCacheLoader.loadDetail(productId)
            .orElseThrow(() -> productNotFound(productId));
        productCache.putDetail(productId, loaded.productRevision(), loaded.brandRevision(), ProductCacheValue.Item.from(loaded.summary()));
        return loaded.summary();
    }

    /** FR-ADMIN-PRODUCT-01 상품 목록 (관리자). 삭제 포함, 최신순. */
    @Transactional(readOnly = true)
    public PageResult<ProductView.Admin> listProductsForAdmin(Long requesterId, PageQuery query) {
        userService.getAdmin(requesterId);
        return productQueryRepository.findPage(query);
    }

    /** FR-ADMIN-PRODUCT-03 상품 상세 (관리자). 삭제 여부 무관. */
    @Transactional(readOnly = true)
    public ProductView.Admin getProductForAdmin(Long requesterId, Long productId) {
        userService.getAdmin(requesterId);
        return productQueryRepository.find(productId)
            .orElseThrow(() -> productNotFound(productId));
    }

    private static CoreException productNotFound(Long productId) {
        return new CoreException(ErrorType.PRODUCT_NOT_FOUND, "[id = " + productId + "] 상품을 찾을 수 없습니다.");
    }
}
