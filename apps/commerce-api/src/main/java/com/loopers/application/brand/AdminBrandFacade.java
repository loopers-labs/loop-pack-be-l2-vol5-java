package com.loopers.application.brand;

import com.loopers.application.PageInfo;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.common.PageCondition;
import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class AdminBrandFacade {
    private final BrandService brandService;
    private final ProductService productService;

    public PageInfo<AdminBrandInfo> getBrands(int page, int size) {
        PageCondition pageCondition = new PageCondition(page, size);
        return PageInfo.of(
            brandService.getBrands(pageCondition).stream().map(AdminBrandInfo::from).toList(),
            page,
            size,
            brandService.countBrands()
        );
    }

    public AdminBrandInfo getBrand(Long brandId) {
        return AdminBrandInfo.from(brandService.getBrand(brandId));
    }

    public AdminBrandInfo register(String name) {
        return AdminBrandInfo.from(brandService.register(name));
    }

    public AdminBrandInfo update(Long brandId, String name) {
        return AdminBrandInfo.from(brandService.update(brandId, name));
    }

    // 연결 상품 확인과 삭제 상태 변경을 한 트랜잭션으로 묶는다(DEL-001).
    // 미삭제 상품은 삭제되지 않은 브랜드에만 연결되므로, 확인 후 삭제 단계에서 브랜드 존재(404)를 검사한다.
    @Transactional
    public void delete(Long brandId) {
        if (productService.hasActiveProducts(brandId)) {
            throw new DomainException(DomainErrorType.CONFLICT, "[brandId = " + brandId + "] 삭제되지 않은 상품이 남아 있어 브랜드를 삭제할 수 없습니다.");
        }
        brandService.delete(brandId);
    }
}
