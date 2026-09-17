package com.loopers.domain.brand;

import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class BrandDeletionPolicy {

    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;

    public void delete(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다."));
        if (brand.getDeletedAt() != null) {
            throw new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다.");
        }
        if (productRepository.existsActiveByBrandId(brandId)) {
            throw new CoreException(ErrorType.CONFLICT, "삭제되지 않은 상품이 연결되어 있습니다.");
        }

        brand.delete();
        brandRepository.save(brand);
    }
}
