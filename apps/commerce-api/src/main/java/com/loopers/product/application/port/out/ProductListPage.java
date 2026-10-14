package com.loopers.product.application.port.out;

import com.loopers.product.application.port.in.ProductInfo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

/**
 * 캐시에 담는 목록 한 페이지. Spring의 PageImpl은 그대로 역직렬화되지 않아, 필요한 값만 담은 형태로 바꿔 저장한다.
 */
public record ProductListPage(List<ProductInfo> content, int page, int size, long totalElements) {

    public static ProductListPage from(Page<ProductInfo> page) {
        return new ProductListPage(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    public Page<ProductInfo> toPage() {
        return new PageImpl<>(content, PageRequest.of(page, size), totalElements);
    }
}
