package com.loopers.application.product;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class ProductFacade {
    private final UserService userService;
    private final BrandService brandService;
    private final ProductService productService;
    private final ProductInfoAssembler assembler;

    /** FR-ADMIN-PRODUCT-02 상품 생성. INV-10 은 같은 트랜잭션에서 BrandService 조회로 지킨다 (DR-04). ST-02 (없음) → ACTIVE. */
    @Transactional
    public ProductInfo createProduct(Long requesterId, Long brandId, String name, Long price, Integer stock) {
        userService.getAdmin(requesterId);
        brandService.getActive(brandId);
        return assembler.assemble(productService.create(brandId, name, price, stock));
    }

    /** FR-ADMIN-PRODUCT-04 상품 수정. 이름·가격만 (ASM-16). 기존 주문 단가에 영향 없음 (ASM-10). */
    @Transactional
    public ProductInfo updateProduct(Long requesterId, Long productId, String name, Long price) {
        userService.getAdmin(requesterId);
        return assembler.assemble(productService.update(productId, name, price));
    }

    /** FR-ADMIN-PRODUCT-05 상품 삭제. ST-02 ACTIVE → DELETED. 좋아요·주문 품목 유지. */
    @Transactional
    public void deleteProduct(Long requesterId, Long productId) {
        userService.getAdmin(requesterId);
        productService.delete(productId);
    }

    /** FR-ADMIN-PRODUCT-06 상품 재고 변경. 최종 수량 설정. */
    @Transactional
    public StockInfo updateStock(Long requesterId, Long productId, Integer stock) {
        userService.getAdmin(requesterId);
        return StockInfo.from(productService.updateStock(productId, stock));
    }
}
