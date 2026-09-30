package com.loopers.brand.application;

import com.loopers.brand.application.port.in.BrandCommandUseCase;
import com.loopers.brand.application.port.in.BrandInfo;
import com.loopers.brand.application.port.out.BrandPort;
import com.loopers.brand.domain.BrandModel;
import com.loopers.product.application.port.out.ProductPort;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class BrandCommandService implements BrandCommandUseCase {

    private final BrandPort brandPort;
    private final ProductPort productPort;

    @Transactional
    @Override
    public BrandInfo create(String name, String description) {
        BrandModel saved = brandPort.save(new BrandModel(name, description));
        return BrandInfo.from(saved);
    }

    @Transactional
    @Override
    public BrandInfo update(Long brandId, String name, String description) {
        BrandModel brand = getActiveBrand(brandId);
        brand.update(name, description);
        return BrandInfo.from(brandPort.save(brand));
    }

    /**
     * BRD-02: 삭제되지 않은 상품(재고 0 포함)이 남은 브랜드는 삭제할 수 없다.
     * 연결 상품 확인은 상품 저장소가 답하고, 삭제 행동은 브랜드가 한다.
     */
    @Transactional
    @Override
    public void delete(Long brandId) {
        BrandModel brand = getActiveBrand(brandId);
        if (productPort.existsActiveByBrandId(brandId)) {
            throw new CoreException(ErrorType.CONFLICT, "삭제되지 않은 상품이 있는 브랜드는 삭제할 수 없습니다.");
        }
        brand.delete();
    }

    private BrandModel getActiveBrand(Long brandId) {
        return brandPort.findActiveById(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[brandId = " + brandId + "] 브랜드를 찾을 수 없습니다."));
    }
}
