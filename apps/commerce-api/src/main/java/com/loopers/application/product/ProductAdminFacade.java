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
// 캐시 무효화는 docs/week4/design.md 7번 섹션 표를 따른다. 이 Facade는 트랜잭션이 없어 Service가 commit한 뒤 즉시 실행된다.
@RequiredArgsConstructor
@Component
public class ProductAdminFacade {

    private final BrandService brandService;
    private final ProductService productService;
    private final ProductCacheInvalidator productCacheInvalidator;

    public ProductInfo createProduct(String name, Long price, Long brandId, int initialStock) {
        brandService.getBrand(brandId); // 존재하지 않거나 삭제된 브랜드면 NOT_FOUND
        ProductModel product = productService.createProduct(name, price, brandId, initialStock);
        productCacheInvalidator.listChanged(); // 목록 구성·건수가 바뀐다
        return ProductInfo.from(product);
    }

    public ProductInfo updateProduct(Long id, String name, Long price) {
        ProductModel product = productService.updateProduct(id, name, price);
        productCacheInvalidator.productChanged(id);
        productCacheInvalidator.listChanged(); // 가격·이름이 price_asc 순서와 동점 순서를 바꾼다
        return ProductInfo.from(product);
    }

    public ProductInfo changeStock(Long id, int quantity) {
        ProductModel product = productService.changeStock(id, quantity);
        productCacheInvalidator.productChanged(id); // 재고는 어떤 정렬 순서도 바꾸지 않아 목록 버전은 그대로 둔다
        return ProductInfo.from(product);
    }

    public void deleteProduct(Long id) {
        productService.deleteProduct(id);
        productCacheInvalidator.productChanged(id);
        productCacheInvalidator.listChanged();
    }
}
