package com.loopers.application.brand;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 관리자 전용 변경(생성·수정·삭제) 유스케이스를 담당한다.
// 조회는 BrandFacade와 이유가 같아 공유하고, 변경은 업무 규칙 때문에 바뀌는 이유가 달라 여기서 분리한다.
// (docs/week2/design.md 1번 섹션 참고)
@RequiredArgsConstructor
@Component
public class BrandAdminFacade {
    private final BrandService brandService;
    private final ProductService productService;

    public BrandInfo createBrand(String name, String description, String category) {
        BrandModel brand = brandService.createBrand(name, description, category);
        return BrandInfo.from(brand);
    }

    public BrandInfo updateBrand(Long id, String name, String description, String category) {
        BrandModel brand = brandService.updateBrand(id, name, description, category);
        return BrandInfo.from(brand);
    }

    /**
     * 삭제되지 않은 상품이 하나라도 이 브랜드를 참조하면 거절한다(409) — 재고 0인 상품도 포함.
     * Brand·Product 두 aggregate에 걸친 확인이라 ProductAdminFacade의 브랜드 존재 확인과 같은 원칙으로
     * 여기(application)에서 조율한다 — BrandService가 Product를 직접 알게 하지 않는다.
     */
    public void deleteBrand(Long id) {
        brandService.getBrand(id); // 존재하지 않거나 이미 삭제됐으면 NOT_FOUND
        if (productService.hasActiveProduct(id)) {
            throw new CoreException(ErrorType.CONFLICT, "삭제되지 않은 상품이 남아있어 브랜드를 삭제할 수 없습니다.");
        }
        brandService.deleteBrand(id);
    }
}
