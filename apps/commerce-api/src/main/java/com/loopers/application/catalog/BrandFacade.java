package com.loopers.application.catalog;

import com.loopers.domain.catalog.BrandService;
import com.loopers.domain.catalog.ProductService;
import com.loopers.domain.user.UserService;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class BrandFacade {
    private final UserService userService;
    private final BrandService brandService;
    private final ProductService productService;

    /** FR-BRAND-01 브랜드 상세 조회 (고객). 삭제된 브랜드는 BRAND_NOT_FOUND. */
    @Transactional(readOnly = true)
    public BrandInfo getBrand(Long requesterId, Long brandId) {
        userService.getUser(requesterId);
        return BrandInfo.from(brandService.getActive(brandId));
    }

    /** FR-ADMIN-BRAND-01 브랜드 목록 (관리자). 삭제 포함, 최신순 (ASM-15, ASM-20). */
    @Transactional(readOnly = true)
    public PageResult<BrandInfo> listBrandsForAdmin(Long requesterId, PageQuery query) {
        userService.getAdmin(requesterId);
        return brandService.listAll(query).map(BrandInfo::from);
    }

    /** FR-ADMIN-BRAND-02 브랜드 생성. ST-01 (없음) → ACTIVE. */
    @Transactional
    public BrandInfo createBrand(Long requesterId, String name) {
        userService.getAdmin(requesterId);
        return BrandInfo.from(brandService.create(name));
    }

    /** FR-ADMIN-BRAND-03 브랜드 상세 (관리자). 삭제 여부 무관. */
    @Transactional(readOnly = true)
    public BrandInfo getBrandForAdmin(Long requesterId, Long brandId) {
        userService.getAdmin(requesterId);
        return BrandInfo.from(brandService.get(brandId));
    }

    /** FR-ADMIN-BRAND-04 브랜드 수정. 연결된 상품에는 영향 없음. */
    @Transactional
    public BrandInfo updateBrand(Long requesterId, Long brandId, String name) {
        userService.getAdmin(requesterId);
        return BrandInfo.from(brandService.update(brandId, name));
    }

    /** FR-ADMIN-BRAND-05 브랜드 삭제. INV-10 은 같은 트랜잭션에서 ProductService 조회로 지킨다 (DR-04). ST-01 ACTIVE → DELETED. */
    @Transactional
    public void deleteBrand(Long requesterId, Long brandId) {
        userService.getAdmin(requesterId);
        brandService.getActive(brandId);
        productService.ensureNoActiveProductsOf(brandId);
        brandService.delete(brandId);
    }
}
