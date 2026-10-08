package com.loopers.application.brand;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
     * 브랜드와 연결된 미삭제 상품을 함께 삭제한다. Brand·Product에 걸친 조율이라 여기(application)에 두고,
     * 그 조율 전체를 하나의 물리 트랜잭션으로 묶는다 — 안쪽 Service 메서드들은 REQUIRED로 참여만 한다.
     * 안쪽 예외를 catch해 일부 성공으로 바꾸지 않는다: 참여 중 실패는 rollback-only를 남기므로
     * 정상 반환해도 전체가 rollback된다 (docs/week3/design.md 2번 섹션).
     */
    @Transactional
    public void deleteBrand(Long id) {
        brandService.getBrand(id); // 존재하지 않거나 이미 삭제됐으면 NOT_FOUND
        productService.deleteAllByBrandId(id);
        brandService.deleteBrand(id);
    }
}
