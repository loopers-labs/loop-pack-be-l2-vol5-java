package com.loopers.application.product;

import com.loopers.application.PageInfo;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.common.PageCondition;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class AdminProductFacade {
    private final ProductService productService;
    private final BrandService brandService;

    // 관리자 목록은 최신순 고정이며 좋아요 수가 필요 없다(7-3)
    public PageInfo<AdminProductInfo> getProducts(Long brandId, int page, int size) {
        PageCondition pageCondition = new PageCondition(page, size);
        if (brandId != null) {
            brandService.getBrand(brandId);
        }

        return PageInfo.of(
            productService.getLatestProducts(brandId, pageCondition).stream().map(AdminProductInfo::from).toList(),
            page,
            size,
            productService.countActiveProducts(brandId)
        );
    }

    public AdminProductInfo getProduct(Long productId) {
        return AdminProductInfo.from(productService.getProduct(productId));
    }

    // 브랜드 확인과 상품 저장을 한 트랜잭션으로 묶는다(PRD-001)
    @Transactional
    public AdminProductInfo register(Long brandId, String name, Long price, Long stock) {
        brandService.getBrand(brandId);
        return AdminProductInfo.from(productService.register(brandId, name, price, stock));
    }

    public AdminProductInfo update(Long productId, String name, Long price) {
        return AdminProductInfo.from(productService.update(productId, name, price));
    }

    public AdminProductInfo changeStock(Long productId, Long stock) {
        return AdminProductInfo.from(productService.changeStock(productId, stock));
    }

    public void delete(Long productId) {
        productService.delete(productId);
    }
}
