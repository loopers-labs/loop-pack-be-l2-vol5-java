package com.loopers.domain.product;

import com.loopers.domain.common.PageCondition;

public record ProductSearchCondition(Long brandId, ProductSortType sort, PageCondition page) {}
