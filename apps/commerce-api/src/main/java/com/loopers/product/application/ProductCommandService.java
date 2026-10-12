package com.loopers.product.application;

import com.loopers.brand.application.port.out.BrandPort;
import com.loopers.product.application.port.in.ProductAdminInfo;
import com.loopers.product.application.port.in.ProductCommandUseCase;
import com.loopers.product.application.port.out.ProductPort;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 상품 변경 유스케이스. 삭제된 상품은 수정·재고 변경·삭제의 대상이 아니다 (PRD-06, P-10).
 */
@RequiredArgsConstructor
@Component
public class ProductCommandService implements ProductCommandUseCase {

    private final ProductPort productPort;
    private final BrandPort brandPort;

    /**
     * PRD-02: 존재하며 삭제되지 않은 브랜드를 참조해야 한다.
     */
    @Transactional
    @Override
    public ProductAdminInfo create(Long brandId, String name, long price, int stock) {
        // 브랜드를 잠근 채 확인해, 동시에 진행 중인 일괄 삭제가 끝난 뒤의 상태로 판단한다 (R-11, 잠금 순서 브랜드 → 상품, ADR-W3-03)
        brandPort.findActiveByIdForUpdate(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[brandId = " + brandId + "] 브랜드를 찾을 수 없습니다."));
        ProductModel saved = productPort.save(new ProductModel(brandId, name, price, stock));
        return ProductAdminInfo.from(saved);
    }

    @Transactional
    @Override
    public ProductAdminInfo update(Long productId, String name, long price) {
        ProductModel product = getActiveProduct(productId);
        product.update(name, price);
        return ProductAdminInfo.from(productPort.save(product));
    }

    @Transactional
    @Override
    public ProductAdminInfo changeStock(Long productId, int stock) {
        ProductModel product = getActiveProduct(productId);
        product.changeStock(stock);
        return ProductAdminInfo.from(productPort.save(product));
    }

    @Transactional
    @Override
    public void delete(Long productId) {
        getActiveProduct(productId).delete();
    }

    private ProductModel getActiveProduct(Long productId) {
        return productPort.findActiveByIdForUpdate(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[productId = " + productId + "] 상품을 찾을 수 없습니다."));
    }
}
