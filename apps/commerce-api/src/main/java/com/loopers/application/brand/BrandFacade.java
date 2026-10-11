package com.loopers.application.brand;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductService;
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

    /** FR-ADMIN-BRAND-02 브랜드 생성. ST-01 (없음) → ACTIVE. */
    @Transactional
    public BrandInfo createBrand(Long requesterId, String name) {
        userService.getAdmin(requesterId);
        return BrandInfo.from(brandService.create(name));
    }

    /** FR-ADMIN-BRAND-04 브랜드 수정. 연결된 상품에는 영향 없음. */
    @Transactional
    public BrandInfo updateBrand(Long requesterId, Long brandId, String name) {
        userService.getAdmin(requesterId);
        return BrandInfo.from(brandService.update(brandId, name));
    }

    /**
     * FR-ADMIN-BRAND-05 브랜드 삭제. ST-01 ACTIVE → DELETED.
     * INV-10 은 삭제되지 않은 소속 상품을 같은 트랜잭션에서 함께 삭제해 지킨다 (DR-04, DR-33). 중간에 실패하면 전부 롤백된다.
     */
    @Transactional
    public void deleteBrand(Long requesterId, Long brandId) {
        userService.getAdmin(requesterId);
        brandService.getActive(brandId);
        productService.deleteAllOfBrand(brandId);
        brandService.delete(brandId);
    }
}
