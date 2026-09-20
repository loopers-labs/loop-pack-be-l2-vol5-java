package com.loopers.application.catalog;

import com.loopers.domain.catalog.BrandService;
import com.loopers.domain.catalog.ProductModel;
import com.loopers.domain.catalog.ProductService;
import com.loopers.domain.catalog.ProductSort;
import com.loopers.domain.user.UserService;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
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

    /** FR-PRODUCT-01 상품 목록 조회 (고객). 삭제되지 않은 상품만, 정렬 하나 (ASM-08). */
    @Transactional(readOnly = true)
    public PageResult<ProductInfo> listProducts(Long requesterId, String sort, PageQuery query) {
        userService.getUser(requesterId);
        PageResult<ProductModel> page = productService.listActive(ProductSort.from(sort), query);
        return PageResult.of(assembler.assemble(page.items()), query, page.totalCount());
    }

    /** FR-PRODUCT-02 상품 상세 조회 (고객). 삭제된 상품은 PRODUCT_NOT_FOUND. */
    @Transactional(readOnly = true)
    public ProductInfo getProduct(Long requesterId, Long productId) {
        userService.getUser(requesterId);
        return assembler.assemble(productService.getActive(productId));
    }

    /** FR-ADMIN-PRODUCT-01 상품 목록 (관리자). 삭제 포함, 최신순. */
    @Transactional(readOnly = true)
    public PageResult<ProductInfo> listProductsForAdmin(Long requesterId, PageQuery query) {
        userService.getAdmin(requesterId);
        PageResult<ProductModel> page = productService.listAll(query);
        return PageResult.of(assembler.assemble(page.items()), query, page.totalCount());
    }

    /** FR-ADMIN-PRODUCT-02 상품 생성. INV-10 은 같은 트랜잭션에서 BrandService 조회로 지킨다 (DR-04). ST-02 (없음) → ACTIVE. */
    @Transactional
    public ProductInfo createProduct(Long requesterId, Long brandId, String name, Long price, Integer stock) {
        userService.getAdmin(requesterId);
        brandService.getActive(brandId);
        return assembler.assemble(productService.create(brandId, name, price, stock));
    }

    /** FR-ADMIN-PRODUCT-03 상품 상세 (관리자). 삭제 여부 무관. */
    @Transactional(readOnly = true)
    public ProductInfo getProductForAdmin(Long requesterId, Long productId) {
        userService.getAdmin(requesterId);
        return assembler.assemble(productService.get(productId));
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
