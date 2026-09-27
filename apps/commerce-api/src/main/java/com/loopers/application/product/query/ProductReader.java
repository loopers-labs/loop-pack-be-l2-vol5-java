package com.loopers.application.product.query;

import com.loopers.domain.product.ProductSort;
import com.loopers.domain.user.UserService;
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
    public PageResult<ProductView.Summary> listProducts(Long requesterId, String sort, PageQuery query) {
        userService.getUser(requesterId);
        return productQueryRepository.findActivePage(ProductSort.from(sort), query);
    }
}
