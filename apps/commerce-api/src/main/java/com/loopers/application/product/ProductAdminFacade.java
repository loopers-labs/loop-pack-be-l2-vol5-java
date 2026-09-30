package com.loopers.application.product;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 상품 변경(생성·수정·삭제·재고변경) 유스케이스를 담당한다 (BrandAdminFacade와 같은 원칙: docs/week2/design.md 1번 섹션).
// 조회는 ProductFacade가 담당한다 — 조회는 고객·관리자가 같은 이유로 바뀌지만, 변경은 업무 규칙 때문에 바뀌어 여기서 분리한다.
// 상품 생성은 Brand·Product 두 aggregate에 걸친 조율(브랜드 존재·미삭제 확인)이 필요하므로
// 여기(application)에서 BrandService를 호출한다 — domain 계층인 ProductService가 다른 aggregate를 직접 알게 하지 않는다.
@RequiredArgsConstructor
@Component
public class ProductAdminFacade {

    private final BrandService brandService;
    private final ProductService productService;

    public ProductInfo createProduct(String name, Long price, Long brandId, int initialStock) {
        brandService.getBrand(brandId); // 존재하지 않거나 삭제된 브랜드면 NOT_FOUND
        ProductModel product = productService.createProduct(name, price, brandId, initialStock);
        return ProductInfo.from(product);
    }

    public ProductInfo updateProduct(Long id, String name, Long price) {
        ProductModel product = productService.updateProduct(id, name, price);
        return ProductInfo.from(product);
    }

    public ProductInfo changeStock(Long id, int quantity) {
        ProductModel product = productService.changeStock(id, quantity);
        return ProductInfo.from(product);
    }

    public void deleteProduct(Long id) {
        productService.deleteProduct(id);
    }
}
