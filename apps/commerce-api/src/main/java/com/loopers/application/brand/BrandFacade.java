package com.loopers.application.brand;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.productlike.ProductLikeService;
import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class BrandFacade {
    private final UserService userService;
    private final BrandService brandService;
    private final ProductService productService;
    private final ProductLikeService productLikeService;

    /** FR-ADMIN-BRAND-02 브랜드 생성. ST-01 (없음) → ACTIVE. */
    @Transactional
    public BrandInfo createBrand(Long requesterId, String name) {
        userService.getAdmin(requesterId);
        return BrandInfo.from(brandService.create(name));
    }

    /**
     * FR-ADMIN-BRAND-04 브랜드 수정. 연결된 상품에는 영향 없음.
     * 상품 응답에 브랜드 이름이 실리므로 브랜드 revision 은 수정이 올리고, 고객 목록 캐시의 revision 도 올린다 (DR-37).
     */
    @Transactional
    public BrandInfo updateBrand(Long requesterId, Long brandId, String name) {
        userService.getAdmin(requesterId);
        BrandInfo info = BrandInfo.from(brandService.update(brandId, name));
        productService.bumpListRevision();
        return info;
    }

    /**
     * FR-ADMIN-BRAND-05 브랜드 삭제. ST-01 ACTIVE → DELETED.
     * INV-10 은 삭제되지 않은 소속 상품을 같은 트랜잭션에서 함께 삭제해 지킨다 (DR-04, DR-33). 중간에 실패하면 전부 롤백된다.
     * 그 상품들의 좋아요 수 행 삭제도 함께 반영한다 (DR-36).
     * 브랜드·상품 revision 은 각 삭제가 올리고, 고객 목록 캐시의 revision 도 올린다 (DR-37).
     */
    @Transactional
    public void deleteBrand(Long requesterId, Long brandId) {
        userService.getAdmin(requesterId);
        brandService.getActive(brandId);
        productService.deleteAllOfBrand(brandId);
        productLikeService.deleteStatsOfBrand(brandId);
        brandService.delete(brandId);
        productService.bumpListRevision();
    }
}
