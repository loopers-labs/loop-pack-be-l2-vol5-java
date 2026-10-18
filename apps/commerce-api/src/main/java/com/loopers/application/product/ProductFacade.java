package com.loopers.application.product;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.productlike.ProductLikeService;
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
    private final ProductLikeService productLikeService;
    private final ProductInfoAssembler assembler;

    /**
     * FR-ADMIN-PRODUCT-02 상품 생성. INV-10 은 같은 트랜잭션에서 BrandService 조회로 지킨다 (DR-04). ST-02 (없음) → ACTIVE.
     * 좋아요 수 행을 0 으로 함께 만든다 (DR-36). 고객 목록 캐시의 revision 을 올린다 (DR-37).
     */
    @Transactional
    public ProductInfo createProduct(Long requesterId, Long brandId, String name, Long price, Integer stock) {
        userService.getAdmin(requesterId);
        brandService.getActive(brandId);
        ProductModel product = productService.create(brandId, name, price, stock);
        productLikeService.createStat(product.getId(), product.getBrandId());
        ProductInfo info = assembler.assemble(product);
        productService.bumpListRevision();
        return info;
    }

    /**
     * FR-ADMIN-PRODUCT-04 상품 수정. 이름·가격만 (ASM-16). 기존 주문 단가에 영향 없음 (ASM-10).
     * 상품 revision 은 수정이 올리고, 고객 목록 캐시의 revision 도 올린다 (DR-37).
     */
    @Transactional
    public ProductInfo updateProduct(Long requesterId, Long productId, String name, Long price) {
        userService.getAdmin(requesterId);
        ProductInfo info = assembler.assemble(productService.update(productId, name, price));
        productService.bumpListRevision();
        return info;
    }

    /**
     * FR-ADMIN-PRODUCT-05 상품 삭제. ST-02 ACTIVE → DELETED. 좋아요 수 행의 삭제도 함께 반영한다 (DR-36). 좋아요 관계·주문 품목 유지.
     * 상품 revision 은 삭제가 올리고, 고객 목록 캐시의 revision 도 올린다 (DR-37).
     */
    @Transactional
    public void deleteProduct(Long requesterId, Long productId) {
        userService.getAdmin(requesterId);
        productService.delete(productId);
        productLikeService.deleteStat(productId);
        productService.bumpListRevision();
    }

    /** FR-ADMIN-PRODUCT-06 상품 재고 변경. 최종 수량 설정. 재고는 고객 조회에 없어 캐시 revision 을 올리지 않는다 (DR-22, DR-37). */
    @Transactional
    public StockInfo updateStock(Long requesterId, Long productId, Integer stock) {
        userService.getAdmin(requesterId);
        return StockInfo.from(productService.updateStock(productId, stock));
    }
}
