package com.loopers.domain.product;

import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.common.PageWindow;

public interface ProductAdminQuery {

    PageWindow<View> findPage(Long brandId, Sort sort, PageNumber page, PageSize size);

    record View(
        Long id,
        Long brandId,
        String brandName,
        String name,
        long price,
        int quantity
    ) {}

    enum Sort {
        LATEST,

        PRICE_ASC,

        PRICE_DESC,

        STOCK_ASC
    }
}
