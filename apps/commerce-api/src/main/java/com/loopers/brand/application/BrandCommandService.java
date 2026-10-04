package com.loopers.brand.application;

import com.loopers.brand.application.port.in.BrandCommandUseCase;
import com.loopers.brand.application.port.in.BrandInfo;
import com.loopers.brand.application.port.out.BrandPort;
import com.loopers.brand.domain.BrandModel;
import com.loopers.product.application.port.out.ProductPort;
import com.loopers.product.domain.ProductModel;
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
     * BRD-02 (W3): 브랜드와 삭제되지 않은 연결 상품 전부(재고 0 포함)를 한 트랜잭션에서 논리 삭제한다 (ADR-W3-05).
     * 잠금 순서는 브랜드 → 상품 id 오름차순 (ADR-W3-03). 저장은 즉시 flush되므로 중간에 실패하면 이미 나간 UPDATE까지 함께 롤백된다.
     */
    @Transactional
    @Override
    public void delete(Long brandId) {

        BrandModel brand = brandPort.findActiveByIdForUpdate(brandId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[brandId = " + brandId + "] 브랜드를 찾을 수 없습니다."));
        brand.delete();
        brandPort.save(brand);

        for (ProductModel product : productPort.findActiveByBrandIdForUpdate(brandId)) {
            product.delete();
            productPort.save(product);
        }
    }

    private BrandModel getActiveBrand(Long brandId) {
        return brandPort.findActiveById(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[brandId = " + brandId + "] 브랜드를 찾을 수 없습니다."));
    }
}
