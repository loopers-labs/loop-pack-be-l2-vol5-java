package com.loopers.product.application;

import com.loopers.product.application.port.in.ProductAdminInfo;
import com.loopers.product.application.port.out.ProductPort;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 상품 조회. 고객 조회(ProductQueryService)와 달리 판매 여부와 관계없이 삭제되지 않은 상품을 본다 (PRD-06, P-10).
 */
@RequiredArgsConstructor
@Component
@Transactional(readOnly = true)
public class ProductAdminQueryService {

    private final ProductPort productPort;

    public Page<ProductAdminInfo> getProducts(Long brandId, Pageable pageable) {
        return productPort.findActive(brandId, pageable).map(ProductAdminInfo::from);
    }

    public ProductAdminInfo getProduct(Long productId) {
        return productPort.findActiveById(productId)
            .map(ProductAdminInfo::from)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[productId = " + productId + "] 상품을 찾을 수 없습니다."));
    }
}
