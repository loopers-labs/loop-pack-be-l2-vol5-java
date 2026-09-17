package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class ProductFacade {

    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;

    @Transactional
    public ProductInfo register(Long brandId, String name, long price) {
        Brand brand = findActiveBrandById(brandId);
        Product product = Product.create(brand, name, price);

        Product savedProduct = productRepository.save(product);
        return ProductInfo.from(savedProduct);
    }

    private Brand findActiveBrandById(Long brandId) {
        if (brandId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드 ID는 필수입니다.");
        }

        Brand brand = brandRepository.findById(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다."));
        if (brand.getDeletedAt() != null) {
            throw new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다.");
        }
        return brand;
    }
}
