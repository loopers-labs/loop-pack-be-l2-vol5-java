package com.loopers.application.product.query;

import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 상품 조회 유스케이스 (DR-31). 요청자 확인(BC-01)은 Service 로, 카탈로그 조회는 조회 Repository 한 번으로. */
@RequiredArgsConstructor
@Component
public class ProductReader {
    private final UserService userService;
    private final ProductQueryRepository productQueryRepository;

    /** FR-PRODUCT-01 상품 목록 조회 (고객). 삭제되지 않은 상품만, 정렬 하나 (ASM-08). */
    @Transactional(readOnly = true)
    public PageResult<ProductView.Summary> listProducts(Long requesterId, ProductSort sort, PageQuery query) {
        userService.getUser(requesterId);
        return productQueryRepository.findActivePage(sort, query);
    }

    /** FR-PRODUCT-02 상품 상세 조회 (고객). 없음·삭제됨 모두 ER-04 PRODUCT_NOT_FOUND. */
    @Transactional(readOnly = true)
    public ProductView.Summary getProduct(Long requesterId, Long productId) {
        userService.getUser(requesterId);
        return productQueryRepository.findActive(productId)
            .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND, "[id = " + productId + "] 상품을 찾을 수 없습니다."));
    }

    /** FR-ADMIN-PRODUCT-01 상품 목록 (관리자). 삭제 포함, 최신순. */
    @Transactional(readOnly = true)
    public PageResult<ProductView.Admin> listProductsForAdmin(Long requesterId, PageQuery query) {
        userService.getAdmin(requesterId);
        return productQueryRepository.findPage(query);
    }

    /** FR-ADMIN-PRODUCT-03 상품 상세 (관리자). 삭제 여부 무관. */
    @Transactional(readOnly = true)
    public ProductView.Admin getProductForAdmin(Long requesterId, Long productId) {
        userService.getAdmin(requesterId);
        return productQueryRepository.find(productId)
            .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND, "[id = " + productId + "] 상품을 찾을 수 없습니다."));
    }
}
